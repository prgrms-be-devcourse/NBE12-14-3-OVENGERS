# 02. 금전·예약 동시성 검증

**목적:** 언어 전환 전후에 금액·예약·이벤트가 같은 방식으로 확정되도록 실 MySQL 회귀 검증을 갖춘다. [상위 계획](00-kotlin-migration-plan.md)의 두 번째 P0 작업이다. 현재 근거는 2026-10-20 / `d1a15ec`의 소스와 2026-10-20 기준선 실행(1,048/1,048 통과)이며, 아래 금전·경합 보강은 아직 완료되지 않았다.

## 우선 확인할 코드

- [`CreditBalanceRepository`](../../../backend/src/main/java/com/ovengers/slotkey/credit/repository/CreditBalanceRepository.java): `balance >= amount` 조건부 차감, 증가 쿼리.
- [`CreditServiceImpl`](../../../backend/src/main/java/com/ovengers/slotkey/credit/service/CreditServiceImpl.java): 차감·환불·위약금과 거래 원장 저장.
- [`ReservationRepository`](../../../backend/src/main/java/com/ovengers/slotkey/reservation/repository/ReservationRepository.java), [`ReservationSlotRepository`](../../../backend/src/main/java/com/ovengers/slotkey/reservation/repository/ReservationSlotRepository.java): 상태 조건부 UPDATE, 슬롯 조회·삭제.
- [`ReservationCancelService`](../../../backend/src/main/java/com/ovengers/slotkey/reservation/service/ReservationCancelService.java): CONFIRMED 취소는 환불·위약금, HELD 취소는 금전 변경 없이 슬롯을 반환하며 양쪽 모두 Outbox를 같은 트랜잭션에 기록한다.
- [`ChargeOrderConfirmService`](../../../backend/src/main/java/com/ovengers/slotkey/credit/service/ChargeOrderConfirmService.java), [`ChargeOrderConfirmTransactionService`](../../../backend/src/main/java/com/ovengers/slotkey/credit/service/ChargeOrderConfirmTransactionService.java): DB 준비 → 트랜잭션 밖 PG 호출 → DB 확정의 분리된 빈 경계다. [`ChargeOrderRecoveryProcessor`](../../../backend/src/main/java/com/ovengers/slotkey/credit/scheduler/ChargeOrderRecoveryProcessor.java)와 [`PgWebhookEventProcessor`](../../../backend/src/main/java/com/ovengers/slotkey/credit/webhook/PgWebhookEventProcessor.java)도 같은 확정 경로를 사용한다.
- [`SlotReleasedRecorder`](../../../backend/src/main/java/com/ovengers/slotkey/outbox/SlotReleasedRecorder.java), [`OutboxRelay`](../../../backend/src/main/java/com/ovengers/slotkey/outbox/OutboxRelay.java), [`SlotReleasedHandler`](../../../backend/src/main/java/com/ovengers/slotkey/notification/consumer/SlotReleasedHandler.java): Outbox 저장/전송/알림 소비의 서로 다른 트랜잭션 경계다.
- [`JdbcLoginAttemptStore`](../../../backend/src/main/java/com/ovengers/slotkey/auth/ratelimit/JdbcLoginAttemptStore.java): `JdbcTemplate`으로 로그인 시도 버킷을 갱신하며 `Propagation.REQUIRES_NEW`, `ON DUPLICATE KEY UPDATE`, `SELECT ... FOR UPDATE`를 사용한다. 로그인 실패/차단 카운트가 본 인증 트랜잭션 롤백과 함께 사라지면 안 된다.
- [`InactiveMemberAccessLogService`](../../../backend/src/main/java/com/ovengers/slotkey/access/service/InactiveMemberAccessLogService.java): 비활성 회원 출입 거절 로그를 `Propagation.REQUIRES_NEW`로 기록한다. 출입 거절 응답과 로그 저장이 서로의 롤백에 영향을 주지 않아야 한다.
- 기존 테스트: [`ReservationPaymentConcurrencyTest`](../../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationPaymentConcurrencyTest.kt), [`ReservationCancelConcurrencyTest`](../../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationCancelConcurrencyTest.kt), [`CreditBalanceConcurrencyIntegrationTest`](../../../backend/src/test/kotlin/com/ovengers/slotkey/credit/CreditBalanceConcurrencyIntegrationTest.kt), [`InactiveMemberAccessIntegrationTest`](../../../backend/src/test/java/com/ovengers/slotkey/access/service/InactiveMemberAccessIntegrationTest.java). 이미 검증되는 시나리오는 중복 작성하지 않는다.

## 인증·출입 로그의 독립 트랜잭션 경계

금전 흐름은 아니지만 같은 커밋에 추가된 `REQUIRES_NEW` 경계다. Kotlin 전환 시 `@Transactional` 위치·전파·프록시 호출 경로가 바뀌면 카운트·로그가 유실되거나 본 트랜잭션을 오염시킬 수 있으므로 함께 고정한다.

