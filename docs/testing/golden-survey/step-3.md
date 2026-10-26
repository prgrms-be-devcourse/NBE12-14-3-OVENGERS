# 골든 현황 조사 — 3단계: 빠진 Java 출력 고정과 Kotlin 비교

## 1. 범위·판정

- 역할: 구현자(implementer). 구현자 자체 실행 결과인 **자기검증**이며 독립 검증자 검토는 대기다.
- 브랜치: `dev`에서 생성한 `test/golden-survey-step3`. 기준 HEAD: `5555e47fc32383ec75087296da5c868267c57fbb`.
- 대상: reservation, credit 충전·웹훅, waitlist, outbox, notification의 요청된 누락 사례만 추가했다.
- **Java `a380133` 기준 골든 JSON 21개와 Kotlin 비교 테스트 21건(6클래스)을 추가했다.** 항목마다 Java 통과 → Kotlin 통과를 순서대로 확인했다. 기존 테스트 15건을 재사용한 웹훅 실행을 포함하면 각 언어의 항목별 성공 실행 합계는 36건이다.
- 운영 코드·기존 테스트·기존 골든·설정·의존성·마이그레이션·API 명세를 수정하지 않았다. 커밋·stage·push는 하지 않았다.
- 이번 항목의 보류: 없음. 전체 테스트 1,353건 및 테스트 소스 lint 통과(5절). 독립 검증·CI는 이번 구현자 실행과 별개다.

## 2. Java 기준 근거와 재현

### 기준 작업 트리

```text
/private/tmp/claude-501/-Users-admin-NBE12-14-3-OVENGERS/101f6dba-b7ed-4b03-b823-1b102a02dce3/scratchpad/wt-61f7cd2
```

- 커밋: `a380133082fc323ed52d933799aa040ad2107b49` (`61f7cd2^`). 대상 응답·엔티티·Outbox recorder·예약 서비스/컨트롤러는 이 커밋의 Java 구현을 실행했다.
- 2단계에서 복사해 둔 미추적 골든·테스트는 그대로 유지했다. 이번에 새 Java 테스트 6개와 보조 클래스 2개만 추가했다. 기존 파일 수정 없음.
- 새 Java 소스의 전체 차이는 [재현 패치](step-3-java-baseline.patch)에 보존했다. 운영 코드 변경이 없는 추가 전용 패치이며, 임시 빈 디렉터리에 `git apply --check` 및 `git apply` 후 원본 8파일과 바이트 일치까지 확인했다.
- 패치 SHA-256: `f23ac09de5526647403f8548863018cc64fcc08c3ae18e66560b81ecaa84343f`.
- 재현 시 `a380133` 작업 트리 루트에서 패치를 적용한 뒤, 이 브랜치의 신규 골든 21개를 같은 `backend/src/test/resources/golden/` 상대 경로로 복사하고 아래 명령을 실행한다. 기존 `FridayRemainingDtoGoldenTest`는 2단계에서 `61f7cd2`에서 복사한 그대로다.
- 패치의 `JavaSurveyGolden`은 **Java worktree에만** 있다. `GOLDEN_SURVEY_CAPTURE_DIR`를 명시한 최초 확보 실행에서 `CREATE_NEW`로 별도 디렉터리에 출력하므로 기존 출력은 덮어쓰지 못한다. Java 비교 재실행 시에는 해당 변수를 설정하지 않는다. Kotlin 테스트에는 파일 쓰기나 골든 갱신 분기가 없다.

### 최초 확보와 실패 확인

각 묶음은 먼저 정적 파일이 없는 상태에서 테스트를 실행했다. 실제 생성·직렬화한 Java JSON을 `/private/tmp/golden-survey-step3-capture/<domain>/`에 1회 저장한 뒤, 정적 기준 파일 부재 단언으로 실패했다. 실패 메시지가 `Java a380133 golden: <path> / Expecting actual not to be null`임을 XML로 확인했다. 출력의 필드·값·null·타입·시각을 검토한 뒤 골든으로 복사하고 Java → Kotlin 비교를 실행했다. 현재 테스트 실행 시 출력으로 기대값을 갱신하지 않는다.

