# 이슈 #154 구현자 보고: `ChargeOrder` 상태 전이 수동 변조

> 작성: 2026-10-26. 구현자 보고이며 검증은 Claude가 별도 세션에서 수행한다. 앞 단계는 [step-4](step-4.md), 결정 근거와 누적 검증 결과는 [mutation-testing.md](../../../decisions/mutation-testing.md)에 있다.

## 1. 이번 작업에서 한 일

1. 오픈소스 PIT가 변조하지 못한 `ChargeOrder` 필드 대입 14건을 한 번에 하나씩 수동 변조했다.
2. `ChargeOrderRepository`의 조건부 UPDATE와 복구 후보 조회 경계 6건을 수동 변조했다.
3. 기존 테스트를 모두 통과한 구멍 5건을 새 Kotlin 단위 테스트 2건으로 보강했다.
4. 같은 변조 5건을 다시 적용해 새 테스트가 모두 `AssertionFailedError`로 실패하는 것을 확인하고 즉시 원복했다.

운영 코드와 `build.gradle`은 최종 변경하지 않았다. 외부 PG API도 호출하지 않았다.

## 2. 수동 변조 결과

| 대상 | 변조 내용 | 결과 | 실패 원인 | 죽인 테스트 | 조치 |
| --- | --- | --- | --- | --- | --- |
| `recordPaymentMethod` | `paymentMethod` 대입 제거 | 잡힘 | 단언 | `ChargeOrderServiceTest.getMyHistory_excludesPendingOrders` | 없음 |
| `recordPaymentMethod` | `paymentProvider` 대입 제거 | 잡힘 | 단언 | `ChargeOrderServiceTest.getMyHistory_excludesPendingOrders` | 없음 |
| `markPaid` | `PAID` → `FAILED` | 잡힘 | 단언: expected `PAID`, actual `FAILED` | `ChargeOrderConfirmIntegrationTest.confirm_approved_marksPaidAndTopUpsAtomically` | 없음 |
| `markPaid` | `failureCode = null` 제거 | **구멍** | 기존 승인 통합 테스트 전부 통과 | 보강 후 `markPaid clears recovery state and records the transition time` | 새 단위 테스트 |
| `markPaid` | `recoveryNextAttemptAt = null` 제거 | **구멍** | 기존 승인 통합 테스트 전부 통과 | 보강 후 `markPaid clears recovery state and records the transition time` | 새 단위 테스트 |
| `markPaid` | `updatedAt = now` → `createdAt` | **구멍** | 기존 승인 통합 테스트 전부 통과 | 보강 후 `markPaid clears recovery state and records the transition time` | 새 단위 테스트 |
| `markFailed` | `FAILED` → `PAID` | 잡힘 | 단언: expected `FAILED`, actual `PAID` | `ChargeOrderRecoveryProcessorIntegrationTest.recover_failed_marksFailedWithRawPgStatus` | 없음 |
| `markFailed` | `failureCode` 대입 제거 | 잡힘 | 단언: expected `PG_STATUS_EXPIRED`, actual null | `ChargeOrderRecoveryProcessorIntegrationTest.recover_failed_marksFailedWithRawPgStatus` | 없음 |
| `markFailed` | `recoveryNextAttemptAt = null` 제거 | **구멍** | 기존 복구 통합 테스트 전부 통과 | 보강 후 `markFailed clears the recovery schedule and records the transition time` | 새 단위 테스트 |
| `markFailed` | `updatedAt = now` → `createdAt` | **구멍** | 기존 복구 통합 테스트 전부 통과 | 보강 후 `markFailed clears the recovery schedule and records the transition time` | 새 단위 테스트 |
| `scheduleNextRecovery` | 대입 제거 | 잡힘 | 단언: expected `12:01`, actual `11:59` | `ChargeOrderRecoveryProcessorIntegrationTest.recover_unknown_reschedulesAfterOneMinute` | 없음 |
| `scheduleNextRecovery` | 입력 시각 대신 null 대입 | 잡힘 | 단언: expected `12:01`, actual null | `ChargeOrderRecoveryProcessorIntegrationTest.recover_unknown_reschedulesAfterOneMinute` | 없음 |
| `markRecoveryExhausted` | `recoveryExhaustedAt = now` 제거 | 잡힘 | 단언: expected `12:00`, actual null | `ChargeOrderRecoveryProcessorIntegrationTest.recover_unknownPaymentObjectMissingRequiredField_marksManualReview` | 없음 |
| `markRecoveryExhausted` | `recoveryNextAttemptAt = null` 제거 | 잡힘 | 단언: expected null, actual `11:59` | `ChargeOrderRecoveryProcessorIntegrationTest.recover_unknownPaymentObjectMissingRequiredField_marksManualReview` | 없음 |
| `startConfirming` | `status = :pending` → `<> :pending` | 잡힘 | 단언: update count 0 및 후보 목록 불일치 | `ChargeOrderRepositoryTest` 3건 | 없음 |
| `startConfirming` | `paymentKey`와 `clientIdempotencyKey` 저장값 교환 | 잡힘 | 단언: PG 조회 키가 client key로 바뀜 | `PgWebhookEventProcessorIntegrationTest.process_usesStoredPaymentKeyOutsideTransaction` | 없음 |
| `findRecoveryCandidateIds` | `recoveryNextAttemptAt <= :now` → `<` | 잡힘 | 단언: 정확히 1분인 후보 누락 | `ChargeOrderRepositoryTest.findRecoveryCandidateIds_appliesTimeWindowAndStableOrder` | 없음 |
| `findRecoveryCandidateIds` | `updatedAt > 24시간 경계` → `>=` | 잡힘 | 단언: 정확히 24시간인 주문이 일반 복구 후보에 추가됨 | 같은 테스트 | 없음 |
| `findRecoveryExhaustedCandidateIds` | `updatedAt <= 24시간 경계` → `<` | 잡힘 | 단언: 정확히 24시간인 소진 후보 누락 | 같은 테스트 | 없음 |
| `findRecoveryExhaustedCandidateIds` | `recoveryExhaustedAt IS NULL` → `IS NOT NULL` | 잡힘 | 단언: 예상 소진 후보 누락 | 같은 테스트 | 없음 |

