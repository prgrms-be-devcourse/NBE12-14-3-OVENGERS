# 뮤테이션 테스트 4단계 보고: 상태 전이와 조건부 UPDATE

> 작성: 2026-10-26. 구현자 보고이며 검증은 Claude가 별도 세션에서 수행한다. 범위는 [계획](../../../plans/mutation-testing/00-mutation-testing-plan.md), 이유는 [결정 문서](../../../decisions/mutation-testing.md), 앞 단계는 [step-3](step-3.md)에 있다.

## 1. 이번 단계에서 한 일

1. 측정용으로 남아 있던 `Reservation` 엔티티·전체 예약 서비스 PIT 설정을 제거하고, 커밋된 3단계 설정에 `ChargeOrder`와 직접 관련된 비-Spring 테스트만 추가했다.
2. `ChargeOrder`의 생존 뮤턴트 5건을 분류하고, 명시적 null 불변식을 검증하는 새 Kotlin 단위 테스트 1건으로 실제 구멍 1건을 보강했다.
3. PIT가 JPQL 문자열을 변조하지 못하므로 `ReservationRepository`의 조건부 UPDATE 10개에 각각 2건씩 총 20건을 한 번에 하나씩 수동 변조했다. 매 실행 직후 원복했고 최종 diff가 없음을 확인했다.
4. 기존 mock 기반 테스트를 모두 통과한 수동 변조 6건을 새 Kotlin 통합 테스트 8건으로 보강하고, 같은 변조를 다시 적용해 모두 실패하는 것을 확인했다.

운영 코드(`ChargeOrder.kt`, `ReservationRepository.kt`, `Reservation.kt`)는 최종 변경하지 않았다.

## 2. `ChargeOrder` PIT 측정 결과

### 2-1. 전/후 측정표

`STRONGER` 변이 세트이며, 전체 행은 앞 단계 대상(정책 2개와 `PricingService`)을 포함한다.

| 범위 | 시점 | 생성 | 잡힘 | 살아남음 | 커버리지 없음 | mutation score | test strength |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `ChargeOrder` | 보강 전 | 28 | 11 | 5 | 12 | 39% | 69% |
| `ChargeOrder` | 보강 후 | 28 | 12 | 4 | 12 | 43% | 75% |
| 전체 PIT 대상 | 보강 전 | 71 | 54 | 5 | 12 | 76% | 92% |
| 전체 PIT 대상 | 보강 후 | 71 | 55 | 4 | 12 | 77% | 93% |

- 보강 전 단독 `pitest`: BUILD SUCCESSFUL, 전체 명령 12초, PIT 내부 분석 3초.
- 보강 후 `ktlintCheck` + 새 단위 테스트 + `pitest`: BUILD SUCCESSFUL, 전체 명령 14초, PIT 내부 분석 3초.
- 대상 테스트는 11개에서 새 `ChargeOrderMutationTest`를 포함한 12개로 늘었다.

### 2-2. 생존 뮤턴트 분류표

| 위치 | 변조 | 분류 | 근거 | 조치 |
| --- | --- | --- | --- | --- |
| `createPending:153` | `requireNotNull(memberId)`의 null 분기 제거 | **구멍** | factory가 nullable `Long?`을 받고 명시적으로 null을 거절하지만 기존 테스트는 non-null 호출만 했다 | `ChargeOrderMutationTest.pending order rejects a null member id` 추가. 해당 뮤턴트 KILLED |
| `getOrderId:35` | `lateinit` null 검사 조건 제거 | 대상 아님 | Kotlin이 생성한 getter의 미초기화 예외 경로다. 정상 객체는 private factory/JPA 로딩 뒤 초기화되고 상태 전이 규칙이 아니다 | 테스트 추가 없음 |
| `getStatus:49` | `lateinit` null 검사 조건 제거 | 대상 아님 | Kotlin 생성 getter이며 상태 대입 자체의 변조가 아니다 | 테스트 추가 없음 |
| `getCreatedAt:85` | `lateinit` null 검사 조건 제거 | 대상 아님 | Kotlin 생성 getter의 미초기화 방어 코드다 | 테스트 추가 없음 |
| `getUpdatedAt:89` | `lateinit` null 검사 조건 제거 | 대상 아님 | Kotlin 생성 getter의 미초기화 방어 코드다 | 테스트 추가 없음 |

