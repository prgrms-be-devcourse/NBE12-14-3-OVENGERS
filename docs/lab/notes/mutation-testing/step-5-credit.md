# 뮤테이션 테스트 5단계 후속 구현자 보고: `CreditServiceImpl`

> 작성: 2026-10-26. 구현자는 Codex이며 검증은 Claude가 별도 세션에서 수행한다. 측정 단계는 [step-5](step-5.md), 결정 근거와 누적 검증 결과는 [mutation-testing.md](../../../decisions/mutation-testing.md)에 있다.

## 1. 이번 작업에서 한 일

1. `CreditServiceImpl`의 구멍 후보 H1~H3를 신규 테스트 없이 한 번에 하나씩 수동 변조했다.
2. H1 1건, H2 1건, H3의 `charge`·`refund`·`penalize` 3건이 기존 `CreditServiceImplTest`를 모두 통과해 후보 H1~H3를 구멍으로 확정했다.
3. 새 Kotlin 테스트 파일에 6건을 추가했다. 수동 변조를 직접 죽이는 테스트는 5건이고, H2의 초과 쪽 경계를 고정하는 보강은 1건이다.
4. 같은 수동 변조 5건을 재적용해 새 테스트가 모두 실제 단언 실패로 잡는 것을 확인하고 즉시 원복했다.
5. 신규 테스트를 PIT 대상에 일시적으로 포함해 재측정한 뒤 `build.gradle`을 원복했다.

운영 코드, 기존 테스트, 결정 문서, 계획 문서는 최종 변경하지 않았다. H4와 다른 팀원 담당 클래스는 작업하지 않았다.

## 2. 후보별 수동 변조 결과

| 후보 | 변조 내용 | 기존 `CreditServiceImplTest` | 실패 원인이 단언인지 | 새 테스트 결과 | 판정·조치 |
| --- | --- | --- | --- | --- | --- |
| H1 | `refund`의 `if (updatedRows == 0)`을 `if (false)`로 변경 | 통과 | 해당 없음 | `refund rejects a missing balance row before saving a transaction`이 `AssertionFailedError`로 실패: expected `MEMBER_NOT_FOUND`, actual `RESERVATION_NOT_FOUND` | 구멍 확정, 새 테스트 추가 |
| H2 | `balanceAfter > Int.MAX_VALUE`를 `>=`로 변경 | 통과 | 해당 없음 | `refund allows a balance exactly equal to the integer maximum`이 `AssertionFailedError`로 실패: 허용돼야 할 경계에서 `BusinessException` 발생 | 구멍 확정, 경계 테스트 추가 |
| H3-charge | `charge`가 저장할 원장에 `reservation` 대신 null 전달 | 통과 | 해당 없음 | `charge ledger references the requested reservation`이 AssertJ `AssertionError`로 실패: actual null | 구멍 확정, 동일 객체 단언 추가 |
| H3-refund | `refund`가 저장할 원장에 `reservation` 대신 null 전달 | 통과 | 해당 없음 | `refund ledger references the requested reservation`이 AssertJ `AssertionError`로 실패: actual null | 구멍 확정, 동일 객체 단언 추가 |
| H3-penalize | `penalize`가 저장할 원장에 `reservation` 대신 null 전달 | 통과 | 해당 없음 | `penalty ledger references the requested reservation`이 AssertJ `AssertionError`로 실패: actual null | 구멍 확정, 동일 객체 단언 추가 |

H3 수동 변조는 Kotlin 타입 검사를 통과하면서 각 호출 경로를 따로 검증하기 위해, 해당 호출 인자만 null로 바꾸고 `saveTransaction`의 실험용 파라미터 타입만 `Reservation?`로 넓혔다. 매 실행 뒤 두 변경을 함께 원복했다. 컴파일 오류나 컨텍스트 로딩 실패를 kill로 세지 않았다.

### 집계

- 확정한 구멍 후보: **3개(H1~H3)**
- 수동 변조 사례: **5건(H1 1건, H2 1건, H3 경로별 3건)**
- 기존 테스트가 잡은 변조: **0건**
- 기존 테스트를 통과한 수동 변조: **5건**
- 보강 후 새 테스트가 실제 단언으로 잡은 변조: **5건**
- 약한 kill: **0건**

## 3. 확정된 구멍과 추가한 테스트

신규 파일: `backend/src/test/kotlin/com/ovengers/slotkey/credit/service/CreditServiceImplMutationTest.kt`

