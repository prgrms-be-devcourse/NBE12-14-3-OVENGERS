# 테스트 전략

> **원칙**: 문서에 명시된 비즈니스 규칙과 보안 정책은 전부 대응하는 테스트 코드로 검증되어야 한다. (**규칙 하나 = 테스트 하나**)
>
> 본 문서는 [`docs/core-domain-decisions.md`](../core-domain-decisions.md) 및 세부 결정 문서들의 정책이 백엔드/프론트엔드 어디에서 어떻게 검증되고 있는지, 그리고 현재 구현되지 않은 미검증 영역이 무엇인지 실행 가능한 기준으로 매핑한 테스트 전략 문서다.
>
> **2026-10-20 기준선 (`d1a15ec`)**: 기능 동결 커밋에서 전체 `clean build`를 실행해 **135개 스위트·1,048개 테스트 전원 통과**(실패 0·오류 0·스킵 0, `@Disabled` 0)를 확인했다. 아래 표의 과거 통과 수·줄 번호·gap은 전체 재검증 결과가 아니며, 새 기준선 수치는 [날짜별 결과](test-results.md) §0-0에 있다. Kotlin 전환 준비 대응표는 [마이그레이션 테스트 기준선](../plans/kotlin-migration/01-test-baseline.md)을 따른다.

---

## 검증 도구 및 환경

- **Testcontainers (MySQL 8.4)**: 실제 MySQL 컨테이너를 구동하여 테이블 제약(UNIQUE), 트랜잭션 격리 수준, 비관적 락(`SELECT ... FOR UPDATE`), 조건부 UPDATE의 원자성을 실측 검증한다. (H2 등 인메모리 DB는 락/방언 동작 차이로 인해 배제)
- **`Clock` 주입 (`FixedClockConfig`)**: 시간 경계 조건 검증 시 `Thread.sleep` 없이 가상 시간을 주입하여 `HELD` 10분 만료, 체크인 시작 15분 전/후 경계, 노쇼 판정 등을 밀리초 단위로 정확하게 검증한다.
- **다중 스레드/커넥션 (`CountDownLatch`, `ExecutorService`)**: 동시성 테스트에서 복수의 스레드가 동시에 같은 리소스에 경합을 유도하여 데이터 정합성을 검증한다.
- **MockMvc / Spring Security Test**: 관리자 권한(`ROLE_ADMIN`), 인증 토큰 누락, 위변조, 정지 회원 토큰 등의 인가 흐름을 슬라이스/통합 테스트한다.
- **PIT 뮤테이션 테스트 (`./gradlew pitest`, 수동 실행)**: 운영 코드를 일부러 망가뜨려 보고 테스트가 잡는지 확인해 "통과하지만 믿을 수 없는 테스트"를 찾는다. 핵심 도메인 로직에 한정하며 CI에는 넣지 않았다. 결정과 결과는 [뮤테이션 테스트 결정](../decisions/mutation-testing.md)을 본다.
- **Node.js 내장 Test Runner (`node --test`)**: 프론트엔드의 정적 렌더링, 시간대·검색 필터·API 페이로드 등을 검증한다. 과거 14개 통과 수를 현재 전체 테스트 수로 사용하지 않고 새 실행에서 집계한다.

---

## 1. 동시성 (Concurrency)