| 확보 묶음 | 실행 / 실패 / 오류 / 스킵 | 최초 확보 시각(UTC) | 종료 코드 |
| --- | --- | --- | --- |
| 충전 | 2 / 2 / 0 / 0 | 2026-10-26T12:32:25Z | 1 |
| 웹훅(신규 10값) | 10 / 10 / 0 / 0 | 2026-10-26T12:45:03Z | 1 |
| 대기·알림 | 2 / 2 / 0 / 0 | 2026-10-26T12:46:58Z | 1 |
| Outbox | 3 / 3 / 0 / 0 | 2026-10-26T12:49:35Z | 1 |
| 예약 HTTP | 4 / 4 / 0 / 0 | 2026-10-26T12:55:21Z | 1 |

확보 명령은 5절의 해당 Java 명령 앞에 다음 환경변수를 붙인 것이다. 웹훅 최초 확보에서는 `FridayRemainingDtoGoldenTest`를 제외하고 새 `PgWebhookResultGoldenTest`만 실행했다.

```bash
GOLDEN_SURVEY_CAPTURE_DIR=/private/tmp/golden-survey-step3-capture ./gradlew cleanTest test --tests '<대상 클래스>'
```

최초 충전 실행은 Gradle wrapper 캐시 잠금 파일 접근 제한으로 테스트 실행 전에 실패했다(XML 없음). 승인된 Gradle 권한으로 재실행하여 위 실패 확인 및 비교 결과를 확보했다. 이 환경 실패는 테스트 실패 수에 넣지 않았다.

## 3. 사례별 대응과 중복 확인

아래 경로는 모두 `backend/src/test/resources/golden/` 기준이다. DTO 사례는 Spring Boot `@JsonTest`의 `ObjectMapper`로 실제 엔티티 → 응답 팩토리를 직렬화한다. JSON을 DTO로 역직렬화해 되돌리는 라운드트립 테스트가 아니다.