NO_COVERAGE 12건도 nullable/`lateinit` getter 반환값과 미초기화 예외 호출에 생성된 뮤턴트로, 상태 전이 대상이 아니다. 더 중요한 한계는 `markPaid`, `markFailed`, `scheduleNextRecovery`, `markRecoveryExhausted`, `recordPaymentMethod`가 테스트 실행 중 실제 객체에서 호출되더라도 PIT `STRONGER`가 JVM `putfield` 대입을 변조하지 않아 이 메서드들에는 뮤턴트가 **0건 생성**됐다는 점이다. 따라서 이 측정은 전이 대입의 충분성을 증명하지 않는다.

## 3. 조건부 UPDATE 수동 변조 결과

각 행은 원본 쿼리에 변조 하나만 적용하고 관련 테스트 클래스만 실행한 결과다. `상태 =`을 `상태 <>`로 바꾼 것은 상태 조건 제거와 같은 가드 손실을 실제 실행 가능한 JPQL로 관찰하기 위한 대체 변조다. 단순히 조건을 삭제하면 사용하지 않는 `@Param` 때문에 Spring Data query validation에서 즉시 실패한다.

| 쿼리 | 변조 내용 | 결과 | 죽인 테스트 | 조치 |
| --- | --- | --- | --- | --- |
| `confirmIfHeldAndNotExpired` | `:now < holdExpiresAt` → `<=` | 잡힘 | `confirmIfHeldAndNotExpired_atExactExpiry_doesNotConfirm` | 없음 |
| `confirmIfHeldAndNotExpired` | `status = :held` → `<>` | 잡힘 | `confirmIfHeldAndNotExpired_atExactExpiry_doesNotConfirm`의 1초 전 단언 외 결제 통합 테스트 | 없음 |
| `expireHeldReservations` | `holdExpiresAt <= :now` → `<` | 잡힘 | `expireHeldReservations_exactNowMatch` 외 3건 | 없음 |
| `expireHeldReservations` | `status = :held` → `<>` | 잡힘 | `expireHeldReservations_exactNowMatch` 외 3건 | 없음 |
| `cancelIfConfirmedAndBeforeStart` | `:now < startTime` → `<=` | 잡힘 | `cancel_atStartTime_rejectsWithoutSideEffects` | 없음 |
| `cancelIfConfirmedAndBeforeStart` | `status = :confirmed` → `<>` | 잡힘 | `cancel_exactlyOneHourBefore_recordsFullRefundOnly`, `cancel_oneSecondAfterDeadline_recordsRefundAndPenalty` | 없음 |
| `cancelIfHeld` | `status = :held` → `<>` | **구멍**: 기존 12개 서비스 테스트 전부 통과 | 보강 후 `HELD cancellation changes status...`, `HELD cancellation does not change a CONFIRMED...` | 새 통합 테스트 2건 |
| `cancelIfHeld` | `SET status = :cancelled` → `:held` | **구멍**: 기존 12개 서비스 테스트 전부 통과 | 보강 후 `ReservationConditionalUpdateBoundaryTest` 컨텍스트의 query validation과 성공 전이 테스트 | 새 통합 테스트에 실제 repository 로딩·성공 결과 단언 |
| `forceCancelIfStatusIs` | `status = :expected` → `<>` | 잡힘 | `forceCancel_success_commitsAllChangesAndAudit` 외 1건 | 없음 |
| `forceCancelIfStatusIs` | `SET status = :cancelled` → `:expected` | 잡힘 | `AdminReservationServiceIntegrationTest` 3건 | 없음 |
| `checkInIfConfirmed` | `status = :confirmed` → `<>` | 잡힘 | `forceCancel_whenConcurrentTransitionOccurs_failsWithConflictAndAborts` | 없음 |
| `checkInIfConfirmed` | `SET status = :inUse` → `:confirmed` | 잡힘 | `AdminReservationServiceIntegrationTest` 3건 | 없음 |
| `checkOutIfInUse` | `status = :inUse` → `<>` | 잡힘 | `forceCancel_whenConcurrentTransitionOccurs_failsWithConflictAndAborts` | 없음 |
| `checkOutIfInUse` | `SET status = :completed` → `:inUse` | 잡힘 | `AdminReservationServiceIntegrationTest` 3건 | 없음 |
| `extendIfEndTimeMatches` | `endTime = :expectedEndTime` → `<>` | 잡힘 | `extend_success_updatesSlotsBalanceAndReservation` 외 4건 | 없음 |
| `extendIfEndTimeMatches` | `status IN` → `NOT IN` | 잡힘 | `extend_success_updatesSlotsBalanceAndReservation` 외 3건 | 없음 |
| `markNoShowIfNotCheckedIn` | `startTime < threshold` → `<=` | **구멍**: 기존 6개 processor 테스트 전부 통과 | 보강 후 `no-show does not transition at the exact check-in deadline` | 새 정각 경계 테스트 |
| `markNoShowIfNotCheckedIn` | `status = :confirmed` → `<>` | **구멍**: 기존 6개 processor 테스트 전부 통과 | 보강 후 `no-show transitions one second after...`, `no-show does not change a HELD...` | 새 성공·상태 가드 테스트 |
| `autoCheckOutIfEnded` | `endTime <= :now` → `<` | **구멍**: 기존 6개 processor 테스트 전부 통과 | 보강 후 `automatic checkout transitions at the exact end time...` | 새 정각 경계 테스트 |
| `autoCheckOutIfEnded` | `status = :inUse` → `<>` | **구멍**: 기존 6개 processor 테스트 전부 통과 | 보강 후 `automatic checkout transitions at the exact end time...`, `automatic checkout does not change a CONFIRMED...` | 새 성공·상태 가드 테스트 |