| 경계 | 보존할 불변식 | 확인할 테스트·방법 |
| --- | --- | --- |
| 로그인 시도 | 시도 버킷 증가·잠금 조회는 `REQUIRES_NEW`다. 인증 실패가 본 트랜잭션을 롤백해도 시도 횟수는 남고, 10회/15분 초과 시 429 `Retry-After`가 유지된다. | `LoginRateLimitIntegrationTest`, `AuthControllerTest`, `JdbcLoginAttemptStore` 프록시 경유 호출. |
| 비활성 출입 로그 | 출입 거절과 무관하게 로그는 별도 트랜잭션으로 커밋된다. 로그 저장 실패가 출입 거절 응답을 성공으로 바꾸지 않는다. | `InactiveMemberAccessIntegrationTest`, `InactiveMemberAccessFilterTest`, 실패 주입 시 응답·DB 확인. |
| 프록시 경계 | 같은 클래스 내부 self-invocation으로 `REQUIRES_NEW`가 무시되지 않는다. Kotlin `final`/`open`·`@Transactional` 위치를 실제 Spring 빈으로 검증한다. | 통합 테스트에서 커밋/롤백 후 별도 커넥션으로 재조회. |

## 예약·원장 검증 순서

[01의 대응표](01-test-baseline.md)를 먼저 적용한다. `ReservationCancelConcurrencyTest`에는 전액 환불 행 수/합계와 위약금 부재 DB 검증이 있고, `ChargeOrderConfirmIntegrationTest`에는 원장 SUM과 회원 잔액 일치 검증이 있다. 이 사실을 모든 환불·위약금/원장 조합의 검증 완료로 확대하지 않는다.

1. **동시 차감.** 서로 다른 예약 두 건이 동일 회원 잔액을 동시에 차감하고 총 요청액이 잔액을 초과하도록 준비한다. 각 스레드는 별도 트랜잭션·커넥션에서 서비스 경로를 호출하고 `CountDownLatch`로 시작을 맞춘다. 성공/실패 개수, 최종 잔액의 음수 방지, 성공 예약에 대응하는 거래만 생성됐는지를 DB에서 확인한다.
2. **원장 정합성.** 가입/관리자 지급, TOP_UP, 예약 차감, 전액/위약금 환불, 탈퇴 시 WITHDRAWAL_EXPIRATION을 시나리오별로 확인한다. 각 행의 `amount/type/balanceAfter`와 최종 잔액을 비교하고 시작 잔액 또는 초기 지급 행을 합계에 포함한다. 기존 PG 원장 검증을 재사용하고 복합 예약/환불 흐름의 누락만 보강한다.
3. **취소 경계.** `Clock`을 고정해 시작 1시간 전, 그 직후, 시작 정각을 검증한다. 전액 환불이면 `REFUND` 한 건, 위약금 구간이면 `REFUND`와 `PENALTY` 각 한 건 및 실제 순환급액을 확인한다. 거절된 취소는 상태·슬롯·잔액·원장에 변화가 없어야 한다.
4. **예약 상태 전이.** HELD 취소는 CANCELLED·슬롯 삭제·이력/Outbox 저장과 환불/위약금/토큰 처리 부재를 확인한다. 결제/취소 경합, 만료 정각 결제, 일반 재취소, 체크인·노쇼·완료 후 취소/연장의 성공/거절과 DB 상태를 매핑한다. 조건부 UPDATE 0의 오류·부작용 부재도 확인한다.
5. **동시성 재현성.** 각 스레드는 별도 트랜잭션·커넥션을 사용한다. 동시 시작만으로 잠금 대기를 입증하지 말고 필요한 잠금 지점의 진입/대기를 관찰해 순서를 통제한다. await 성공 여부와 Future의 결과/예외를 검사하고 모든 대기에 제한 시간을 둔다. 부모 테스트의 트랜잭션 공유나 고정 sleep에 의존하지 않고 최종 DB 상태를 새로 조회한다.

## PG 충전·복구·웹훅의 보존 계약

| 경계 | 전환 전후 확인할 불변식 | 우선 재실행할 테스트 |
| --- | --- | --- |
| 주문 준비/PG 호출 | PENDING → CONFIRMING 준비와 PG 호출을 분리한다. 소유권·금액·회원 상태·멱등 키를 확인하고 PG 호출 중 DB 트랜잭션/잠금을 유지하지 않는다. | `ChargeOrderConfirmIntegrationTest`, `ChargeOrderConfirmServiceTest` |
| DB 확정 | 주문 → 회원 잠금 아래 잔액 증가·TOP_UP 1행·PAID·멱등 응답이 함께 커밋된다. 같은 주문의 재요청/동시 요청에서 중복 증가하지 않는다. 주문 long 금액의 `Math.toIntExact`와 int 잔액 상한 검사를 유지한다. | `ChargeOrderConfirmIntegrationTest`, `ChargeOrderRepositoryTest` |
| 불명확 승인/DB 실패 | 확정 DB 실패나 PG 결과 불명확을 임의로 PAID/FAILED로 바꾸지 않는다. CONFIRMING과 저장된 키를 유지하고 복구 시 조회·재시도/수동 확인 정책을 보존한다. | `ChargeOrderRecoveryProcessorIntegrationTest`, `ChargeOrderRecoverySchedulerTest` |
| 웹훅 수신/처리 | 수신은 transmission_id 중복을 막아 기록하고 끝낸다. 본문의 승인 상태/paymentKey를 확정 근거로 쓰지 않는다. 저장된 키로 PG 조회 후 주문번호·금액·통화 등을 대조한다. | `TossWebhookIntegrationTest`, `PgWebhookEventProcessorIntegrationTest` |
| confirm/복구/웹훅 경합 | 확정 공통 경로의 상태 검사·주문 잠금·`credit_transaction.charge_order_id` UNIQUE로 충전을 1회만 반영한다. 수신 중복과 주문 중복 확정은 별개로 검증한다. | `PgWebhookEventProcessorIntegrationTest`, `ChargeOrderConfirmIntegrationTest` |