| 사례·입력/사전 상태 | 골든 경로 | 신규 비교 테스트 | 검증 경계·별도 HTTP 검증 |
| --- | --- | --- | --- |
| 1. PAID 충전 이력: 주문 1000원, CARD/TOSS_PAYMENTS 표시값, 생성 09:00·완료 09:01 | `credit/history-paid-response.json` | `ChargeOrderSupplementGoldenTest.paidHistoryIncludesPaymentDisplayAndUpdatedTime` | `ChargeOrderHistoryResponse.from(order)`. 기존 null 표시값 사례와 다름. HTTP 이력 조회는 기존 `ChargeOrderControllerTest` 재사용 |
| 1. FAILED 충전 주문: `markFailed` 적용 | `credit/order-failed-response.json` | `ChargeOrderSupplementGoldenTest.failedOrderKeepsFailedStatus` | `ChargeOrderResponse.from(order)`의 FAILED 직렬화. 기존 PENDING·PAID와 중복 없음 |
| 2. 웹훅 결과 ALREADY_PAID, ORDER_PENDING, ORDER_FAILED, ORDER_NOT_FOUND, ORDER_INVARIANT_VIOLATION, PG_NOT_APPROVED, PG_MISMATCH, PG_LOOKUP_ERROR, STATE_CONFLICT, COMPLETE_ERROR | `credit/webhook-<결과 소문자·하이픈>-result.json` 10개 | `PgWebhookResultGoldenTest.missingResultKeepsJavaEnumString` | 기존 `FridayRemainingDtoGoldenTest[webhook-result]`의 CONFIRMED를 재사용하여 총 11값. 새 enum이 생기면 대응 정적 파일 부재로 실패. HTTP 수신 동작·정합성은 기존 웹훅 테스트 범위 |
| 3. 대기 취소: WAITING 엔티티 `cancel()`, ID=3, spaceId=20, position=0 | `waitlist/entry-cancelled-response.json` | `WaitlistCancelledGoldenTest.responseKeepsJavaFactoryOutput` | `WaitlistEntryResponse.of`. 기존 WAITING과 중복 없음. 취소 API는 기존 204 계약이며 이 파일을 취소 API 본문으로 주장하지 않음 |
| 3. 읽은 알림: ID=5, waitlistEntryId=3, readAt=09:01 | `notification/read-true-response.json` | `NotificationReadGoldenTest.responseKeepsJavaFactoryOutput` | `NotificationResponse.from`. readAt 입력이 read=true로 바뀌는 팩토리 경로. 기존 read=false와 중복 없음 |
| 4. 슬롯 해제 기록: reservationId=10, spaceId=20, CANCELLED | `outbox/slot-released-cancelled-payload.json` | `OutboxPayloadGoldenTest.recordedPayloadKeepsJavaFields[CANCELLED]` | 실제 트랜잭션·MySQL `SlotReleasedRecorder.record`, 기존 `assertEvent`와 같은 저장 형식 |
| 4. 위와 같은 입력, FORCE_CANCELLED | `outbox/slot-released-force-cancelled-payload.json` | `…[FORCE_CANCELLED]` | 실제 저장된 payload 4필드와 행 메타데이터 |
| 4. 위와 같은 입력, HOLD_EXPIRED | `outbox/slot-released-hold-expired-payload.json` | `…[HOLD_EXPIRED]` | 실제 저장된 payload 4필드와 행 메타데이터 |
| 5. HELD 예약, 30분당 5000원·2슬롯, 잔액 30000원, 미만료 HOLD | `reservation/pay-success.json` | `ReservationHttpSupplementGoldenTest[pay -> 200]` | 실제 POST `/api/v1/reservations/{id}/pay`, 200·전체 본문. 동일 키 재요청 본문 동일, 차감·원장 각 1회 |
| 5. CONFIRMED 예약 10:00~11:00 → 11:30, 잔액 30000원 | `reservation/extend-success.json` | `…[extend -> 200]` | 실제 POST extend, 200·전체 본문, totalAmount=15000, 추가 차감 5000원·슬롯 3개 |
| 5. 다른 회원의 CONFIRMED 예약을 관리자 세션으로 강제 취소 | `reservation/force-cancel-success.json` | `…[force-cancel -> 200]` | 실제 관리자 POST force-cancel, 200·AdminReservationResponse 전체 본문, CANCELLED·슬롯 0·전액 환불 1건 |
| 5. HELD 예약의 hold_expires_at=고정 현재 시각-1분 | `reservation/expired-pay-conflict.json` | `…[expired-pay -> 409]` | 실제 POST pay, 409·오류 본문, HELD·잔액·슬롯 유지, 원장·멱등키 미생성 |

예약의 기존 `confirmed-response.json`·`cancelled-response.json`은 DTO 상태 직렬화 기준이다. 새 4파일은 실제 HTTP 경로·정책에 따라 생성한 envelope와 data/오류 본문이며, `/pay`의 `holdExpiresAt` 잔존, 연장된 시간·금액, 관리자용 응답 필드, 실패 메시지까지 보호하므로 기존 DTO 골든을 복제하지 않는다.

## 4. 변동 필드·비교 방식