수동 변조 20건 중 기존 테스트가 잡은 것은 14건, 구멍은 6건이다. 보강 뒤 같은 6건을 재적용했을 때 새 통합 테스트가 모두 실패했다. 모든 변조를 즉시 원복했고 최종 `git diff -- ReservationRepository.kt`는 비어 있다.

## 4. 추가한 테스트와 검증 내용

### `ChargeOrderMutationTest` — 1건

- **직접 뮤턴트 살해 1건:** nullable factory 인자에서 `requireNotNull`을 제거한 생존 뮤턴트를 죽인다.

### `ReservationConditionalUpdateBoundaryTest` — 8건

- **직접 수동 변조 살해 목적 7건:** HELD 취소 성공·다른 상태 거절, 노쇼 정각·1초 후·다른 상태 거절, 자동 체크아웃 정각·다른 상태 거절.
- **경계 고정 보강 1건:** 자동 체크아웃 종료 1초 전에는 전이하지 않는다.
- 자동 체크아웃 성공 테스트는 `checked_out_at`이 배치 실행 시각이 아니라 예약 `end_time`과 같은지도 확인한다.

합계: **새 테스트 9건 = 직접 변조/뮤턴트 살해 목적 8건 + 경계 고정 보강 1건**.

## 5. 대표 사례 후보

| 사례 | 기존에 못 잡은 이유 | 추가한 테스트 | 결과 |
| --- | --- | --- | --- |
| `ChargeOrder.createPending`의 `requireNotNull(memberId)` 제거 | 기존 서비스 테스트가 항상 실제 회원 ID만 전달했다 | null 회원 ID를 전달해 `IllegalArgumentException`을 단언 | PIT 생존 1건 KILLED |
| `cancelIfHeld`의 상태 가드 반전·결과 상태 무변경 | 기존 `ReservationCancelServiceTest`는 repository mock 반환값만 정해 JPQL을 실행하지 않았다 | 실제 MySQL에서 HELD 성공과 CONFIRMED 거절을 각각 단언 | 수동 변조 2건이 보강 후 실패 |
| 노쇼·자동 체크아웃 정각 비교 연산자 변경 | 기존 `ReservationBatchProcessorTest`는 repository mock이라 `<`/`<=`를 실행하지 않았다 | 같은 순간·1초 전·1초 후를 고정한 repository 통합 테스트 | 노쇼 `<→<=`, 자동 체크아웃 `<=→<` 변조가 보강 후 실패 |

## 6. 실행한 명령과 결과

- PIT: `env -u SPRING_MAIL_USERNAME -u SPRING_MAIL_PASSWORD -u VERIFICATION_MAIL_FROM -u SLOTKEY_KAFKA_ENABLED ./gradlew --no-daemon pitest`
  - 보강 전 BUILD SUCCESSFUL, 전체 71개 중 54개 killed, survived 5, NO_COVERAGE 12.
  - 보강 후 BUILD SUCCESSFUL, 전체 71개 중 55개 killed, survived 4, NO_COVERAGE 12.
- 수동 변조: 각 변조마다 `./gradlew --no-daemon test --tests '*관련테스트클래스*'`를 실행했다. 구체 결과는 §3 표에 있다.
- 보강 테스트: `./gradlew --no-daemon ktlintCheck test --tests '*ReservationConditionalUpdateBoundaryTest*'` → 8건 통과. 같은 생존 변조 6건을 재적용하면 모두 실패.
- ChargeOrder 보강: `./gradlew --no-daemon ktlintCheck test --tests '*ChargeOrderMutationTest*' pitest` → BUILD SUCCESSFUL, 해당 생존 뮤턴트 KILLED.
- 전체 테스트: `./gradlew --no-daemon test` → **BUILD SUCCESSFUL (8분 42초), 169개 클래스·1,305건·실패 0·오류 0·스킵 0**. XML을 직접 합산했다.