| 추가 테스트 | 검증 내용 | 분류 |
| --- | --- | --- |
| `refund rejects a missing balance row before saving a transaction` | 잔액 증가가 0행이면 `MEMBER_NOT_FOUND`이고 원장을 저장하지 않음 | H1 변조 직접 살해 |
| `refund allows a balance exactly equal to the integer maximum` | 환불 후 잔액이 정확히 `Int.MAX_VALUE`이면 성공 | H2 변조 직접 살해 |
| `refund rejects a balance one greater than the integer maximum` | `Int.MAX_VALUE + 1`이면 `VALIDATION_FAILED`이고 잔액 증가·원장 저장 없음 | 경계 고정용 보강 |
| `charge ledger references the requested reservation` | 결제 차감 원장이 요청 예약과 동일 객체로 연결됨 | H3-charge 변조 직접 살해 |
| `refund ledger references the requested reservation` | 환불 원장이 요청 예약과 동일 객체로 연결됨 | H3-refund 변조 직접 살해 |
| `penalty ledger references the requested reservation` | 위약금 원장이 요청 예약과 동일 객체로 연결됨 | H3-penalize 변조 직접 살해 |

합계: **새 테스트 6건 = 직접 변조 살해 5건 + 경계 고정용 보강 1건**.

### 대표 사례 후보: H3 원장-예약 연결

- 변조: `charge`, `refund`, `penalize`가 생성하는 `CreditTransaction.reservation`을 null로 저장했다.
- 기존에 못 잡은 이유: 기존 테스트는 저장된 원장의 `amount`, `type`, `balanceAfter`만 확인하고 예약 연결은 확인하지 않았다.
- 추가한 테스트: 세 경로 각각에서 캡처한 원장의 `reservation`이 요청한 예약 mock과 같은 객체인지 `isSameAs`로 단언했다.
- 결과: 세 경로의 수동 변조가 각각 AssertJ `AssertionError`로 실패했고, PIT의 `findReservation` null 반환 뮤턴트도 KILLED로 바뀌었다.
- 의미: 금액 증감 자체는 기존 테스트가 확인했으나, 거래 원장이 어느 예약에서 발생했는지 추적하는 메타데이터가 유실될 수 있었다. 즉시 잔액 결과가 틀리는 문제로 과장하지 않고 장부 추적성 구멍으로 분류한다.

## 4. PIT 재측정

`build.gradle`의 `targetTests`가 기존 `CreditServiceImplTest`를 정확한 클래스명으로만 지정해 첫 재실행에서는 신규 테스트가 선택되지 않았다. 이 실행은 36개 중 28개 KILLED·8개 생존으로 전과 같았으며 개선 측정으로 사용하지 않았다. 신규 테스트 클래스를 `targetTests`에 일시적으로 추가해 다시 실행하고 즉시 원복했다.

| 대상 | 전 | 후 | 변화 |
| --- | ---: | ---: | ---: |
| 생성 뮤턴트 | 36 | 36 | 0 |
| KILLED | 28 | 31 | +3 |
| SURVIVED | 8 | 5 | -3 |

| 후보 | PIT 뮤턴트 | 재측정 결과 | PIT가 기록한 죽인 테스트 |
| --- | --- | --- | --- |
| H1, `refund`:82 | equality 조건을 false로 제거 | KILLED | `refund rejects a missing balance row before saving a transaction` |
| H2, `validateBalanceAfterRefund`:186 | 비교 경계 변경 | KILLED | `refund allows a balance exactly equal to the integer maximum` |
| H3, `findReservation`:166 | 반환값을 null로 변경 | KILLED | `charge ledger references the requested reservation` |

PIT는 뮤턴트마다 죽인 테스트를 하나만 기록한다. H3의 환불·위약금 테스트도 각 경로의 수동 변조를 직접 잡았으므로 불필요하다고 해석하지 않는다.

남은 5건은 이번 범위에서 제외한 H4의 null ID 검사 제거 2건과 Kotlin 생성 코드의 `checkNotNullExpressionValue` 제거 3건이다. H1~H3의 비즈니스 뮤턴트는 남지 않았다.

## 5. 실행한 명령과 결과

### 수동 변조 전·후

각 변조마다 아래 명령으로 기존 테스트 또는 신규 테스트만 강제 실행했다.

```bash
env -u SPRING_MAIL_USERNAME -u SPRING_MAIL_PASSWORD -u VERIFICATION_MAIL_FROM -u SLOTKEY_KAFKA_ENABLED \
  ./gradlew --no-daemon cleanTest test --tests '*CreditServiceImplTest*'

env -u SPRING_MAIL_USERNAME -u SPRING_MAIL_PASSWORD -u VERIFICATION_MAIL_FROM -u SLOTKEY_KAFKA_ENABLED \
  ./gradlew --no-daemon cleanTest test --tests '*CreditServiceImplMutationTest*'
```

- 변조 전 기존 테스트 실행: 5건 모두 BUILD SUCCESSFUL, 구멍 확정.
- 보강 뒤 재변조: 5건 모두 해당 신규 테스트가 `AssertionFailedError` 또는 AssertJ `AssertionError`로 실패.
- 매 변조 뒤 `git diff --stat -- backend/src/main`으로 운영 코드 원복을 확인했다.