- 공통 시계: `2026-10-23T00:00:00Z`, `Asia/Seoul` → `2026-10-23T09:00:00`. 예약 이용 시각은 이틀 뒤 10:00~11:00. 시스템 시각이나 테스트 실행 날짜에 의존하지 않는다.
- 충전 생성·갱신 시각, 대기 생성·이용 시각, 알림 생성·읽음 시각은 고정 입력이다. 대기·알림의 영속화 ID와 알림 readAt은 기존 `ReflectionTestUtils`로 사전 상태만 설정한다. 실제 DTO `from/of`는 운영 코드다.
- 예약 HTTP의 DB 자동 증가 ID(`data.reservationId`, `data.spaceId`, 관리자 응답의 `data.memberId`)는 Java 캡처 숫자 자체와 비교하지 않는다. 필드 존재·정수 타입을 검증하고 현재 테스트가 생성한 예약·공간·소유 회원 ID와 각각 일치시킨다. 이메일과 공간 이름은 입력을 고정했다.
- Outbox의 `eventId`는 recorder 내부 난수 UUID이므로 Java 캡처의 UUID 문자열과 동등 비교하지 않는다. **필드 존재·문자열 타입·표준 UUID 표현·v4·variant 2·DB `event_id`와의 일치**를 검증한다. `reservationId`, `reason`, `occurredAt`은 정적 JSON 값과 직접 비교한다. `event_type=SlotReleased`, `topic=slot-released`, `partition_key=20`, `status=PENDING`, `attempts=0`, `published_at=null`도 행에서 검증한다. 테스트 트랜잭션은 롤백한다.
- 객체 키 순서·공백을 제외한 JSON 구조·값을 비교한다. 응답·payload의 필드 집합을 비교해 누락·추가 필드도 잡는다. null 제거, 배열 정렬, 숫자 문자열화, 전체 JSON의 정규화나 재작성 없음. DTO 역직렬화로 기대값을 만들지 않는다.
- **신규 JSON 21개 모두 Java 캡처 원본 = Java worktree 골든 = Kotlin 골든의 바이트 일치 확인.** 동적 필드는 위 단언으로만 다루고 파일 내용을 편집해 통과시키지 않았다.

## 5. 실행 명령과 결과

환경: JDK 21.0.11, 저장소 Gradle wrapper(8.10.2), Docker/Testcontainers MySQL 8.4. Java 명령은 위 worktree의 `backend/`, Kotlin 명령은 현재 저장소의 `backend/`에서 실행했다. 아래 시각은 XML `timestamp`(UTC, 한국 시각은 +9시간)이며 수치는 **실행 / 실패 / 오류 / 스킵**이다.

| 항목 | 코드 | XML 시작 시각(UTC) | 명령 | 종료 코드 | 실행 / 실패 / 오류 / 스킵 |
| --- | --- | --- | --- | --- | --- |
| 충전 | Java | `2026-10-26T12:42:37Z` | `./gradlew cleanTest test --tests '*ChargeOrderSupplementGoldenTest'` | 0 | 2 / 0 / 0 / 0 |
| 충전 | Kotlin | `2026-10-26T12:43:18Z` | `./gradlew cleanTest test --tests '*ChargeOrderSupplementGoldenTest'` | 0 | 2 / 0 / 0 / 0 |
| 웹훅 | Java | `2026-10-26T12:45:34Z` | `./gradlew cleanTest test --tests '*PgWebhookResultGoldenTest' --tests '*FridayRemainingDtoGoldenTest'` | 0 | 25 / 0 / 0 / 0 |
| 웹훅 | Kotlin | `2026-10-26T12:46:18Z` | `./gradlew cleanTest test --tests '*PgWebhookResultGoldenTest' --tests '*FridayRemainingDtoGoldenTest'` | 0 | 25 / 0 / 0 / 0 |
| 대기·알림 | Java | `2026-10-26T12:48:35Z` | `./gradlew cleanTest test --tests '*WaitlistCancelledGoldenTest' --tests '*NotificationReadGoldenTest'` | 0 | 2 / 0 / 0 / 0 |
| 대기·알림 | Kotlin | `2026-10-26T12:49:06Z` | `./gradlew cleanTest test --tests '*WaitlistCancelledGoldenTest' --tests '*NotificationReadGoldenTest'` | 0 | 2 / 0 / 0 / 0 |
| Outbox | Java | `2026-10-26T12:54:13Z` | `./gradlew cleanTest test --tests '*OutboxPayloadGoldenTest'` | 0 | 3 / 0 / 0 / 0 |
| Outbox | Kotlin | `2026-10-26T12:55:02Z` | `./gradlew cleanTest test --tests '*OutboxPayloadGoldenTest'` | 0 | 3 / 0 / 0 / 0 |
| 예약 HTTP | Java | `2026-10-26T12:56:02Z` | `./gradlew cleanTest test --tests '*ReservationHttpSupplementGoldenTest'` | 0 | 4 / 0 / 0 / 0 |
| 예약 HTTP | Kotlin | `2026-10-26T12:56:28Z` | `./gradlew cleanTest test --tests '*ReservationHttpSupplementGoldenTest'` | 0 | 4 / 0 / 0 / 0 |