## 7. 인계 보고

1. 변경 파일
   - `backend/build.gradle`: PIT 대상에 `ChargeOrder`와 직접 관련 테스트 추가
   - `backend/src/test/kotlin/com/ovengers/slotkey/credit/entity/ChargeOrderMutationTest.kt`: 신규 단위 테스트 1건
   - `backend/src/test/kotlin/com/ovengers/slotkey/reservation/repository/ReservationConditionalUpdateBoundaryTest.kt`: 신규 통합 테스트 8건
   - `docs/lab/notes/mutation-testing/step-4.md`: 이 보고서
2. 실행한 명령과 결과: §6 참조. ktlint와 전체 테스트 통과, 수동 변조 20건 및 재검증 완료.
3. 가정·미확인
   - PIT `STRONGER`가 `putfield`를 변조하지 않는다는 판단은 생성된 뮤턴트 XML과 `javap -c` 바이트코드를 대조한 결과다. 다른 PIT 플러그인/커스텀 mutator 가능성은 미확인이다.
   - CI와 Claude 별도 검증은 미확인이다.
   - `start + 15분` 정각의 노쇼 전이 정책은 §9 질문처럼 문서 표현과 코드 주석의 해석을 사용자에게 확인해야 한다.
4. 관련 결정 문서: [mutation-testing.md](../../../decisions/mutation-testing.md), [core-domain-decisions.md](../../../core-domain-decisions.md) §3.

## 8. 리뷰 체크리스트

- [ ] `ChargeOrder` PIT 대상과 테스트가 직접 관련 클래스로만 제한돼 있다.
- [ ] 생존 5건 중 구멍 1건과 Kotlin 생성 코드 4건의 분류 근거가 코드·XML과 맞는다.
- [ ] `ChargeOrder` 전이 메서드의 `putfield`에 PIT 뮤턴트가 생성되지 않았다는 한계를 과장 없이 기록했다.
- [ ] 수동 변조는 한 번에 하나만 적용했고 20건 모두 결과를 기록했다.
- [ ] `ReservationRepository.kt`에 최종 diff가 없고 운영 코드를 수정하지 않았다.
- [ ] 새 테스트는 기존 테스트를 수정하지 않은 별도 Kotlin 파일 2개다.
- [ ] 자동 체크아웃은 정각 포함(`<=`)이며 `checked_out_at = end_time`을 검증한다.
- [ ] 전체 테스트 1,305건과 ktlint가 통과했다.
- [ ] 시크릿·Lombok·배포 job 변경이 없다.

## 9. 사용자 결정이 필요한 질문

1. `Reservation` 엔티티의 운영 코드에서 호출되지 않는 전이 메서드 7개(`confirm`, `expire`, `cancel`, `checkIn`, `checkOut`, `markNoShow`, `extend`)를 삭제할지? 현재 실제 전이는 repository 조건부 UPDATE가 담당해 규칙이 두 곳에 중복되고 어긋날 위험이 있다. 이번 단계에서는 삭제하거나 테스트를 추가하지 않았다.
2. 노쇼 정각 경계를 현재 쿼리·주석처럼 **`start + 15분`이 지난 뒤**(`startTime < threshold`)로 유지할지, `core-domain-decisions.md` §3의 “start + 15분 미체크인”을 정각 포함(`<=`)으로 해석할지? 수동 변조에서 이 차이가 드러났다. 이번 단계에서는 운영 코드를 바꾸지 않고 현재 `<` 동작을 테스트로 기록했다.

## 10. 커밋 대상 파일

커밋은 하지 않았다. 사용자 확인 후 코드와 문서를 나눈다.

### 코드 커밋

- `backend/build.gradle`
- `backend/src/test/kotlin/com/ovengers/slotkey/credit/entity/ChargeOrderMutationTest.kt`
- `backend/src/test/kotlin/com/ovengers/slotkey/reservation/repository/ReservationConditionalUpdateBoundaryTest.kt`

### 문서 커밋

- `docs/lab/notes/mutation-testing/step-4.md` (`docs/lab/` 로컬 exclude 때문에 `git add -f` 필요)

