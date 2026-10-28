# Outbox + Kafka — 이벤트 전달

> **구현 기준: 기능 동결 커밋 `d1a15ec` / 문서 갱신일: 2026-10-21**. 기존 단계별 계획·실행 수치는 당시 기록으로 보존한다. 이번 확인은 코드·설정 대조이며 운영 PG·Kafka 재검증을 뜻하지 않는다.

> **과거 보고서 참조 안내**: 이 문서에서 언급하는 `docs/lab/notes/`의 보고서 원문은 기능 동결 커밋 `d1a15ec`에 포함되어 있지 않다. 보고서 이름과 당시 결과는 기존 문서의 기록으로 보존하며, 이번 갱신에서 원문을 확인하거나 테스트를 재실행했다는 뜻이 아니다. 해당 보고서 이름에는 링크를 제공하지 않는다.

> 상태: 10/21 범위 구현·문서화 완료 (2~5단계). 작성: 2026-10-06. 열린 질문 결정 반영: 2026-10-06. 10/21 범위 결정 반영: 2026-10-15. relay(3단계)·컨슈머(4단계)·최종 정리(5단계) 반영: 2026-10-16. 운영 서버 Kafka 적용 반영: 2026-10-18.

> **2026-10-28 확장 (PR #246)**: 같은 Outbox·relay 위에 알림 이벤트 4종(관리자 강제 취소, 관리자 크레딧 지급, 문의 답변, 문의 등록)을 토픽 `notification-events`로 추가했다. 이 문서의 `slot-released` 설계와 발행 지점 3곳은 그대로다. 아래 "토픽은 이벤트 종류별로 나눈다" 결정과 다르게 한 토픽에 `eventType`을 둔 이유는 [notification-events.md](notification-events.md) §3에 있다.

## 한 줄 요약

취소와 이벤트 기록을 같은 트랜잭션에 묶기 위해 Kafka 직접 발행 대신 `outbox_event` 테이블에 먼저 쓰고, relay가 Kafka로 내보내며, 컨슈머는 중복 전달을 `processed_event`로 흡수한다 — 전달은 최소 1회(at-least-once), 효과는 한 번(멱등)이다.

## 1. 문제와 배경

- "자리가 났다", "충전됐다" 같은 사실을 다른 기능(대기열, 알림, 감사)에 전달해야 한다.
- DB 변경은 성공했는데 이벤트 발행이 실패하면(또는 반대) 두 곳이 어긋난다 (이중 쓰기 문제).
- 제약: 마감 2026-10-21, 인스턴스 1개, 팀 EC2에는 Kafka를 올리지 않는다(Gradle 테스트가 OOM으로 죽은 이력). *(2026-10-17 변경: 운영 서버를 c7i-flex.large로 옮기며 운영에서도 Kafka를 켰다 — 아래 "지켜야 할 규칙")* 기존 예약·취소 경로의 잠금 순서(Member/Space 데드락 이력)를 바꾸지 않아야 한다.

## 2. 선택과 이유

- **선택한 방법:** 슬롯이 풀리는 3곳(취소·강제 취소·홀드 만료)의 성공 전이와 **같은 트랜잭션**에서 `outbox_event`(PENDING)에 INSERT하고, `@Scheduled` 폴링 relay가 Kafka(`slot-released`, 키 `spaceId`)로 내보낸 뒤 PUBLISHED로 바꾼다. 컨슈머(`slotkey-waitlist-notifier`)는 `processed_event`로 중복을 걸러 알림을 저장한다. Kafka는 포트(`OutboxPublisher`) 뒤에 두고 `slotkey.kafka.enabled`(기본 꺼짐)로 켠다.
- **이유:**
  1. 같은 트랜잭션에 기록하면 롤백될 때 이벤트도 사라지고, 커밋되면 반드시 남는다. Kafka 직접 발행은 이 둘 중 하나가 어긋날 수 있다.
  2. 전달 보장을 최소 1회로 두고 중복은 컨슈머가 흡수한다. 정확히 한 번 전달을 만들려는 시도보다 단순하고 장애 때 복구가 쉽다.
  3. `outbox_event`는 FK 없는 독립 테이블이라 새 잠금 경로가 생기지 않고, relay는 잠금 없는 SELECT + PK 조건부 UPDATE라 취소 트랜잭션과 락 대기를 하지 않는다(step 2 검증).
  4. 로컬 E2E에서 얻은 사실: 전송 대기가 `delivery.timeout`보다 짧으면 같은 `eventId`가 토픽에 두 번 들어갔다. 이 관측이 타임아웃 불변식과 "중복은 정상 범위" 원칙의 근거다(아래 relay 결정).

### 선택안 (초안): 3단계로 전달 방식만 교체한다 (10/21 범위: 1·2단계를 건너뛰고 Outbox → Kafka로 구현)

| 단계 | 전달 방식 | 얻는 것 |
| --- | --- | --- |
| 1 | 서버 내부 이벤트 (`@TransactionalEventListener`) | 새 기술 없이 대기열이 먼저 동작 ([waitlist](waitlist.md)) |
| 2 | `outbox_event` 테이블 + 서버 내부 relay | 이벤트 유실 방지 (변경과 같은 트랜잭션에 기록) |
| 3 | relay의 목적지를 Kafka로 교체 | 비동기, 재처리, 확장 |

컨슈머는 처음부터 중복 이벤트에 안전하게(멱등) 만든다. 그래야 단계가 바뀌어도 컨슈머 코드를 고치지 않는다.

### 발행 지점 (작성 시점의 코드 기준)

슬롯이 풀리는 곳은 아래뿐이다 (`deleteByReservationId` 등 삭제 호출 전체 검색 결과).

| 위치 | 상황 | 발행 |
| --- | --- | --- |
| `ReservationCancelService.cancel` — 환불·위약금·토큰 폐기·상태 이력 저장 뒤, 메서드 끝 | 본인 취소 | 한다 (`CANCELLED`) |
| `AdminReservationService.forceCancel` — 토큰 폐기·환불·상태 이력·감사 로그 저장 뒤, 응답 조립 전 | 관리자 강제 취소 | 한다 (`FORCE_CANCELLED`) |
| `ReservationHoldExpirationService.expireHold` — 상태 이력 저장 뒤, 전이가 일어난 경우(`updated == 1`)만 | 홀드 만료 (배치와 즉시 정리 두 경로 공통) | 한다 (`HOLD_EXPIRED`) |
| `ReservationBatchProcessor.markNoShow` | 노쇼 (시작+15분 경과) | 하지 않는다: 이미 시작 시각이 지난 구간이라 대기자에게 의미가 없다 |

- 기록은 `SlotReleasedRecorder.record`(`Propagation.MANDATORY`)가 호출자 트랜잭션 안에서 INSERT한다. 트랜잭션이 없으면 `IllegalTransactionStateException`으로 실패하고, 롤백되면 이벤트도 함께 사라진다.
- `expireHold`도 `MANDATORY`라서 호출자 트랜잭션 안에서만 돈다. 여기에 기록하면 배치 경로와 다른 사용자의 슬롯 확보(`ReservationSlotService.secureSlots`) 중 즉시 정리 경로가 모두 같은 원자성을 얻는다.
- 위치는 "각 서비스 메서드의 마지막 쓰기"이지 "호출자 트랜잭션 전체의 마지막 쓰기"가 아니다. 즉시 정리 경로에서는 `expireHold` 뒤에 `secureSlots`의 새 슬롯 INSERT가 이어진다. 롤백 원자성은 같고, 잠금 관점의 영향은 [02 설계 문서 §1 relay 제약](../plans/waitlist-outbox/02-outbox-kafka-design.md)에 적었다.
- 체크아웃과 자동 퇴실은 슬롯을 지우지 않는다. 대상이 아니다.
- 페이로드는 `eventId`, `reservationId`, `reason`, `occurredAt` 4필드다. `spaceId`는 페이로드에 넣지 않고 `partition_key` 컬럼에만 둔다(`aggregate_id` 컬럼도 만들지 않는다). 취소·만료 후에도 예약 행은 남으므로 컨슈머가 `startTime`, `endTime`을 조회한다.

### 지켜야 할 규칙

- **`outbox_event`에는 FK를 걸지 않는다.** 이 코드베이스는 FK 검사의 공유 잠금(S) 때문에 Member/Space 잠금 순서 데드락을 겪었고 주석에 해법이 남아 있다 (`ReservationHoldService`, `AdminReservationService.forceCancel`). 독립 테이블이면 새 잠금 경로가 생기지 않는다.
- relay는 `ReservationCompletionScheduler`의 `processEach` 패턴(건별 트랜잭션, 한 건 실패해도 계속)을 따른다.
- 컨슈머는 `processed_event`의 `UNIQUE(consumer, event_id)`로 중복을 걸러낸다(컨슈머 이름이 키에 들어가 같은 토픽을 다른 컨슈머가 읽어도 서로 막지 않는다).
- Kafka는 docker compose(KRaft 단일 노드, 프로필 `kafka`)로 실행하고, 로컬과 운영 서버 모두 같은 compose 정의를 쓴다. *(2026-10-17 변경: 처음에는 "로컬 전용"이었다. 당시 팀 EC2(t3.micro)는 Gradle 테스트가 OOM으로 죽은 이력이 있어 Kafka를 얹지 않았다. 운영 백엔드를 c7i-flex.large 한 대로 옮기면서 같은 서버에 compose로 Kafka를 띄우고 `SLOTKEY_KAFKA_ENABLED=true`로 relay·컨슈머를 켰다. 브로커 힙 512MB 제한과 재시작 정책은 서버의 compose override에만 있고 레포에는 없다.)*

### 10/21 범위 (2026-10-15)

이번 구현 범위(1·2단계 생략, 폴링 Outbox → Kafka, `SlotReleased` 1종, 멱등 컨슈머, 테스트 방식)는 [plans/waitlist-outbox](../plans/waitlist-outbox/00-waitlist-outbox-plan.md)에 둔다. 발행 지점 3곳, FK 금지, 토픽 분리, 파티션 키 `spaceId`, compose 결정은 이 문서 그대로 유지한다(운영 적용은 위 "지켜야 할 규칙"의 2026-10-17 변경).

### 결정 (2026-10-06)

- **토픽은 이벤트 종류별로 나눈다.** 지금 이벤트는 하나라 토픽은 `slot-released` 하나로 시작하고, 이벤트가 늘면 토픽을 추가한다. 하나의 토픽에 타입 필드를 두는 방식은 쓰지 않는다.
  - *(2026-10-28 예외: 알림 전용 이벤트 4종은 `notification-events` 한 토픽에 `eventType`으로 구분한다. 이 이벤트들은 "도메인에서 일어난 사실"이 아니라 수신자와 문구가 정해진 "알림 요청"이고 소비자가 알림 하나뿐이라서다. 도메인 사실 이벤트에는 이 규칙을 계속 적용한다. 근거: [notification-events.md](notification-events.md) §3)*
- **파티션 키는 `spaceId`다.** 같은 공간의 이벤트는 같은 파티션에서 순서가 보장된다.
- **로컬 환경:** `backend/docker-compose.yml`에 Kafka(KRaft 단일 노드)를 별도 compose 프로필로 추가한다. 프로필을 켜지 않으면 기존 MySQL만 뜬다. 저사양 EC2에는 올리지 않는다. *(2026-10-17 변경: 운영 서버를 c7i-flex.large로 바꾼 뒤 운영에서도 이 프로필로 Kafka를 띄운다.)*

### relay 결정 (2026-10-15, 3단계)

설계 문서([02 §1](../plans/waitlist-outbox/02-outbox-kafka-design.md))에 이미 있는 폴링 방식(`@Scheduled`, id 순 N건), 건별 트랜잭션, 잠금 없는 후보 조회 + PK 조건부 UPDATE, at-least-once, 인스턴스 1개 가정은 그대로 따른다. 이 문서에 빠져 있던 것만 더한다.

- **FAILED 상태를 도입하지 않는다.** 계속 실패하는 행도 `PENDING`으로 두고 `attempts`만 올린다(`outbox_event.status`는 `PENDING`/`PUBLISHED` 두 값뿐). 상한을 넘겨 포기하는 로직이 없으므로 브로커가 오래 죽어 있어도 복구 시 이어서 발행된다.
- **Kafka 전송은 트랜잭션 밖에서 한다.** relay 자체는 트랜잭션을 열지 않고, `markPublished`/`increaseAttempts` 두 UPDATE가 각각 자기 트랜잭션이다. 전송 중 DB 잠금을 쥐지 않는다.
- **타임아웃 불변식:** `slotkey.kafka.send-timeout-ms`(전송 대기, 6000) **>** `delivery-timeout-ms`(5000) **>** `request-timeout-ms`(3000). 값은 `application.yml`의 `slotkey.kafka` 한 곳에서만 정의하고(`max-block-ms`=3000 포함) 프로듀서 설정(`spring.kafka.producer.properties`)이 이를 참조한다. 전송 대기가 delivery.timeout보다 짧으면 대기 타임아웃으로 "실패" 처리된 레코드가 프로듀서 버퍼에 남아 브로커 복구 뒤 뒤늦게 전달되고, 다음 relay 라운드의 재전송과 겹쳐 같은 이벤트가 토픽에 두 번 들어간다. **첫 수동 E2E에서 전송 대기 5초 < delivery.timeout 8초일 때 같은 `eventId`가 두 번 들어간 것을 실제로 관찰**했고, 값을 정렬한 뒤 재현되지 않았다. 기본값이 부등식을 지키는지는 단위 테스트(`KafkaTimeoutDefaultsTest`)가 확인한다.
- **중복 전달은 at-least-once의 정상 범위다.** 불변식을 지켜도 전송 성공 후 `markPublished` 직전 장애 등으로 같은 이벤트가 두 번 나갈 수 있다. 막으려고 하지 않고 컨슈머 멱등 처리(`processed_event`, 4단계)로 흡수한다. 전송은 성공했는데 `markPublished`가 실패하는 경우도 같다 — 행이 `PENDING`으로 남고 `attempts`가 오른 채 다음 라운드에 다시 발행된다(로그 메시지로 "전송 실패"와 구분하며, `OutboxRelayTest`가 이 분기를 고정한다).
- **기본은 꺼짐.** `slotkey.kafka.enabled=false`(환경변수 `SLOTKEY_KAFKA_ENABLED`)이면 `OutboxRelay`와 `KafkaOutboxPublisher` 빈이 생성되지 않는다. 브로커가 없는 환경(당시 팀 EC2, CI)이 기동에 실패하지 않게 하기 위해서다. 코드 기본값은 그대로 꺼짐이고, 운영 서버는 2026-10-17부터 환경변수로 켠다. 브로커 주소는 `KAFKA_BOOTSTRAP_SERVERS`(기본 `localhost:9092`).

### 컨슈머 결정 (2026-10-16, 4단계)

계획 문서([02 §1](../plans/waitlist-outbox/02-outbox-kafka-design.md))의 컨슈머 방향(얇은 리스너 + `SlotReleasedHandler`, `processed_event` 멱등, 알림 대상 재검사)은 그대로 따른다. 여기에는 문서에 비어 있던 값만 적는다.

- **이벤트 payload (실제 2단계 코드 `SlotReleasedRecorder.Payload` 기준):** `eventId`, `reservationId`, `reason`, `occurredAt` 4필드. `spaceId`와 구간은 payload에 없다. 컨슈머가 `reservationId`로 예약 행을 조회해 `spaceId`, `startTime`, `endTime`을 얻는다(취소·만료 뒤에도 예약 행은 남는다).
- **컨슈머 그룹 id:** `slotkey-waitlist-notifier`. 용도가 이름에 드러나게 하고, 나중에 같은 토픽을 감사 등 다른 컨슈머가 읽어도 오프셋이 섞이지 않게 한다. `processed_event.consumer`에는 같은 의미로 `waitlist-notifier`를 쓴다.
- **오프셋 시작 위치:** `auto-offset-reset=earliest`. 컨슈머가 relay보다 늦게 떠도 이벤트를 잃지 않는다. 과거 이벤트가 재생돼도 알림 대상을 "지금" 기준으로 다시 고르므로 틀린 알림은 나가지 않는다.
- **실패 시 재시도:** Spring Kafka 3.2.4의 기본 `DefaultErrorHandler`는 `FixedBackOff(0, 9)`(간격 없이 총 10회 시도 후 로그만 남기고 건너뜀)이다(`SeekUtils.DEFAULT_BACK_OFF`를 바이트코드로 확인). 즉시 10번 두들기면 DB 순간 장애에 의미가 없으므로 `FixedBackOff(1000ms, 2회 재시도)`로 짧게 제한하고, 소진되면 건너뛴다. 해석 불가능한 payload(`IllegalArgumentException`)는 재시도해도 같으므로 재시도 없이 건너뛴다. **DLT(dead-letter topic)는 3차로 미룬다** — 건너뛴 이벤트는 로그에만 남고 복구 수단이 없다는 한계를 안고 간다.
- **멱등 처리:** 한 트랜잭션 안에서 ① `INSERT IGNORE INTO processed_event(consumer, event_id)`(UNIQUE(consumer, event_id))를 먼저 실행해 영향 행이 0이면 이미 처리한 이벤트로 보고 아무것도 하지 않는다 ② 알림 INSERT. 둘이 같은 트랜잭션이므로 알림 저장이 실패하면 `processed_event`도 롤백되어 재전달 때 다시 처리된다. 알림 테이블에도 UNIQUE(event_id, waitlist_entry_id)를 걸어 이중으로 막는다.

- **한계 — 취소와 컨슈머가 경합하면 취소 직후에도 알림이 한 건 생길 수 있다:** 핸들러가 후보(활성 WAITING)를 읽은 뒤 사용자의 대기 취소가 커밋되면, `markNotified`는 WAITING이 아니라 0건을 갱신하지만 알림 저장은 이미 끝나 있어 취소한 사용자에게 알림이 저장된다(`markNotified` 반환값을 보지 않는다). 허용한다: 알림은 힌트일 뿐이고 최종 판정은 슬롯 UNIQUE이며, 이 경합을 막으려면 후보 행에 락을 걸어야 하는데 3단계의 "락 대기 없음" 원칙(취소·강제 취소·홀드 만료 트랜잭션이 relay나 컨슈머와 락을 기다리지 않는다)을 깨기 때문이다. 재검토 조건: 취소 직후 알림 민원이 관측될 때(그때는 `markNotified`를 먼저 호출하고 반환값 1일 때만 알림을 저장하는 순서 변경이 후보다).
- **한계 — `INSERT IGNORE`는 중복 외 오류도 삼킬 수 있다:** `event_id VARCHAR(36)`에 36자를 넘는 값이 들어오면 잘려 저장되어, 앞 36자가 같은 서로 다른 `event_id`를 중복으로 오판하는 것이 재현되었다(MySQL 8.4 기본 strict 모드). 현재는 수용한다: `event_id`는 우리 outbox가 만드는 36자 UUID이고 나머지 컬럼은 코드 상수·시각뿐이다. 재검토 조건: 외부 입력이 이벤트 id를 만들게 될 때 — 그때 리스너에서 길이(36자 초과)를 검증해 `IllegalArgumentException`(비재시도)으로 거른다.

- **컨슈머 테스트 (경계·엣지 케이스):** 실제 MySQL(Testcontainers)과 Fake 구성으로 Kafka 없이 돈다.
  - 멱등: 같은 이벤트 2회 순차 전달, 같은 `eventId`를 4개 스레드가 동시에 처리(예외 없이 알림·처리 기록 각 1건), 알림 저장 실패 시 처리 기록 롤백, 후보 여러 명 중 뒤쪽 후보가 실패하면 앞선 알림·알림 시각·처리 기록 전부 롤백, 같은 대기 항목에 다른 이벤트 2개면 알림 2건(재알림 쓰로틀 없음을 고정).
  - 대상 선정: 구간 일부만 비었으면 제외, 재점유(만료 전 HELD) 제외·만료된 HELD는 알림, 정지·탈퇴·CANCELLED 제외, 시작이 지난 항목 제외, 시작 시각이 핸들러의 `now`와 정확히 같으면 제외(고정 Clock으로 검증하고 쿼리 `>`를 `>=`로 바꾸면 실패함을 확인), 본인 CONFIRMED·만료 전 HELD 겹침 제외, 다른 공간·겹치지 않는 본인 예약은 알림, 대기 구간이 해제 구간보다 넓거나 일부만 겹쳐도 구간 전체가 비었으면 알림, 공간 이름 최대 100자에서도 문구가 500자 컬럼에 들어감.
  - 리스너 payload: 정상 4필드, 모르는 필드 추가, 그리고 깨진 JSON·타입 불일치(`eventId` 숫자·null·빈 문자열·공백, `reservationId` 문자열·null·실수 1.5·long 범위 초과)·배열·빈 입력은 모두 `IllegalArgumentException`(비재시도)이며 핸들러를 호출하지 않는다.
  - 이 테스트로 리스너의 결함 두 건이 드러나 고쳤다: `reservationId` 1.5가 1로 잘려 엉뚱한 예약으로 처리되던 것(정수만 허용), 빈 `eventId`가 통과해 모든 빈 이벤트가 "이미 처리함"으로 묶이던 것(공백 거절).
  - 일부러 두지 않은 테스트: 취소와 컨슈머 경합(위 한계로 허용), `eventId` 36자 초과(위 한계, 외부 입력이 생기면 도입), 켜진 컨텍스트에서 에러 핸들러가 컨테이너 팩토리에 붙는지와 실제 브로커 재시도(5단계 E2E).

## 3. 기각한 대안과 이유

| 대안 | 장점 | 기각한 이유 |
| --- | --- | --- |
| **Kafka 직접 발행**(트랜잭션 안에서 또는 커밋 뒤) | 테이블·relay가 필요 없다 | 커밋과 발행이 원자적이지 않다. 커밋 뒤 발행이 실패하면 이벤트가 유실되고, 트랜잭션 안에서 발행하면 롤백된 변경의 이벤트가 나간다(이중 쓰기 문제) |
| **서버 내부 이벤트**(`@TransactionalEventListener`)만 사용 | 새 기술 없이 대기열이 동작한다 | 프로세스가 죽으면 이벤트가 사라지고 재처리·확장 수단이 없다. 위 선택안의 1단계로만 쓰고 10/21 범위에서는 건너뛰었다 |
| **CDC**(변경 데이터 캡처) | relay 코드가 필요 없고 지연이 짧다 | Debezium 등 인프라가 늘고 저사양 환경·일정에 비해 무겁다 → 3차 |
| relay가 **`FOR UPDATE`/`SKIP LOCKED`로 행 잠금** | 다중 인스턴스에서 중복 발행을 줄인다 | `(status, id)` 범위 잠금이 취소·강제 취소·홀드 만료 트랜잭션과 락 대기를 만들어 step 2의 데드락 없음 판정을 깨뜨린다. 인스턴스 1개 가정이라 불필요 |
| **`FAILED` 상태·재시도 상한** | 영구 실패 행을 격리한다 | 상한을 넘겨 포기하는 로직이 생기고, 브로커가 오래 죽었다 살아나도 자동 복구되지 않는다 → 계속 `PENDING`으로 두고 `attempts`만 올린다. head-of-line 막힘 위험은 한계로 남겼다 |
| **DLT**(dead-letter topic) | 건너뛴 이벤트를 복구할 수 있다 | 계획상 3차 범위다 → 컨슈머는 재시도 후 건너뛰고 로그만 남긴다 |
| 멱등 판정을 **중복 키 예외 catch**로 | 코드가 직관적이다 | 호출자 트랜잭션이 rollback-only로 오염될 수 있다 → `INSERT IGNORE`의 영향 행 수(1=처음, 0=이미 처리함)로 판정 |
| 멱등 판정을 **`ON DUPLICATE KEY UPDATE`**로 | 다른 오류를 삼키지 않는다 | 영향 행 수가 드라이버 설정(`useAffectedRows`)에 따라 달라진다 |
| `processed_event`를 **JPA 엔터티**로 | 다른 테이블과 모양이 같다 | 쓰는 연산이 INSERT 하나뿐이라 엔터티가 불필요하다 → `JdbcTemplate` |
| 처리 기록을 **먼저 커밋**하고 알림을 나중에 저장 | 중복 방어가 먼저 확정된다 | 알림 저장이 실패하면 "처리됨"으로 남아 재전달해도 건너뛰므로 알림이 유실된다 → 같은 트랜잭션 |
| payload에 **구간(`startTime`/`endTime`)을 넣기** | 컨슈머의 예약 조회가 필요 없다 | 2단계 payload(4필드)를 바꿔야 하고, 컨슈머는 어차피 지금의 DB 상태를 다시 읽어야 한다 → 예약 행에서 읽는 포트(`ReservationRangeProvider`) |
| 오프셋 시작을 **`latest`**로 | 첫 기동 때 과거 이벤트를 재생하지 않는다 | 컨슈머가 relay보다 늦게 뜨면 이벤트를 잃는다 → `earliest`. 재생돼도 대상을 "지금" 기준으로 다시 고르므로 틀린 알림은 나가지 않는다 |
| 재시도를 **기본값**(간격 0ms, 총 10회)으로 | 설정이 없다 | 즉시 반복은 순간적인 DB 장애에 의미가 없다 → 1초 간격 2회 재시도(총 3회) |

## 4. Trade-off

| 얻는 것 | 잃는 것(비용·위험) | 완화책 |
| --- | --- | --- |
| 변경과 이벤트 기록이 원자적이다(롤백되면 이벤트도 없음) | 취소 트랜잭션에 INSERT가 하나 더 붙는다. `expireHold`는 호출자 트랜잭션의 마지막 쓰기가 아니다 | FK 없는 독립 테이블이라 새 잠금 경로가 없다. 위험이 확인되면 호출자 끝에서 일괄 기록하는 대안이 step-2-review.md에 있다 |
| 커밋된 Outbox를 재시도하여 일시적 발행 실패를 복구한다(최소 1회 전달 지향) | 중복 전달이 정상 범위다 | 컨슈머 멱등(`processed_event` + `UNIQUE(event_id, waitlist_entry_id)` 이중 방어) |
| relay가 잠금 없이 폴링해 취소 경로와 락 대기가 없다 | 인스턴스 2개 이상이면 같은 행을 둘이 발행한다. 1초 폴링 지연이 있다 | 인스턴스 1개 가정을 명시하고, 다중화하면 분산 락이나 CDC로 바꾼다 |
| 계속 실패하는 행도 복구 시 이어서 발행된다 | 영구 실패 행이 `batch-size`건 이상 쌓이면 뒤의 새 이벤트가 막힌다(head-of-line) | 현재 payload가 4필드 고정이라 가능성은 낮다. `attempts` 임계 관측 시 FAILED/DLT·백오프를 도입한다 |
| Kafka를 끄면 EC2가 브로커 없이도 기동한다 | 꺼진 환경에서는 알림이 생성되지 않는다 | 기본 꺼짐(`slotkey.kafka.enabled=false`), 켜는 환경은 로컬 compose와 운영 서버(2026-10-17부터 환경변수로 켬) |
| 알림 대상을 지금의 DB로 다시 고른다 | 후보 N명에 질의 약 3N+1회 | 현재 규모에서는 수용, 대기자가 수십 명 이상이면 한 질의로 합친다 |
| 재시도 후 건너뛰어 컨슈머가 막히지 않는다 | 건너뛴 이벤트는 로그에만 남고 복구 수단이 없다 | DLT는 3차. 실제 브로커에서 재시도 소진 로그를 확인했다(아래 검증 결과) |

## 5. 검증

2단계(Outbox 기록)는 `OutboxRecordIntegrationTest`로 3지점의 성공 시 PENDING 1건·롤백 시 행 없음을, 3단계(relay)는 변이 5종 실패 확인과 로컬 Kafka 수동 E2E(Kafka 중단 중에도 취소 성공 → 재시작 후 발행, 타임아웃 불변식 발견)를, 4단계(컨슈머)는 멱등·대상 선정·payload 경계 테스트와 로컬 실제 Kafka E2E(취소 → 알림 1건, 중복 전달 시 알림 유지, 동시 예약 201/409, 재점유 시 알림 없음, 깨진 payload 건너뜀, 재시도 소진 로그)를 거쳤다. 전체 `./gradlew cleanTest test`는 **814 tests, 실패 0, 오류 0, 건너뜀 11**(기존 `V2SecurityRedTest`의 `@Disabled`)이다. E2E의 홀드 만료는 SQL로 시각을 바꾼 재현이고, 재시도 소진은 중복 키 충돌을 일부러 만들어 재현했다. 단계별 상세는 아래 "검증 계획"·"검증 결과"에 있다.

### 검증 계획

- Kafka가 내려가 있어도 취소는 성공하고, 복구 후 이벤트가 이어서 발행된다.
- 같은 이벤트가 중복 전달돼도 컨슈머 효과는 한 번이다.
- 발행 지점 세 곳의 기존 테스트(`ReservationCancelConcurrencyTest`, `ReservationCompletionSchedulerTest`, `AdminReservationControllerTest` 등)가 그대로 통과한다.

### 검증 결과

#### 2단계 Outbox 기록 (2026-10-15)

- `OutboxRecordIntegrationTest`: 취소·강제 취소·홀드 만료 3지점 모두 성공하면 PENDING 정확히 1건, 트랜잭션이 롤백되면 outbox 행이 없다. `Propagation.MANDATORY`라 트랜잭션 밖 호출은 `IllegalTransactionStateException`이다. 구현 전 6건 실패(`outbox_event` 테이블 없음)를 확인한 뒤 구현했다. 전체 705 tests(2단계) → fix-1 뒤 710 tests, 실패 0. 보고: step-2.md, step-2-review.md.

#### 3단계 relay와 Kafka 발행 (2026-10-15)

- 전체 768 tests(3단계 리뷰 반영 후), 실패 0, 건너뜀 11. relay 실패 분기·`InterruptedException`·PUBLISHED 제외 등 5개 테스트는 구현을 일부러 망가뜨리는 변이 5종에서 모두 실패함을 확인했다(step-3-review-r2.md). 기본 설정에서 relay·Kafka 빈이 없음을 `OutboxRelayDisabledTest`, 타임아웃 불변식(`send-timeout` > `delivery.timeout` > `request.timeout`)을 `KafkaTimeoutDefaultsTest`가 고정한다.
- 수동 E2E(로컬 Docker, `apache/kafka:3.8.0` KRaft 단일 노드, 프로필 `kafka`): 취소 후 토픽에 메시지 도착(key = `spaceId`), Kafka를 끈 채 취소해도 취소는 성공하고 outbox는 `PENDING`으로 남아 `attempts`가 5까지 오른 뒤 Kafka 재시작 약 40초 안에 `PUBLISHED`. 기록: step-3-kafka-manual.md.

#### 4단계 컨슈머 (2026-10-16)

- 전체 `./gradlew cleanTest test` → **814 tests, 실패 0, 오류 0, 건너뜀 11**(11건은 모두 기존 `V2SecurityRedTest`의 `@Disabled`). 알림 관련 테스트의 분류와 내용은 위 "컨슈머 결정"의 "컨슈머 테스트" 항목에 있다. 보고: step-4.md, step-4-review.md.
- **로컬 E2E (실제 Kafka + 컨슈머 켬)** — 기록: step-5.md.

  | 확인 | 결과 |
  | --- | --- |
  | 취소 → 알림 | 점유자 취소 → outbox `PUBLISHED`(`CANCELLED`) → 대기자에게 알림 1건, `last_notified_at` 기록, `GET /notifications`·`unread-count`에 대기자만 보임(점유자는 0건). V19·V20이 앱 기동 시 로컬 DB에 적용됨 |
  | 중복 전달(멱등) | 같은 payload를 토픽에 한 번 더 넣어도 알림 1건, `processed_event` 1건 그대로 |
  | 알림 후 동시 예약 | 두 사용자가 같은 구간에 동시에 `POST /reservations` → 201 `HELD` 1건, 409 `RESERVATION_SLOT_CONFLICT` 1건, 슬롯은 한 예약 몫(2개)만 남음 |
  | 홀드 만료 후 재점유 | 대기자가 있는 구간의 `HELD`를 만료시킨 뒤 다른 사용자가 곧바로 예약하면 `HOLD_EXPIRED` 이벤트가 `PUBLISHED`로 나가고 기존 홀드는 `EXPIRED`, 대기자 알림 0건 |
  | 해석 불가 payload | `not-json`, `eventId` 빈 문자열 payload → `IllegalArgumentException`, `DefaultErrorHandler` 로그 `FixedBackOff{interval=0, currentAttempts=1, maxAttempts=0} exhausted`(재시도 없이 건너뜀), 컨슈머는 계속 동작 |
  | 일시적 실패 재시도 | 알림 INSERT가 중복 키로 실패하는 이벤트(알림 행을 미리 넣어 재현) → `FixedBackOff{interval=1000, currentAttempts=3, maxAttempts=2} exhausted`(1초 간격 총 3회 시도 후 건너뜀), `processed_event` 롤백, `last_notified_at` 미기록 |

- 확인하지 못한 것: 브로커가 죽은 상태에서 컨슈머를 재기동하는 경우, 장시간 중단 뒤 리밸런싱, 파티션 2개 이상에서의 순서, 건너뛴 이벤트의 복구(DLT 없음).

## 6. 한계와 재검토 조건

- **켜진 컨텍스트 와이어링 테스트가 없다(3차):** `slotkey.kafka.enabled=true`로 기동했을 때 에러 핸들러가 리스너 컨테이너 팩토리에 붙고 `@KafkaListener`가 등록되는지는 자동 테스트가 아니라 로컬 실제 브로커 E2E(`partitions assigned` 로그, 재시도 소진 로그)로만 확인했다. 재검토 조건: 컨슈머 설정을 바꿀 때(브로커 없이 `auto-startup=false`로 와이어링을 검증하는 테스트 추가).
- **FAILED 미도입의 한계(head-of-line 막힘):** 계속 실패하는 행이 `PENDING`에 남아 매 라운드 재시도되고, relay는 id 순으로 한 번에 N건(`batch-size`, 기본 50)만 읽는다. 영구 실패 행이 N건 이상 쌓이면 그 뒤의 새 이벤트가 읽히지 않아 발행되지 못한다. 현재 페이로드가 4필드 고정이라 가능성은 낮지만 구조상 존재한다.
  - 재검토 조건: `attempts`가 임계치 이상인 `PENDING` 행이 관측될 때.
  - 후속안: FAILED 상태(또는 DLT) 도입, 또는 `attempts` 기반 백오프(실패 횟수가 많은 행의 재시도 간격을 늘리거나 배치에서 후순위로 미룸). 3차 범위.
- **단일 스케줄러 스레드 점유:** 스케줄러 풀이 기본 1개라 `ReservationCompletionScheduler`, `ChargeOrderRecoveryScheduler`와 같은 스레드를 쓴다. relay는 한 라운드에 `batch-size`건을 순차 전송하므로 브로커 장애 시 한 라운드가 최대 `batch-size × (max-block-ms + send-timeout-ms)`(기본 50 × 9초 = 450초 = 7.5분)까지 걸려 같은 스케줄러의 다른 작업(예약 완료·홀드 만료 정리, 충전 복구)을 지연시킬 수 있다. 2026-10-17부터 운영 서버에서 Kafka를 켰으므로 운영에도 해당한다(브로커가 앱과 같은 서버의 컨테이너라 서버 자체가 죽으면 앱도 함께 멈추지만, 브로커 컨테이너만 죽은 경우에 이 지연이 생긴다). 재검토 조건: 운영에서 브로커 장애 중 스케줄러 지연이 관측될 때.
  - shutdown 중 인터럽트가 걸려도 relay는 남은 배치를 계속 처리하므로, 그 건들이 전송 실패로 계산되어 `attempts`가 오를 수 있다.
- 브로커 장애 중에는 매 라운드 실패 로그가 건마다 남는다(로그 소음). 백오프가 없다.
- 인스턴스 2개 이상이면 같은 행을 둘이 발행해 중복 전송된다(PK 조건부 UPDATE는 DB 반영만 한 번으로 만든다). 다중 인스턴스는 범위 밖이다.

### 열린 질문

- (2026-10-16 당시 결정) 재알림 쓰로틀·읽음 처리·SSE를 3차로 분류했다. 이후 상한 기반 일괄 읽음과 목록 UI는 구현됐으며 [알림 UI 결정](notification-ui.md)을 따른다. 쓰로틀·SSE는 후속 범위다.
- (열림) 건너뛴 이벤트의 복구 수단(DLT)과 영구 실패 행의 head-of-line 문제는 3차에서 함께 설계한다.

## 면접·발표용 정리 (구현 후)

- **30초 설명:** 취소·강제 취소·홀드 만료로 슬롯이 풀리면 같은 트랜잭션에서 `outbox_event`에 한 줄을 남긴다. 별도 relay가 이를 Kafka로 내보내고, 컨슈머는 `processed_event`로 중복을 걸러 대기자에게 알림을 저장한다. 롤백되면 이벤트도 없고, 커밋되면 반드시 남으며, 중복 전달은 정상으로 보고 컨슈머가 한 번만 반영한다.
- **예상 질문과 답:**
  - Q. 왜 Kafka로 바로 발행하지 않고 Outbox 테이블을 쓰나요?
    A. DB 커밋과 Kafka 발행은 원자적이지 않아 한쪽만 성공할 수 있다(이중 쓰기). 이벤트를 같은 트랜잭션의 행으로 남기면 변경과 함께 롤백·커밋되고, 발행은 나중에 재시도할 수 있다.
  - Q. 이벤트가 두 번 전달되면요?
    A. 전달은 최소 1회라 중복이 정상이다. 로컬 E2E에서 전송 대기 5초 < `delivery.timeout` 8초일 때 같은 `eventId`가 토픽에 두 번 들어간 것을 직접 봤고, 설정을 정렬했지만 전송 성공 후 `markPublished` 직전 장애로도 중복은 생긴다. 컨슈머가 `processed_event`의 `UNIQUE(consumer, event_id)`와 같은 트랜잭션 안의 `INSERT IGNORE`로 한 번만 반영한다(같은 이벤트 4스레드 동시 처리에서도 알림·처리 기록 각 1건).
  - Q. 이 선택의 단점은 무엇이고 어떻게 보완했나요?
    A. 폴링 지연, 인스턴스 1개 가정, 영구 실패 행의 head-of-line 막힘, 건너뛴 이벤트의 복구 수단 없음(DLT 미도입)이다. 각 항목의 재검토 조건을 문서에 남겼다.
  - Q. 규모가 커지면 어떻게 바꾸겠습니까?
    A. 다중 인스턴스에서는 relay를 분산 락이나 CDC로 바꾸고, FAILED 상태·DLT·백오프를 도입하며, 후보 N명당 질의 약 3N+1회인 알림 대상 선정을 한 질의로 합친다.
- **숫자·근거 한 줄:** 전체 814 tests·실패 0(건너뜀 11은 기존 `@Disabled`), 로컬 Kafka E2E에서 취소 → 알림 1건 → 동시 예약 201/409, 중복 전달 시 알림 1건 유지, 재시도 소진 로그 `interval=1000 currentAttempts=3 maxAttempts=2`.