### 신규 테스트·PIT·전체 테스트

- `./gradlew --no-daemon ktlintCheck cleanTest test --tests '*CreditServiceImplMutationTest*'`
  - BUILD SUCCESSFUL, 신규 6건 통과.
- `./gradlew --no-daemon cleanTest pitest`
  - 신규 테스트가 정확한 `targetTests` 목록에서 제외된 사실 확인: `CreditServiceImpl` 36개 중 28개 KILLED·8개 생존.
- 신규 테스트를 `targetTests`에 일시 추가 후 같은 PIT 명령
  - BUILD SUCCESSFUL, 14초.
  - `CreditServiceImpl` 36개 중 31개 KILLED·5개 생존.
  - 일시 변경한 `build.gradle`은 즉시 원복.
- `env -u SPRING_MAIL_USERNAME -u SPRING_MAIL_PASSWORD -u VERIFICATION_MAIL_FROM -u SLOTKEY_KAFKA_ENABLED ./gradlew --no-daemon ktlintCheck cleanTest test`
  - **BUILD SUCCESSFUL, 9분 50초**
  - XML 합산: **175개 클래스·1,322건·실패 0·오류 0·스킵 0**

현재 기준선 174개 클래스·1,316건에 이번 신규 클래스 1개·테스트 6건이 더해져 175개·1,322건이 됐다.

## 6. 인계 보고

1. 변경 파일
   - `backend/src/test/kotlin/com/ovengers/slotkey/credit/service/CreditServiceImplMutationTest.kt`: 신규 단위 테스트 6건
   - `docs/lab/notes/mutation-testing/step-5-credit.md`: 이 구현자 보고서
2. 실행한 명령과 결과
   - §5 참조. 수동 변조 5건, 재변조 5건, PIT 재측정, ktlint, 전체 `cleanTest test`를 실행했다.
3. 가정·미확인
   - `CreditTransaction.reservation`은 nullable이지만 예약 결제·환불·위약금 경로에서는 요청 예약과 연결돼야 한다는 현재 구현 의도를 테스트 계약으로 보았다.
   - H4(null ID)는 사용자 지시에 따라 미확인이다.
   - CI와 Claude 별도 검증은 미확인이다.
   - 신규 테스트를 PIT 기본 대상에 영구 등록할지는 아래 사용자 질문으로 남긴다.
4. 관련 결정 문서
   - [mutation-testing.md](../../../decisions/mutation-testing.md) §5 검증 결과와 “검증 중 발견한 한계·주의”

결정 문서와 계획 문서는 검증자 담당이므로 수정하지 않았다.

## 7. 리뷰 체크리스트

- [ ] H1~H3 수동 변조 5건이 각각 한 번에 하나씩 적용됐는지 확인한다.
- [ ] 기존 테스트가 변조 5건을 모두 통과했는지 확인한다.
- [ ] 신규 테스트 5건이 대응 수동 변조를 실제 단언 실패로 잡는지 확인한다.
- [ ] H2 초과 쪽 테스트 1건을 직접 살해가 아닌 경계 고정용 보강으로 집계했는지 확인한다.
- [ ] H3 세 테스트가 각 거래 원장의 예약을 `isSameAs`로 확인하는지 검토한다.
- [ ] PIT의 H1·H2·H3 뮤턴트가 KILLED이고 나머지 5건이 H4·Kotlin 생성 코드인지 XML에서 확인한다.
- [ ] `CreditServiceImpl.kt`, 기존 `CreditServiceImplTest.kt`, `build.gradle`, `.github`에 최종 diff가 없는지 확인한다.
- [ ] 새 Kotlin 파일에 Lombok·`data class`·시크릿이 없는지 확인한다.
- [ ] 전체 테스트 1,322건과 ktlint를 `cleanTest`로 다시 실행한다.

## 8. 사용자 결정이 필요한 질문

- 신규 테스트를 이후 PIT 실행에서도 자동 포함하도록 `build.gradle`의 `targetTests`에 `CreditServiceImplMutationTest`를 추가할지? 현재 설정은 기존 테스트 클래스명을 정확히 지정해, 설정을 그대로 두면 신규 테스트는 일반 `test`에는 포함되지만 PIT에서는 제외되어 `CreditServiceImpl` 결과가 다시 28 KILLED·8 생존으로 보인다. 이번 작업에서는 설정을 영구 변경하지 않았다.

## 9. 커밋 대상 파일

커밋·push는 하지 않았다. 사용자 확인 후 코드와 문서를 나눈다.

### 코드 커밋

- `backend/src/test/kotlin/com/ovengers/slotkey/credit/service/CreditServiceImplMutationTest.kt`

### 문서 커밋

- `docs/lab/notes/mutation-testing/step-5-credit.md` (`docs/lab/` 로컬 exclude 때문에 `git add -f` 필요)