| # | 시나리오 | 기대 결과 | 검증 소스 매핑 |
| --- | --- | --- | --- |
| 1-1 | 같은 슬롯에 20개 동시 예약 요청 | 서비스 계층 호출 시 정확히 1건 성공(`ReservationResponse` 반환), 19건 `BusinessException(RESERVATION_SLOT_CONFLICT)` 발생. 실제 DB에 성공한 예약 1건 및 이에 대응하는 30분 슬롯 2건만 남고 실패 요청의 슬롯 잔존 없음 확인 (※ 컨트롤러 계층 HTTP 409 응답 처리는 직접 호출하지 않음) | [`ReservationConcurrencyTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationConcurrencyTest.kt) (`createHold_concurrentRequests_onlyOneSucceeds`, L108~L148) |
| 1-2 | 만료된 HELD가 있는 슬롯에 신규 예약 요청 (배치 미실행 상태) | 만료 시각이 지난 기존 HOLD 정리(EXPIRED 전이 및 슬롯 삭제) 후 새 예약 슬롯 확보 보장 (실제 DB 검증) | [`ReservationSlotRepositoryTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/repository/ReservationSlotRepositoryTest.kt) (`secureSlots_exactExpiredHold_cleansUpAndSecuresNewSlotInRealDb`, L327~L365) |
| 1-3 | 같은 `Idempotency-Key`로 결제 3회 순차 요청 | 크레딧 차감은 1회만 발생, 3번의 서비스 응답 모두 동일(`CONFIRMED`, 동일 reservationId) 반환 | [`ReservationPaymentConcurrencyTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationPaymentConcurrencyTest.kt) (`pay_sameKeyRepeated_chargesOnce`, L42~L51) |
| 1-4 | 같은 `Idempotency-Key`로 5개 스레드 동시 결제 경합 | 크레딧 차감은 1회만 발생, 성공 `>=1`(선행 커밋된 응답은 늦은 요청이 재생 가능), 실패는 `RESERVATION_STATE_CONFLICT` 부분집합, 이후 동일 키 재요청 시 `CONFIRMED` 응답 재생 | [`ReservationPaymentConcurrencyTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationPaymentConcurrencyTest.kt) (`pay_sameKeyConcurrently_chargesOnceAndReplays`, L171~L185) |
| 1-4-1 | 같은 예약에 서로 다른 `Idempotency-Key`로 5개 스레드 동시 결제 경합 | 정확히 1건만 성공, 크레딧 1회만 차감, 나머지 4건은 `RESERVATION_STATE_CONFLICT` 거절 | [`ReservationPaymentConcurrencyTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationPaymentConcurrencyTest.kt) (`pay_differentKeysConcurrently_chargesOnce`, L55~L66) |
| 1-4-2 | 같은 `Idempotency-Key`로 서로 다른 예약 2건 순차 결제 | 서로 다른 예약은 각각 `CONFIRMED`로 전이되고 크레딧도 각각 1회 차감된다. 예약별 `RESERVATION_CHARGE` 원장이 각각 1건씩 기록되며 멱등성 키 레코드는 2건 저장됨을 확인 | [`ReservationPaymentConcurrencyTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationPaymentConcurrencyTest.kt) (`pay_sameKeyForDifferentReservations_chargesBoth`, L132~L167) |
| 1-5 | 연장 ↔ 신규 예약 동시 요청(같은 슬롯) | 서비스 계층에서 둘 중 정확히 1건 성공, 실패한 쪽은 `RESERVATION_SLOT_CONFLICT` 예외 발생. 경합 구간의 슬롯은 한 예약 몫(2개 슬롯)만 영속화됨을 확인 (※ HTTP 409 응답 계층이 아닌 서비스 통합 테스트) | [`ReservationExtendIntegrationTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationExtendIntegrationTest.kt) (`extend_concurrentWithNewHold_onlyOneSucceeds`, L134~L147) |
| 1-6 | 같은 예약에 연장 요청 2회 동시 제출 | `end_time` 낙관적 검사 및 락으로 하나만 성공 | [`ReservationExtendIntegrationTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationExtendIntegrationTest.kt) |
| 1-7 | 인접 슬롯(14:00~15:00, 15:00~16:00) 동시 예약 | 경합 없이 두 예약 모두 정상 성공 | [`ReservationAdjacentSlotConcurrencyTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationAdjacentSlotConcurrencyTest.kt) |
| 1-8 | 부분 겹침 슬롯(14:00~15:00, 14:30~15:30) 동시 예약 | 서비스 계층 동시 호출 시 겹치는 슬롯으로 인해 정확히 1건만 성공, 실패 요청은 `RESERVATION_SLOT_CONFLICT` 예외 발생. 진 요청의 예약·슬롯은 롤백되고 이긴 예약 몫의 슬롯만 DB에 저장됨 (※ HTTP 409는 ErrorCode 규격 매핑이며 MockMvc 계층 호출 아님) | [`ReservationPartialOverlapConcurrencyTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationPartialOverlapConcurrencyTest.kt) (`createHold_partialOverlap_onlyOneSucceeds`, L27~L33) |
| 1-9 | 예약 취소 동시 요청 경합 | 1건만 취소 성공, 중복 환불 방지 | [`ReservationCancelConcurrencyTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationCancelConcurrencyTest.kt) |
| 1-10 | 회원 상태 변경(SUSPENDED)과 크레딧 증가(충전) 동시 경합 | 상태 변경 트랜잭션이 크레딧 증가 커밋 후 최신 잔액(15,000)을 덮어쓰지 않고 유지 | [`CreditBalanceConcurrencyIntegrationTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/credit/CreditBalanceConcurrencyIntegrationTest.kt) (`concurrentStatusChange_doesNotOverwriteUpdatedCreditBalance`) |
| 1-11 | 출입 토큰 동시 발급 요청 경합 | 중복 발급 없이 안전하게 일관성 유지 | [`DoorAccessTokenConcurrencyTest`](../../backend/src/test/java/com/ovengers/slotkey/access/service/DoorAccessTokenConcurrencyTest.java) |
| 1-12 | 관리자 강제 취소 완료 후 사용자 연장 시도 (순차 계약) | 관리자 강제 취소 완료(CANCELLED) 후 사용자 연장 시도 시 `BusinessException(RESERVATION_EXTEND_NOT_ALLOWED)`으로 거절되고 CANCELLED 상태 및 슬롯 삭제 상태 보존 확인 (※ 두 작업이 동일 트랜잭션 락 경합을 벌이는 동시 실행 테스트는 미작성·향후 보강 대상) | [`AdminReservationConcurrencyIntegrationTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/AdminReservationConcurrencyIntegrationTest.kt) (`contract_cancelCompleted_extendAbortsWithDisallowed`, L1455~L1500) |

---

## 2. 결제 및 크레딧 (Payment & Credit)

| # | 시나리오 | 기대 결과 | 검증 소스 매핑 |
| --- | --- | --- | --- |
| 2-1 | 잔액 부족 상태에서 결제(`/pay`) 시도 | 서비스 단위 테스트에서 `BusinessException(INSUFFICIENT_BALANCE)` 발생 및 확정 전이 미실행 확인. 통합 동시성 테스트에서 잔액 부족 실패 시 Idempotency-Key 미소진으로 충전 후 동일 키 재시도 성공 확인 (※ HTTP 422 응답 자체는 컨트롤러 테스트 대상이며, 서비스 테스트에서 직접 검증하지 않음) | [`ReservationPaymentConfirmServiceTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationPaymentConfirmServiceTest.kt) (`confirm_insufficientBalance_propagatesExceptionWithoutTransition`, L222~L244), [`ReservationPaymentConcurrencyTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationPaymentConcurrencyTest.kt) (`pay_insufficientBalance_doesNotConsumeIdempotencyKey`, L189~L206) |
| 2-2 | 동시 결제 2건의 겹치는 금액 차감 시도 (동시 차감 경합 및 음수 잔액 방지) | 조건부 UPDATE(`balance >= :amount`)를 통한 원자적 음수 차단. 실제 DB 동시성 테스트에서 서로 다른 예약 2건의 동시 결제 시 정확히 1건만 성공하고, 잔액 부족 결제는 `INSUFFICIENT_BALANCE`로 거절되며 성공한 예약에만 차감 원장이 기록되는지 검증 | [`ReservationPaymentConcurrencyTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationPaymentConcurrencyTest.kt) (`pay_differentReservationsConcurrently_preventsOverdraft`, L70~L128) |
| 2-3 | 크레딧 거래 엔티티 생성 및 잔액 반영 단위 검증 | RESERVATION_CHARGE·차감액·balanceAfter·Mock 저장을 확인한다. 이 단위 테스트는 DB SUM 검증이 아니지만 PG 충전 통합 테스트에는 별도의 원장 SUM/잔액 assertion이 있다. | [`CreditServiceImplTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/credit/service/CreditServiceImplTest.kt) (`charge_success`), [`ChargeOrderConfirmIntegrationTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/credit/service/ChargeOrderConfirmIntegrationTest.kt) (`assertBalanceInvariant`) |
| 2-4 | 취소 환불 구간별 분리 호출 (시작 1시간 전 마감 후 50% 위약금 구간) | 서비스 단위 테스트에서 `creditService.refund`(전액) 및 `penalize`(50% 위약금) 분리 호출 및 응답 금액(`refundAmount`, `penaltyAmount`) 계산 검증. 추가 통합 테스트에서 실제 DB에 `REFUND`와 `PENALTY` 원장이 각각 1건씩 기록되고, 동시 취소에서도 중복 기록되지 않는지 확인 | [`ReservationCancelServiceTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationCancelServiceTest.kt) (`cancel_afterOneHourDeadline_halfRefund`, L184~L217), [`ReservationCancelConcurrencyTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationCancelConcurrencyTest.kt) (`cancel_partialRefundConcurrently_recordsRefundAndPenaltyOnce`, L105~L160), [`ReservationCancelBoundaryIntegrationTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationCancelBoundaryIntegrationTest.kt) (`cancel_oneSecondAfterDeadline_recordsRefundAndPenalty`, L69~L115) |
| 2-4-1 | 예약 시작 정확히 1시간 전 취소 | 시작 1시간 전 정각에는 전액 환불되며 `REFUND` 원장이 1건 기록되고 `PENALTY` 원장은 생성되지 않음을 실제 DB에서 검증. 예약 취소 상태 전이 및 슬롯 삭제·상태 이력·Outbox 기록도 확인 | [`ReservationCancelBoundaryIntegrationTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationCancelBoundaryIntegrationTest.kt) (`cancel_exactlyOneHourBefore_recordsFullRefundOnly`, L47~L65) |
| 2-5 | 관리자 크레딧 지급 | 양수 금액 지급 및 잔액 반영 | [`CreditGrantServiceTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/credit/service/CreditGrantServiceTest.kt) |

---

## 3. 상태 전이 및 스케줄러 (State Transition & Batch)

| # | 시나리오 | 기대 결과 | 검증 소스 매핑 |
| --- | --- | --- | --- |
| 3-1 | 조건부 전이 쿼리 실패 시 예외 처리 (취소/체크아웃 충돌) | 조건부 취소 반환 0일 때 `RESERVATION_STATE_CONFLICT` 발생(`cancel_conflictState_throwsException`), 조건부 체크아웃 반환 0일 때 `RESERVATION_STATE_CONFLICT` 발생(`shouldNotRevokeTokenWhenCheckOutTransitionFails`). (※ 6종 상태 전이 조합의 전수 매트릭스 테스트는 미구현·보강 대상) | [`ReservationCancelServiceTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationCancelServiceTest.kt) (`cancel_conflictState_throwsException`, L107~L122), [`ReservationCheckOutServiceTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationCheckOutServiceTest.kt) (`shouldNotRevokeTokenWhenCheckOutTransitionFails`, L120~L154) |
| 3-2 | `HELD` 상태에서 도어 토큰 발급 시도 | 거절(`RESERVATION_STATE_CONFLICT`) 확인 | [`DoorAccessTokenServiceTest`](../../backend/src/test/java/com/ovengers/slotkey/access/service/DoorAccessTokenServiceTest.java) (`shouldRejectIssueWhenReservationIsNotConfirmed`, L176~L190) |
| 3-3 | 취소 후 재취소 시도 | 조건부 취소 반환이 0일 때 `RESERVATION_STATE_CONFLICT` 예외 발생 단위 검증. 관리자 강제 취소 경로는 순차 2회 호출 실 DB 테스트 완료 (`contract_cancelCompleted_reCancelAbortsWithAlreadyCancelled`). (※ 사용자 일반 취소 경로에서 동일 예약에 `cancel`을 2회 순차 호출하는 통합 테스트는 미작성·보강 대상) | [`ReservationCancelServiceTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationCancelServiceTest.kt) (`cancel_conflictState_throwsException`, L107~L122), [`AdminReservationConcurrencyIntegrationTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/AdminReservationConcurrencyIntegrationTest.kt) (L1440~L1453) |
| 3-4 | 이용 시작 시각(`startTime`) 정각 또는 이후 사용자 취소 시도 | 조건부 UPDATE 쿼리([`ReservationRepository`](../../backend/src/main/kotlin/com/ovengers/slotkey/reservation/repository/ReservationRepository.kt))의 `:now < r.startTime` 조건으로 영향 행 0 반환 및 서비스 계층 `RESERVATION_STATE_CONFLICT` 예외로 거절되는 구현 설계. 실제 DB 통합 테스트에서 시작 정각 취소 거절 및 예약 상태·잔액·원장·슬롯·상태 이력·Outbox의 부작용 부재를 검증함 (※ 시작 정각 이후의 별도 시간 경계 테스트는 미작성) | [`ReservationRepository`](../../backend/src/main/kotlin/com/ovengers/slotkey/reservation/repository/ReservationRepository.kt) (`cancelIfConfirmedAndBeforeStart` 쿼리 계약), [`ReservationCancelBoundaryIntegrationTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationCancelBoundaryIntegrationTest.kt) (`cancel_atStartTime_rejectsWithoutSideEffects`, L119~L135) |
| 3-5 | 시작 + 15분까지 미체크인 | 배치 프로세서가 조건부 노쇼 전이 성공 시 슬롯 삭제, 출입 토큰 폐기, `NO_SHOW` 상태 이력 저장을 검증함. (※ '환불 없음'은 `ReservationBatchProcessor`가 크레딧 환불 로직을 일체 호출하지 않는 구현 설계이며, 잔액 미변동 실 DB assertion은 단위 테스트 범위 밖임) | [`ReservationBatchProcessorTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/scheduler/ReservationBatchProcessorTest.kt) (`shouldRevokeTokenAndSaveHistoryWhenMarkNoShowSucceeds`, L89~L122) |
| 3-6 | 종료 시각 경과 후 미체크아웃(`IN_USE`) | 스케줄러가 종료 시각이 지난 후보를 조회해 `processor.autoCheckOut`에 위임하고, 프로세서는 성공 시 `END_TIME` 기준으로 출입 토큰 폐기 및 이력 저장을 검증함. (※ 실제 DB의 `checked_out_at = end_time` 컬럼 영속화는 단위 Mockito가 아닌 리포지토리 쿼리 계약 영역임) | [`ReservationCompletionSchedulerTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/scheduler/ReservationCompletionSchedulerTest.kt) (`autoCheckOut_callsProcessorForEachCandidate`, L78~L87), [`ReservationBatchProcessorTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/scheduler/ReservationBatchProcessorTest.kt) (`shouldRevokeTokenAtEndTimeWhenAutoCheckOutSucceeds`, L152~L188) |

---

## 4. 시간 경계 (`Clock` 주입 검증)

| # | 경계 | 케이스 | 검증 소스 매핑 |
| --- | --- | --- | --- |
| 4-1 | 최초 체크인 시간 경계 | `start - 1ns`(거절, `OUTSIDE_ALLOWED_TIME`) / `start + 15m`(허용) / `start + 15m + 1ns`(거절, `OUTSIDE_ALLOWED_TIME`) (※ `start` 정각 허용은 `!now.isBefore(start)` 구현 정책이며 단위 테스트 미호출) | [`DoorAccessTimePolicyTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/access/policy/DoorAccessTimePolicyTest.kt) (`shouldRejectFirstCheckInBeforeReservationStart`, `shouldAllowFirstCheckInUntilFifteenMinutesAfterStart`, `shouldRejectFirstCheckInAfterFifteenMinutes`) |
| 4-2 | 재입장 시간 경계 | `checked_in_at - 1ns`(거절) / `checked_in_at`(거절, 열린 구간) / `end - 1ns`(허용) / `end`(거절) | [`DoorAccessTimePolicyTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/access/policy/DoorAccessTimePolicyTest.kt) |
| 4-3 | HOLD 10분 만료 경계 | DB 조건부 UPDATE(`confirmIfHeldAndNotExpired`)가 `hold_expires_at` 정각에는 0을 반환하여 `HELD` 상태가 유지되고, `hold_expires_at - 1s`에는 1을 반환하여 `CONFIRMED`로 전이됨을 실 DB에서 직접 검증 (※ 결제 서비스 전체 플로우나 HTTP 응답 계층이 아닌 조건부 UPDATE 리포지토리 쿼리 경계 실측 검증) | [`ReservationPaymentConcurrencyTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationPaymentConcurrencyTest.kt) (`confirmIfHeldAndNotExpired_atExactExpiry_doesNotConfirm`, L210~L243) |