위 표의 테스트 존재는 실제 실행 통과를 뜻하지 않는다. 현재 커버하는 경합 조합을 먼저 확인하고, 전환으로 영향을 받는 누락 조합만 추가한다. 결제수단 표시 저장은 충전 확정과 별도 트랜잭션이므로 두 처리를 하나의 필수 성공 조건으로 합치지 않는다.

## 취소·Outbox·대기 알림의 보존 계약

- **잠금 순서:** CONFIRMED 일반 취소는 Reservation UPDATE → Member X lock → Slot DELETE → 회원 환불 UPDATE다. HELD 취소는 Reservation UPDATE → Slot DELETE다. 연장/HOLD/만료 정리와의 FK·조건부 UPDATE까지 대조하고 `ReservationCancelConcurrencyIntegrationTest` 등 기존 경합 테스트를 재실행한다.
- **Outbox 원자성:** `SlotReleasedRecorder.record`는 MANDATORY다. 일반/강제 취소와 만료 정리의 상태·슬롯·원장·Outbox가 함께 커밋/롤백되어야 한다. `OutboxRecordIntegrationTest`의 실패 주입·HELD 취소 검증을 재사용한다.
- **발행 분리:** `OutboxRelay`는 트랜잭션 밖에서 전송하고 상태 갱신은 건별로 처리한다. 전송 성공 후 DB 표시 실패에 따른 중복 전달을 허용하는 최소 1회 전달 계약을 유지한다. `OutboxRelayIntegrationTest`·`OutboxRelayTest`를 재실행한다.
- **소비 원자성:** processed_event의 `(consumer,event_id)` 선점·알림 저장·대기 알림 시각 갱신은 한 트랜잭션이다. 알림 저장 실패 시 중복 처리 기록도 롤백되어 재전달을 처리할 수 있어야 한다. `SlotReleasedHandlerTest`·`SlotReleasedHandlerConcurrencyTest`를 재사용한다.
- **현재 상태 재검사:** 해제 이벤트가 있어도 현재 슬롯 재점유, 본인 점유 예약, 비활성 회원 등은 다시 판정한다. 알림은 예약을 보장하지 않는다. `WaitlistApplyConcurrencyTest`·`WaitlistEntryLostUpdateTest`와 [03의 payload 계약](03-api-contract.md)을 함께 확인한다. 실제 브로커 검증과 Fake publisher/직접 handler 호출은 구분한다.

## 수정 판단과 완료 조건

현행 Java 코드에서 실패하면 Kotlin 변환과 분리해 벌크 UPDATE 뒤 영속성 컨텍스트 재조회, 환불·위약금의 트랜잭션 참여, 락 획득 순서, 영향 행 처리를 확인한다. 쿼리 수정은 관련 통합 테스트로 검증하고, 실제 스키마 변경이 필요한 경우에만 별도 결정과 새 Flyway 버전을 추가한다. 언어 전환 PR에서 금전 규칙과 잠금 순서를 동시에 바꾸지 않는다.

- 신규 테스트는 `MySqlTestContainerConfig`의 MySQL 8.4에서 통과한다. H2 결과로 대체하지 않는다.
- 대상 테스트는 위 표와 [01](01-test-baseline.md)에서 골라 먼저 실행하고, 최종적으로 **필터 없는 `cd backend && ./gradlew clean build`**를 실행한다. 예약/credit 패키지만 실행한 결과로 Outbox·notification·waitlist까지 통과했다고 표현하지 않는다.
- 성공 건수, 잔액·원장·주문·예약·슬롯·이벤트·알림과 시간 경계를 실제 DB에서 확인하고 명령/종료 코드/스킵을 `docs/testing/test-results.md`에 새 날짜로 기록한다.

관련 결정: [예약 동시성](../../decisions/reservation-concurrency.md), [예약 멱등성](../../decisions/reservation-idempotency.md), [PG 충전](../../decisions/pg-charge.md), [Outbox/Kafka](../../decisions/outbox-kafka.md).