- 위 모든 비교 실행은 `cleanTest`를 포함했다. 각 묶음의 Java 성공 XML을 보존한 뒤 Kotlin을 실행했다.
- 웹훅의 25건은 신규 10건 + 기존 `FridayRemainingDtoGoldenTest` 15건이다. 신규 21건에 기존 15건을 중복 합산하지 않는다.
- XML 원본 사본: `/private/tmp/golden-step3-evidence/<java|kotlin>-<charge|webhook|wait-notification|outbox|reservation>/TEST-*.xml`. 최초 실패 XML은 같은 이름의 `-red` 디렉터리다. `cleanTest`로 앞선 XML이 지워지기 전에 복사했다.
- 각 명령의 로그: `/private/tmp/golden-step3-<java|kotlin>-<항목>.log`와 최초 확보의 `-red.log`. 대기·알림의 항목명은 `wait-notification`이다.
- `./gradlew ktlintTestSourceSetCheck`: 최초 실행에서 **새 파일 3개**의 포맷 오류 22개 발견(function-signature 13, chain-method-continuation 8, import-ordering 1). 새 파일만 수정 후 재실행 **종료 0**. 기존 파일 자동 포맷은 실행하지 않았다.
- 전체 회귀 검사: `./gradlew cleanTest test` **종료 0, 1,353 / 0 / 0 / 0**, XML 183개. `BUILD SUCCESSFUL in 9m 34s`. XML 시작 범위 `2026-10-26T12:56:58Z`~`13:06:16Z`(한국 시각 21:56:58~22:06:16). 새 6클래스 21건이 모두 포함됐음을 클래스별 수로 재확인했다. XML 사본은 `/private/tmp/golden-step3-evidence/kotlin-full/`, 로그는 `/private/tmp/golden-step3-kotlin-full.log`.
- 전체 `clean build`·프론트 검사는 미실행. 이번 작업은 응답/메시지 기준과 새 테스트 추가이며 운영 전환·의존성·프론트 변경은 없고, 위 targeted 실행 및 전체 테스트·테스트 소스 lint를 별도 확인했다. 전환 PR의 CI 최종 검증을 대체하지 않는다.

## 6. 변경 파일

새 파일 **31개**: Kotlin 테스트/보조 클래스 8개, 정적 JSON 21개, 보고서와 Java 재현 패치 2개.