### 집계

- 수동 변조: **20건**
- 기존 테스트가 실제 단언으로 잡음: **15건**
- 컨텍스트 로딩 실패로만 잡힌 약한 kill: **0건**
- 기존 테스트를 모두 통과한 구멍: **5건**
- 보강 후 새 테스트가 직접 죽인 변조: **5건**

## 3. 구멍과 추가한 테스트

신규 파일: `backend/src/test/kotlin/com/ovengers/slotkey/credit/entity/ChargeOrderTransitionMutationTest.kt`

| 추가 테스트 | 직접 죽인 변조 | 경계 고정용 보강 여부 |
| --- | --- | --- |
| `markPaid clears recovery state and records the transition time` | 실패코드 초기화 제거, 다음 복구시각 초기화 제거, 갱신시각 오염 — 3건 | 아니오 |
| `markFailed clears the recovery schedule and records the transition time` | 다음 복구시각 초기화 제거, 갱신시각 오염 — 2건 | 아니오 |

합계: **새 테스트 2건 = 직접 변조 살해 테스트 2건 + 경계 고정용 보강 0건**.

### 돈 관련 대표 사례 후보

- 변조: 승인 성공 뒤 `markPaid`가 `failureCode`와 `recoveryNextAttemptAt`을 지우지 않고 `updatedAt`도 승인 시각으로 갱신하지 않도록 변경했다.
- 기존에 못 잡은 이유: 승인 통합 테스트는 `PAID`, 잔액 증가, `TOP_UP` 원장만 단언하고 주문의 복구 메타데이터 정리와 전이 시각은 확인하지 않았다.
- 추가한 테스트: 실패 이력과 복구 예약이 남아 있는 주문에 `markPaid`를 호출해 세 필드 정리와 승인 시각을 함께 단언한다.
- 결과: 기존에 생존한 대입 변조 3건이 모두 `AssertionFailedError`로 실패했다.

## 4. 실행한 명령과 결과

### 변조별 실행

각 변조마다 관련 테스트만 아래 형식으로 강제 실행했다.

```bash
env -u SPRING_MAIL_USERNAME -u SPRING_MAIL_PASSWORD -u VERIFICATION_MAIL_FROM -u SLOTKEY_KAFKA_ENABLED \
  ./gradlew --no-daemon cleanTest test --tests '*관련테스트*'
```

- 엔티티 기존 테스트: `ChargeOrderServiceTest`, `ChargeOrderConfirmIntegrationTest`, `ChargeOrderRecoveryProcessorIntegrationTest`
- repository 기존 테스트: `ChargeOrderRepositoryTest`, `PgWebhookEventProcessorIntegrationTest`
- 보강 후 재변조: `ChargeOrderTransitionMutationTest`
- 20건 모두 실제 테스트 태스크가 실행됐으며 결과는 §2 표와 같다.
- 모든 변조 직후 원복하고 `git diff --stat`을 확인했다.

### 무효 처리한 실행 1건