---

## 5. 인가 및 보안 (Authorization & Security)

| # | 시나리오 | 기대 결과 | 검증 소스 매핑 |
| --- | --- | --- | --- |
| 5-1 | 일반 회원의 타인 예약 취소 및 출입 토큰 발급 시도 | 소유권 불일치(`loginMemberId != reservationMemberId`) 시 `FORBIDDEN_NOT_OWNER` 예외 발생 (※ 타인 예약 조회 API의 소유권 검증 테스트는 현재 별도 부재) | 취소 소유권: [`ReservationCancelServiceTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationCancelServiceTest.kt) (`cancel_notOwner_throwsException`, L94~L105)<br>출입 토큰 소유권: [`DoorAccessAuthorizationServiceTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/access/authorization/DoorAccessAuthorizationServiceTest.kt) (`shouldThrowWhenMemberIsNotReservationOwner`, L54~L63) |
| 5-2 | 관리자 계정의 일반 취소/도어 토큰 발급 경로 소유권 우회 불가 여부 | 일반 취소/출입 로직에서 회원 ID 불일치로 거절 (`FORBIDDEN_NOT_OWNER`, 관리자 강제 취소 전용 경로 외 소유권 우회 불가 구조) (※ 관리자 토큰으로 일반 사용자 경로를 직접 호출하는 컨트롤러 테스트는 미작성·보강 대상) | 구현상 `loginMemberId == reservation.memberId` 단순 일치 검증으로 방어 (단위 테스트는 5-1 회원 ID 불일치 검증에 의존) |
| 5-3 | 일반 회원이 관리자 전용 공간 API 호출 시 인가 거절 | `ROLE_USER` 권한으로 관리자 공간 등록/조회 API 호출 시 403 Forbidden (`ACCESS_DENIED`) 확인 (※ 관리자 크레딧 지급 경로 `POST /api/v1/admin/members/{memberId}/credits` 등에 대한 일반 회원 403 차단 테스트는 별도 부재·보강 대상) | [`AdminSpaceSecurityIntegrationTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/space/controller/AdminSpaceSecurityIntegrationTest.kt) (`createSpace_userRole_returns403WithoutSideEffects`, L89~L103; `getSpaceDetail_userRole_returns403`, L107~L115) |
| 5-4 | 관리자가 자기 자신에게 크레딧 지급 시도 | 거절(`SELF_GRANT_NOT_ALLOWED`) | [`AdminMemberServiceTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/member/service/AdminMemberServiceTest.kt) (`grantCredit_selfGrant_throwsException`, L502~L533) |
| 5-5 | 만료 전 기존 Access Token으로 보호 API 호출 | `sid` 세션 검증 결과에 따라 허용/거절한다. Auth V2는 세션을 DB에서 확인하므로 "stateless 인증, DB 재조회 없음"으로 표현하지 않는다. 세션 만료·폐기·조회 불가는 401이다. | [`V2SecurityRedTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/auth/controller/V2SecurityRedTest.kt), [`AuthControllerTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/auth/controller/AuthControllerTest.kt), [`CustomAuthenticationFilter`](../../backend/src/main/java/com/ovengers/slotkey/global/security/CustomAuthenticationFilter.kt) |
| 5-6 | 정지된 계정이 리프레시 토큰으로 재발급(`/auth/refresh`) 시도 | 403 거절(`ACCOUNT_INACTIVE`, DB 상태 검증 수행) | [`AuthControllerTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/auth/controller/AuthControllerTest.kt) |
| 5-7 | 같은 이메일로 10회/15분 초과 로그인 시도 | HTTP 429 `LOGIN_RATE_LIMITED`와 `Retry-After`(초) 반환. 시도 카운트는 `REQUIRES_NEW`로 인증 실패 롤백과 분리된다. | [`LoginRateLimitIntegrationTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/auth/ratelimit/LoginRateLimitIntegrationTest.kt), [`AuthControllerTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/auth/controller/AuthControllerTest.kt) |
| 5-8 | 이미 소비된 리프레시 토큰 재사용 | 해당 세션과 세션의 모든 RT를 폐기하고 refresh를 거절한다. `consumed_at`·`revoked_at`을 DB에서 확인한다. | [`RefreshTokenServiceTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/auth/service/RefreshTokenServiceTest.kt), [`AuthSessionMigrationIntegrationTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/auth/migration/AuthSessionMigrationIntegrationTest.kt) |
| 5-9 | 비활성 회원의 출입 시도 | 출입을 거절하고 `REQUIRES_NEW` 거절 로그를 남긴다. 로그 저장이 본 응답·트랜잭션을 바꾸지 않는다. | [`InactiveMemberAccessIntegrationTest`](../../backend/src/test/java/com/ovengers/slotkey/access/service/InactiveMemberAccessIntegrationTest.java), [`InactiveMemberAccessFilterTest`](../../backend/src/test/java/com/ovengers/slotkey/global/security/InactiveMemberAccessFilterTest.java) |

---

## 6. 가격 및 공간 정책 (Pricing & Space Policy)

| # | 시나리오 | 기대 결과 | 검증 소스 매핑 |
| --- | --- | --- | --- |
| 6-1 | 공간 가격 변경 및 슬롯 가격 계산 단위 검증 | 공간 가격 및 버전 변경 단위 검증(`SpaceTest.updateDetail_priceChanged_versionIncrements`), 슬롯 수 비례 총액 계산(`PricingServiceTest.calculateTotalAmount_multipliesPriceAndSlotCount`). (※ 공간 가격 변경 후 기존 확정 예약을 조회하여 금액 불변을 확인하는 복합 시나리오는 `reservation.totalAmount` 스냅샷 영속화 설계 특성이며 별도 통합 테스트는 미구현·보강 대상) | [`SpaceTest`](../../backend/src/test/java/com/ovengers/slotkey/space/entity/SpaceTest.java) (`updateDetail_priceChanged_versionIncrements`, L36~L45), [`PricingServiceTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/PricingServiceTest.kt) (`calculateTotalAmount_multipliesPriceAndSlotCount`, L49~L53) |
| 6-2 | HOLD 중 관리자가 가격/정보 변경 → 결제(`/pay`) 시도 | 관리자 가격 수정 선행(`version 0 -> 1`) 커밋 후, 결제 시도가 최신 `spaceVersion` 불일치를 감지하여 서비스 계층에서 `BusinessException(SPACE_VERSION_MISMATCH)` 발생 (※ HTTP 409는 ErrorCode 규격 매핑이며 MockMvc 계층 호출 아님) | [`SpaceReservationLockIntegrationTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/SpaceReservationLockIntegrationTest.kt) (`concurrency_priceUpdateFirst_thenPaymentFailsWithVersionMismatch`, L339~L400) |
| 6-3 | 클라이언트 조작 금액 주입 시도 방지 | 결제 DTO([`ReservationPayRequest`](../../backend/src/main/kotlin/com/ovengers/slotkey/reservation/dto/request/ReservationPayRequest.kt))에 금액 필드가 없어(`spaceVersion`만 전달) 구조적으로 주입 불가, 서버 저장 금액(`reservation.totalAmount`)으로만 차감 | DTO 구조적 원천 방어 (별도 금액 조작 검증 테스트 클래스 부재) |
| 6-4 | 공간 운영시간 외 예약 시도 | 운영시간 위반 거절 | [`SpaceOperatingHoursPolicyTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/space/policy/SpaceOperatingHoursPolicyTest.kt) |