```text
backend/src/test/kotlin/com/ovengers/slotkey/credit/dto/ChargeOrderSupplementGoldenTest.kt
backend/src/test/kotlin/com/ovengers/slotkey/credit/webhook/PgWebhookResultGoldenTest.kt
backend/src/test/kotlin/com/ovengers/slotkey/notification/NotificationReadGoldenTest.kt
backend/src/test/kotlin/com/ovengers/slotkey/outbox/OutboxPayloadGoldenTest.kt
backend/src/test/kotlin/com/ovengers/slotkey/reservation/controller/ReservationHttpSupplementGoldenTest.kt
backend/src/test/kotlin/com/ovengers/slotkey/support/SurveyGoldenAssertions.kt
backend/src/test/kotlin/com/ovengers/slotkey/support/SurveyGoldenClockConfig.kt
backend/src/test/kotlin/com/ovengers/slotkey/waitlist/WaitlistCancelledGoldenTest.kt
backend/src/test/resources/golden/credit/history-paid-response.json
backend/src/test/resources/golden/credit/order-failed-response.json
backend/src/test/resources/golden/credit/webhook-already-paid-result.json
backend/src/test/resources/golden/credit/webhook-complete-error-result.json
backend/src/test/resources/golden/credit/webhook-order-failed-result.json
backend/src/test/resources/golden/credit/webhook-order-invariant-violation-result.json
backend/src/test/resources/golden/credit/webhook-order-not-found-result.json
backend/src/test/resources/golden/credit/webhook-order-pending-result.json
backend/src/test/resources/golden/credit/webhook-pg-lookup-error-result.json
backend/src/test/resources/golden/credit/webhook-pg-mismatch-result.json
backend/src/test/resources/golden/credit/webhook-pg-not-approved-result.json
backend/src/test/resources/golden/credit/webhook-state-conflict-result.json
backend/src/test/resources/golden/notification/read-true-response.json
backend/src/test/resources/golden/outbox/slot-released-cancelled-payload.json
backend/src/test/resources/golden/outbox/slot-released-force-cancelled-payload.json
backend/src/test/resources/golden/outbox/slot-released-hold-expired-payload.json
backend/src/test/resources/golden/reservation/expired-pay-conflict.json
backend/src/test/resources/golden/reservation/extend-success.json
backend/src/test/resources/golden/reservation/force-cancel-success.json
backend/src/test/resources/golden/reservation/pay-success.json
backend/src/test/resources/golden/waitlist/entry-cancelled-response.json
docs/testing/golden-survey/step-3-java-baseline.patch
docs/testing/golden-survey/step-3.md
```

보고서와 재현 패치는 처음 `docs/lab/notes/golden-survey/`에 저장됐으나, `docs/lab/`은 개인 문서 폴더라 로컬 제외 대상이므로 검증 후 `docs/testing/golden-survey/`로 옮겼다(2026-10-26, 사용자 결정). 위 경로 표기는 이동 후 기준이다.

## 7. 가정·미확인·발견 사항·보류

- **보류 항목 없음.** 이번 요청에 설계에 없는 결정을 추가하거나 Java 기준을 재현하지 못한 항목은 없다. 사용자 결정이 필요한 질문도 없다.
- FAILED 응답은 `ChargeOrderResponse.from`을 대상으로 선택했다. 값이 채워진 이력은 별도 `ChargeOrderHistoryResponse.from`으로 검증했다. PG 승인/복구/웹훅 처리 로직을 새 골든 테스트에서 재구현하지 않았다.
- 대기 CANCELLED의 position=0은 DTO 팩토리의 고정 입력이다. 대기 취소 API의 본문이나 CANCELLED 항목의 목록 노출 정책을 새로 정의하지 않았다.
- **기존 1단계 보고서의 알림 `readAt` 포함 표현과 실제 계약이 다름:** `NotificationResponse`에는 readAt 필드가 없다. 엔티티 readAt으로 read=true를 생성하며 JSON은 id/waitlistEntryId/message/read/createdAt 5필드다. 기존 문서·운영 코드는 고치지 않았다.
- Outbox 테스트는 실제 recorder·트랜잭션·MySQL JSON 컬럼을 검증한다. Kafka relay/브로커 전달·컨슈머 처리까지의 새 end-to-end 골든은 범위 밖이다. 기존 관련 테스트 결과는 전체 회귀 검사와 구분해 본다.
- 예약 HTTP는 실제 인증 세션/JWT·컨트롤러·서비스·DB로 실행했다. HTTP의 동적 DB ID만 필드별로 검증한다. 실제 HTTP 서버 소켓은 띄우지 않고 Spring MockMvc를 사용한다.
- 외부 PG 호출은 새 테스트에 없다. 결제 표시값은 `recordPaymentMethod`의 로컬 입력이며 실제 Toss 응답/공식 필드의 근거라고 주장하지 않는다. PG 기능 검증은 기존 Fake/테스트 대역 범위다. 새 외부 API 엔드포인트·필드·헤더·에러 코드를 설계하지 않아 공식 MCP 조회를 적용할 항목이 없다.
- 이번 결과는 구현자 자기검증이다. 독립 검증자는 전체 테스트를 직접 실행하고 아래 체크리스트 및 Java 재현 패치를 검토해야 한다. CI·사람 확인 전 merge/배포 완료를 주장하지 않는다.