첫 `markPaid.updatedAt` 변조에서 `updatedAt = createdAt` 문자열이 생성자에도 있어 문맥이 짧은 역패치가 생성자와 `markPaid`를 서로 뒤바꿨다. 즉시 diff로 발견해 두 위치를 원본으로 복구했고, 이 실행 결과는 집계에서 제외했다. 이후 함수 전체 문맥을 포함한 단일 위치 패치로 다시 실행해 기존 테스트가 통과하는 구멍임을 확인했다.

### 보강 테스트와 전체 테스트

- `./gradlew --no-daemon ktlintCheck cleanTest test --tests '*ChargeOrderTransitionMutationTest*'`
  - BUILD SUCCESSFUL, 신규 2건 통과.
- 같은 생존 변조 5건 재적용
  - 신규 테스트가 각각 `AssertionFailedError`로 실패. 모두 원복.
- `./gradlew --no-daemon ktlintCheck cleanTest test`
  - **BUILD SUCCESSFUL, 10분 53초**
  - XML 합산: **175개 클래스·1,318건·실패 0·오류 0·스킵 0**

전달받은 기준 169개 클래스·1,305건과 현재 `dev` HEAD의 차이는 이미 병합된 #147·#150의 신규 테스트 5개 클래스·11건이다. 현재 HEAD 기준선은 174개 클래스·1,316건이며, 이번 작업의 새 클래스 1개·테스트 2건이 더해져 175개·1,318건이 됐다.

## 5. 인계 보고

1. 변경 파일
   - `backend/src/test/kotlin/com/ovengers/slotkey/credit/entity/ChargeOrderTransitionMutationTest.kt`: 신규 단위 테스트 2건
   - `docs/lab/notes/mutation-testing/follow-up-154.md`: 이 보고서
2. 실행한 명령과 결과
   - §4 참조. 수동 변조 20건, 구멍 재변조 5건, ktlint, 전체 `cleanTest test` 완료.
3. 가정·미확인
   - `markPaid`의 실패코드 초기화를 관찰하기 위해 테스트에서 `FAILED` 주문에 `markPaid`를 호출했다. 현재 운영 호출부는 잠금 조회 후 `CONFIRMING` 상태를 검사하므로 이 순서를 사용하지 않는다.
   - CI와 Claude 별도 검증은 미확인이다.
   - `ChargeOrder` 전이 메서드 자체가 출발 상태를 검사해야 하는지는 아래 사용자 질문으로 남긴다.
4. 관련 결정 문서
   - [mutation-testing.md](../../../decisions/mutation-testing.md) §5 검증 결과와 “검증 중 발견한 한계·주의”

결정 문서는 검증자 담당이므로 수정하지 않았다.

## 6. 리뷰 체크리스트

- [ ] 수동 변조 20건이 각각 한 번에 하나씩 적용됐는지 확인한다.
- [ ] 잡힘 15건이 모두 컨텍스트 실패가 아니라 실제 단언 실패인지 XML에서 확인한다.
- [ ] 구멍 5건이 새 테스트의 필드 단언으로 각각 실패하는지 재변조한다.
- [ ] `markPaid` 성공 뒤 실패코드·복구시각이 null이고 `updatedAt`이 승인 시각인지 확인한다.
- [ ] `markFailed` 뒤 복구시각이 null이고 `updatedAt`이 실패 시각인지 확인한다.
- [ ] `ChargeOrder.kt`, `ChargeOrderRepository.kt`, `build.gradle`, `.github`에 최종 diff가 없는지 확인한다.
- [ ] 새 Kotlin 파일에 Lombok·`data class`·시크릿이 없는지 확인한다.
- [ ] 전체 테스트 1,318건과 ktlint를 `cleanTest`로 다시 실행한다.

## 7. 사용자 결정이 필요한 질문

- `ChargeOrder.markPaid`와 `markFailed`가 엔티티 내부에서도 출발 상태(`CONFIRMING`)를 검사하도록 할지? 현재는 `ChargeOrderConfirmTransactionService`와 `ChargeOrderRecoveryTransactionService`가 호출 전에 검사해 운영 경로는 안전하지만, public 엔티티 메서드 자체는 `FAILED → PAID` 같은 재개방을 막지 않는다. 이번 작업에서는 운영 코드를 수정하지 않았다.

## 8. 커밋 대상 파일

커밋은 하지 않았다. 사용자 확인 후 코드와 문서를 나눈다.

### 코드 커밋

- `backend/src/test/kotlin/com/ovengers/slotkey/credit/entity/ChargeOrderTransitionMutationTest.kt`

### 문서 커밋

- `docs/lab/notes/mutation-testing/follow-up-154.md` (`docs/lab/` 로컬 exclude 때문에 `git add -f` 필요)