---

## 7. 관리자 공간 이미지 관리 (Space Image Management)

| # | 시나리오 | 기대 결과 | 검증 소스 매핑 |
| --- | --- | --- | --- |
| 7-1 | 파일 시스템 저장, UUID 파일명 생성, JPEG/PNG 검증 | 물리 디렉터리 저장 및 확장자 검증 통과 | [`SpaceImageStorageTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/space/image/SpaceImageStorageTest.kt) |
| 7-2 | 대표 사진 등록/교체 및 트랜잭션 롤백 시 파일 롤백 | DB 롤백 시 업로드된 새 파일 삭제 및 원본 유지 | [`AdminSpaceImageServiceIntegrationTest`](../../backend/src/test/java/com/ovengers/slotkey/space/service/AdminSpaceImageServiceIntegrationTest.java) |
| 7-3 | 5MB 초과 사진 업로드 시도 (5MB+10KB) | 413 Payload Too Large (`IMAGE_SIZE_EXCEEDED`) 규격화 거절 | [`AdminSpaceImageMultipartLimitIntegrationTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/space/controller/AdminSpaceImageMultipartLimitIntegrationTest.kt) (`uploadSpaceImage_exceedsServletMultipartLimit_returns413`, L53~L90) |
| 7-4 | 공개 이미지 바이너리 서빙 GET 200 | 스트리밍 응답, `Content-Type: image/jpeg, image/png` 검증 | [`SpaceImageControllerTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/space/controller/SpaceImageControllerTest.kt) |
| 7-5 | OpenAPI / Swagger UI 명세 자동 검증 | Multipart PUT 및 바이너리 GET 스키마 유효성 | [`SpaceOpenApiDocumentationTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/space/controller/SpaceOpenApiDocumentationTest.kt) |