## 8. 관련 decision 문서

새 기능·기술·의존성을 도입하지 않았으므로 새 decision을 만들거나 기존 decision을 수정하지 않았다. 이번 실행 근거는 본 보고서에 기록했다.

- [Kotlin 테스트 보호 장치](../../decisions/kotlin-test-guardrails.md): Java 골든 먼저 확보, 동적 필드 통제, 동일 기준 비교.
- [PG 크레딧 충전](../../decisions/pg-charge.md): 충전 이력·FAILED·웹훅 처리 결과의 배경.
- [대기열·알림](../../decisions/waitlist.md): CANCELLED와 읽음 알림의 기존 정책.
- [Outbox + Kafka](../../decisions/outbox-kafka.md): SlotReleased payload 4필드와 레코드 메타데이터.
- [핵심 도메인 결정](../../core-domain-decisions.md): HOLD → 결제, 만료 409·롤백, 연장 단가·슬롯, 관리자 강제 취소의 기준.
- [Java → Kotlin 골든 가이드](../java-kotlin-golden-guide.md): 이번 확보·비교·기록 절차.

## 9. 리뷰 체크리스트

- [ ] Java 기준 커밋이 `a380133082fc323ed52d933799aa040ad2107b49`이고 패치가 테스트 파일 추가만 포함하는가.
- [ ] 기존 코드·테스트·골든 수정이 없고 현재 작업의 31파일만 추가했는가(로컬 제외된 문서 포함).
- [ ] 충전 이력 PAID 표시값·시각, FAILED 주문 응답이 실제 Java 팩토리 출력과 같은가.
- [ ] 웹훅 CONFIRMED 1값은 기존 테스트로 재사용하고 나머지 10값만 새 JSON에 추가했는가.
- [ ] 대기 CANCELLED·position=0은 DTO 검증이며 기존 취소 HTTP 204 계약을 바꾸지 않는가.
- [ ] 읽은 알림은 엔티티 readAt → read=true 변환을 검증하고 readAt JSON 필드를 추가하지 않는가.
- [ ] Outbox UUID를 제거/치환하지 않고 타입·표준 형식·v4·variant 2·행 일치로 검증하는가.
- [ ] Outbox 나머지 3필드와 행 메타데이터가 기존 `OutboxRecordIntegrationTest.assertEvent` 형식과 같은가.
- [ ] 예약 HTTP 본문의 동적 ID를 정수·현재 fixture의 ID로 검증하고 다른 필드·null은 골든 그대로 비교하는가.
- [ ] 결제 성공·동일 키 재요청에서 차감/원장 1회이고 연장 금액은 30분당 스냅샷 단가로 계산되는가.
- [ ] 강제 취소가 관리자 전용 경로에서 소유자와 다른 관리자 세션으로 수행되며 전액 환불·슬롯 반환하는가.
- [ ] 만료 후 pay 409 본문에 정확한 코드·메시지가 있고 HELD·잔액·슬롯 유지 및 원장/멱등키 롤백을 확인하는가.
- [ ] 골든 JSON 21개가 Java 확보 출력과 바이트 일치하며 Kotlin 테스트에 자동 재생성 코드가 없는가.
- [ ] 각 항목의 cleanTest·테스트 수·실패/오류/스킵 수와 Java → Kotlin 순서를 독립적으로 확인했는가.
- [ ] 전체 회귀 검사 결과와 구현자 자기검증/독립 검증 대기를 구분했는가.
- [ ] 운영 코드·JWT 정책·상태 변경 도구 연결·JPA 엔티티·Lombok·시크릿·배포 job·DB 스키마 변경이 없는가.
- [ ] 커밋·stage·push 없이 인계 파일과 대상 목록만 남겼는가.