---

## 8. 감사 로그 (Audit Logs)

| # | 시나리오 | 기대 결과 | 검증 소스 매핑 |
| --- | --- | --- | --- |
| 8-1 | 중요 상태 변경 시 비즈니스 변경과 감사 로그의 정상 동반 커밋 | 단위 Mockito 테스트에서 `AuditLogService.log()` 호출 및 `auditLogRepository.save()` 전달 인자 검증. 실제 회원 정지/복구/크레딧 지급 비즈니스 변경과 감사 로그의 동일 트랜잭션 동반 DB 커밋은 서비스 통합 테스트에서 직접 검증 확인 | 단위 검증: [`AuditLogServiceTest`](../../backend/src/test/java/com/ovengers/slotkey/audit/service/AuditLogServiceTest.java)<br>동반 커밋 통합 검증: [`AdminMemberServiceIntegrationTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/member/service/AdminMemberServiceIntegrationTest.kt) (`suspend_success_commitsMemberStatusAndAudit`, `restore_success_commitsMemberStatusAndAudit`, `grantCredit_success_commitsBalanceTransactionAndAudit`, L173~L250) |
| 8-2 | 감사 로그 트랜잭션 전파 및 감사 실패 시 비즈니스 롤백 | 1) 감사 서비스 트랜잭션 검증: 트랜잭션 없이 호출 시 `IllegalTransactionStateException` 발생 및 미저장, 트랜잭션 내 호출 시 정상 저장 확인 ([`AuditLogServiceTransactionTest`](../../backend/src/test/java/com/ovengers/slotkey/audit/service/AuditLogServiceTransactionTest.java))<br>2) 감사 실패 시 비즈니스 롤백: 감사 저장 호출에 SpyBean으로 예외를 주입했을 때 비즈니스 변경(회원 상태·잔액·거래 원장)이 실제 DB에서 롤백됨을 실 DB 재조회로 검증 ([`AdminMemberServiceIntegrationTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/member/service/AdminMemberServiceIntegrationTest.kt), L94~L171)<br>(※ 비즈니스 로직 예외 발생 시 선행 저장된 감사 로그의 동반 롤백 시나리오는 전용 통합 테스트 미작성·보강 대상) | [`AuditLogServiceTransactionTest`](../../backend/src/test/java/com/ovengers/slotkey/audit/service/AuditLogServiceTransactionTest.java), [`AdminMemberServiceIntegrationTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/member/service/AdminMemberServiceIntegrationTest.kt) (`suspend_auditLogFails_rollsBackMemberStatus`, `restore_auditLogFails_rollsBackMemberStatus`, `grantCredit_auditLogFails_rollsBackBalanceAndTransaction`, L94~L171) |
| 8-3 | 관리자 감사 로그 페이징 조회 API | 관리자 권한 확인 및 페이징 목록 정상 반환 | [`AdminAuditLogControllerTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/audit/controller/AdminAuditLogControllerTest.kt), [`AdminAuditLogSecurityIntegrationTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/audit/controller/AdminAuditLogSecurityIntegrationTest.kt) |
| 8-4 | 감사 로그 레포지토리 및 쿼리 서비스 | 조건별 필터링 및 정렬 조회 검증 | [`AuditLogRepositoryTest`](../../backend/src/test/java/com/ovengers/slotkey/audit/repository/AuditLogRepositoryTest.java), [`AuditLogQueryServiceTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/audit/service/AuditLogQueryServiceTest.kt) |

---

## 9. 현재 미구현 및 미검증 영역 (Gap Analysis)

과거 미검증 목록을 바탕으로 하되 2026-10-20 기준선(`d1a15ec`, 1,048/1,048)과 현재 소스를 대조했다. 나머지 항목은 신규 작업 전 현재 소스와 재대조하며 “테스트 부재”를 그대로 신규 작성 지시로 쓰지 않는다. 소스 존재와 실행 성공은 구분한다.

1. **프론트엔드 자동화 테스트의 범위와 한계**:
   - **과거 실행 기록**: 2026-09-24 기록의 Node 테스트 14개 통과는 당시 결과다. 현재 테스트 목록/케이스 수는 `node --test tests/*.cjs`로 새로 집계한다. 기존 예약 카드·검색·문의·관리자 대시보드 정적 검증과 이후 추가된 테스트를 구분한다.
   - **미구현 영역**: Jest/Vitest 통합 테스트 러너 환경, Playwright/Cypress 브라우저 E2E 테스트, 실제 브라우저 DOM 렌더링 및 클릭·입력 상호작용 자동화 테스트는 부재함.
2. **문의의 회원·서비스/DB 검증 보강**:
   - [`AdminInquiryControllerTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/inquiry/controller/AdminInquiryControllerTest.kt)에 11개 테스트가 있다. 실제 SecurityFilterChain을 포함한 WebMvcTest로 목록의 관리자/일반 회원/미인증, 필터/기본 페이징·상세/404·답변 성공 JSON/위임을 확인한다. **2026-10-20 기준선에서 이 클래스를 포함한 전체 1,048건이 통과했지만**, 서비스는 MockBean이므로 회원 소유권·수정·답변의 실제 저장/상태 전이와 거절 부작용 부재는 별도 보강 대상이다.
3. **크레딧 동시 차감 및 복합 원장 흐름 보강**:
   - `CreditBalanceConcurrencyIntegrationTest`는 크레딧 증가/회원 상태 변경 경합이다. [`ChargeOrderConfirmIntegrationTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/credit/service/ChargeOrderConfirmIntegrationTest.kt)의 `assertBalanceInvariant`는 실제 DB의 회원 잔액과 원장 SUM을 비교한다. 서로 다른 예약의 잔액 초과 동시 차감은 [`ReservationPaymentConcurrencyTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationPaymentConcurrencyTest.kt) (`pay_differentReservationsConcurrently_preventsOverdraft`, L70~L128)에서 검증했다. 다만 PG 충전 경로의 assertion과 일부 예약 차감·환불·위약금 검증을 모든 복합 원장 흐름의 검증 완료로 확대하지 않는다. 나머지 검증 범위는 [금전·경합 계획](../plans/kotlin-migration/02-money-and-concurrency.md)에 따라 대조·보강한다.
4. **상태 전이 6종 전수 매트릭스 및 노쇼 크레딧 미환불 DB 검증 부재**:
   - 조건부 UPDATE의 0 반환 시 예외 발생 단위 테스트는 있으나, 상태 전이 불가 6종 조합에 대한 전수 통합 테스트 및 노쇼 전이 시 실제 크레딧 잔액 미변동 실 DB 검증은 미구현 상태임.
5. **사용자 일반 취소 재취소 및 이용 시작 이후 취소 거절 추가 검증**:
   - 관리자 강제 취소의 순차 2회 호출은 실 DB 테스트(`AdminReservationConcurrencyIntegrationTest.contract_cancelCompleted_reCancelAbortsWithAlreadyCancelled`)로 검증되었다. 사용자 일반 취소 경로(`ReservationCancelService.cancel`)에서 동일 예약에 2회 순차 호출하는 통합 테스트는 미작성 상태다. 이용 시작 정각 취소 거절은 [`ReservationCancelBoundaryIntegrationTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationCancelBoundaryIntegrationTest.kt) (`cancel_atStartTime_rejectsWithoutSideEffects`, L119~L135)에서 실 DB 검증을 완료했으며, 시작 정각 이후의 별도 시간 경계 테스트는 미작성 상태다.
   - **2026-10-26 갱신:** 이용 시작 정각 취소 쿼리의 `:now < r.startTime`을 `<=`로 변조하면 위 테스트가 실패함을 확인했다([뮤테이션 테스트 결정](../decisions/mutation-testing.md) §5). 동일 예약 2회 순차 취소는 이 갱신의 확인 범위가 아니다.
6. **취소 시 REFUND·PENALTY 원장 영속성 검증 현황**:
   - `ReservationCancelServiceTest` 단위 Mockito 테스트에서는 환불과 위약금 처리 메서드 호출을 검증한다. 추가로 [`ReservationCancelConcurrencyTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationCancelConcurrencyTest.kt) (`cancel_partialRefundConcurrently_recordsRefundAndPenaltyOnce`, L105~L160)와 [`ReservationCancelBoundaryIntegrationTest`](../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/service/ReservationCancelBoundaryIntegrationTest.kt) (`cancel_oneSecondAfterDeadline_recordsRefundAndPenalty`, L69~L115)에서 실제 DB에 `REFUND`와 `PENALTY` 거래 내역이 각각 1건씩 영속화되고 최종 잔액과 `balanceAfter`가 일치하는지 검증했다. 해당 부분 환불 시나리오는 검증되었으며, 다른 복합 금전 흐름의 전수 검증과는 구분한다.
7. **관리자 강제 취소 ↔ 사용자 연장 동시 경합(데드락 부재) 테스트 부재**:
   - 관리자 강제 취소 완료 후 사용자 연장이 거절되는 순차 계약 테스트는 존재하나, 두 트랜잭션이 동시에 경합하여 데드락 없이 완료되는 동시성 통합 테스트는 미작성 상태임.
8. **관리자 크레딧 지급 API 인가 차단 및 타인 예약 조회 소유권 통합 테스트 부재**:
   - 일반 회원이 관리자 크레딧 지급 경로(`POST /api/v1/admin/members/{memberId}/credits`)를 호출했을 때의 403 차단 테스트 및 타인 예약 조회 API 소유권 차단 테스트, 가격 변경 후 기존 확정 예약 조회 시 원래 금액이 유지되는 복합 시나리오 통합 테스트는 미작성 상태임.
9. **비즈니스 예외 시 감사 로그 동반 롤백 및 자동 체크아웃 컬럼 영속성 통합 테스트 부재**:
   - 감사 실패 시 비즈니스 롤백은 검증되었으나 비즈니스 예외 발생 시 감사 로그 동반 롤백 통합 테스트는 미작성 상태이며, 스케줄러 단위 Mockito 테스트 외 실제 DB `checked_out_at = end_time` 컬럼 값 영속 상태 확인 통합 테스트도 미작성 상태임.
   - **2026-10-26 갱신:** 자동 체크아웃의 `checked_out_at = end_time` 영속은 `ReservationConditionalUpdateBoundaryTest`(`automatic checkout transitions at the exact end time and records the end time`)가 실제 MySQL로 확인한다. 비즈니스 예외 시 감사 로그 동반 롤백 통합 테스트는 여전히 미작성이다.
10. **Swagger UI 브라우저 `Try it out` 파일 업로드**:
    - MockMvc 및 정적 스키마 검증은 완료되었으나, 브라우저 환경에서 실제 multipart 바이너리를 Swagger UI로 업로드하는 시나리오는 수동 브라우저 미검증 상태임.
11. **AWS 클라우드 인프라 배포 및 운영 스토리지**:
    - RDS/S3/EC2/CloudFront의 실제 배포 및 장애 조치(Failover) 검증은 미실시 상태임.

---

## 10. 테스트의 신뢰도 검증 (뮤테이션 테스트)

위 1~9절은 "어떤 규칙을 어떤 테스트가 확인하는가"를 다룬다. 이 절은 **그 테스트들이 실제로 틀린 코드를 잡아내는가**를 다룬다. 테스트 수와 라인 커버리지는 단언이 약해도 통과하므로, 도구(PIT)와 수동 변조로 운영 코드를 일부러 망가뜨려 테스트가 실패하는지 확인했다.

| 항목 | 내용 |
| --- | --- |
| 결정·결과·한계 | [뮤테이션 테스트 결정](../decisions/mutation-testing.md) (§5 최종 결과 요약) |
| 범위·진행 | [뮤테이션 테스트 계획](../plans/mutation-testing/00-mutation-testing-plan.md) |
| 단계별 보고 | [`lab/notes/mutation-testing/`](../lab/notes/mutation-testing/) |
| 실행 방법 | `cd backend && ./gradlew pitest` (결과: `backend/build/reports/pitest/`). 수동 실행이며 CI에 연결하지 않았다 |
| 새 테스트를 추가할 때 | `backend/build.gradle`의 `pitest.targetTests`에도 클래스를 추가해야 PIT 결과에 반영된다 |
| 요약 (2026-10-26) | 구멍 16개를 찾아 테스트 21개로 보강했다. 정책 2개·`PricingService`는 뮤턴트 43개 중 43개를 잡았고, 돈이 오가는 핵심(주문 상태, 잔액, 원장)은 기존 테스트가 이미 탄탄했다 |
| 이 방법으로 검증하지 못하는 것 | 필드 대입과 쿼리(JPQL) 문자열은 PIT가 변조하지 못해 수동 변조로 보완했다. 동시성·멱등성 경합은 뮤턴트로 잡히지 않으므로 1·2절의 통합 테스트가 계속 필요하다 |
