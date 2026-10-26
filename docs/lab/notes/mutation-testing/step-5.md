# 뮤테이션 테스트 5단계 보고: 팀원 담당 클래스 측정 (측정만)

> 작성: 2026-10-26. 구현자와 검증자가 같은 세션이므로 "자기검증"이다. 범위는 [계획](../../../plans/mutation-testing/00-mutation-testing-plan.md), 앞 단계는 [step-4](step-4.md).
> **이번 단계는 측정과 분류만 했다.** 팀원 담당 파일이라 테스트를 추가하지 않았고, 구멍 후보 목록을 담당자에게 공유하는 것으로 끝낸다. 분류는 코드를 읽어 판단한 것이며 테스트로 확정하지 않았다.

## 1. 계획 대비 정정

계획 문서에 대상으로 적은 `CreditService`는 **인터페이스**(메서드 선언뿐, 변조할 코드 없음)다. 실제 로직은 `CreditServiceImpl`(193줄)에 있어 이쪽을 측정했다.

## 2. 측정 설정

- `backend/build.gradle`의 PIT 설정에 3개 클래스와 단위 테스트 4개를 추가했다. 변이 세트는 `STRONGER`.
- 대상 테스트는 스프링 컨텍스트 없이 도는 단위 테스트로만 한정했다: `SpaceOperatingHoursPolicyTest`, `SpaceSlotAvailabilityServiceTest`, `CreditServiceImplTest`, `DoorAccessTimePolicyTest`.
- 실행: `./gradlew --no-daemon cleanTest pitest` → BUILD SUCCESSFUL, 22초(Gradle 기동 포함).

## 3. 측정 결과

| 클래스 | 담당 | 생성 | 잡힘 | 살아남음 | 비고 |
| --- | --- | ---: | ---: | ---: | --- |
| `DoorAccessTimePolicy` | 박창현 | 16 | 16 (100%) | 0 | 구멍 없음 |
| `SpaceOperatingHoursPolicy` | 김재철 | 56 | 46 | 9 | 1건은 메모리 오류(PIT가 탐지로 처리) |
| `CreditServiceImpl` | 백한비 | 36 | 28 | 8 | |
| 합계 | | 108 | 90 | 17 | |

## 4. 살아남은 17건 분류

### 구멍 후보 (6건, 4개 묶음) — 담당자 확인 필요

| 묶음 | 위치 | 내용 | 왜 구멍인가 |
| --- | --- | --- | --- |
| H1 | `CreditServiceImpl.kt:82` | `updatedRows == 0` 검사 제거 | `increase`가 0행을 갱신해도(회원 없음) `MEMBER_NOT_FOUND`를 던지는 경로를 검증하는 테스트가 없다. 환불 중 잔액이 안 늘었는데 원장만 기록될 수 있는 경로다 |
| H2 | `CreditServiceImpl.kt:186` | `balanceAfter > Int.MAX_VALUE`의 경계 변경 | 기존 오버플로 테스트는 한도를 **넘는** 경우만 확인한다. 정확히 `Int.MAX_VALUE`가 되는 환불은 허용돼야 하는데 그 경계를 확인하지 않는다 |
| H3 | `CreditServiceImpl.kt:166` | `findReservation`의 반환값을 null로 | 테스트는 원장의 금액·종류·잔액은 단언하지만 **`saved.reservation`(어느 예약의 거래인지)은 단언하지 않는다**. 환불·위약금·차감 원장이 예약과 연결되지 않아도 통과한다 |
| H4 | `CreditServiceImpl.kt:159, 165` | `requireNotNull(memberId/reservationId)` 제거 | null ID가 들어오면 `IllegalArgumentException`으로 거절하는 동작을 확인하지 않는다 (낮은 위험) |
| H5 | `SpaceOperatingHoursPolicy.kt:38` | `date ?: throw NullPointerException()` 제거 | 코드 주석이 "date가 null이면 NPE"라고 명시한 동작인데 테스트가 없다 (낮은 위험) |

### 동치·도달 불가 (3건)

| 위치 | 내용 | 이유 |
| --- | --- | --- |
| `SpaceOperatingHoursPolicy.kt:28, 29, 34` | `return emptyList()`를 `emptyList` 반환으로 변조 | 변조 전후가 같은 값이라 동작이 달라지지 않는다 |
| `SpaceOperatingHoursPolicy.kt:45` (2건) | `currentEnd == currentStart` 조건 변조 | `currentEnd`는 항상 `currentStart + 30분`이라 두 값이 같을 수 없다(24시간 순환 제외). 도달 불가 조건일 가능성이 높다 |

### Kotlin 생성 코드 노이즈 (6건)

`CreditServiceImpl.kt:150, 160, 166`(`checkNotNullExpressionValue`), `SpaceOperatingHoursPolicy.kt:49 ×2, 54`(`checkNotNull`). Kotlin 컴파일러가 널 안전성 때문에 넣은 호출이라 비즈니스 규칙이 아니다.

## 5. 인계 보고

1. 변경 파일: `backend/build.gradle`(PIT 대상 3개 클래스·테스트 4개 추가), 이 보고서. 운영 코드와 기존 테스트는 변경하지 않았다.
2. 실행한 명령과 결과: `cleanTest pitest` BUILD SUCCESSFUL. 전체 테스트는 이 단계에서 실행하지 않았다(운영 코드·테스트 변경 없음).
3. 가정·미확인
   - 구멍 후보는 코드를 읽어 분류했고 테스트나 수동 변조로 확정하지 않았다. H3(원장의 예약 연결)가 가장 의미가 크므로 담당자가 확인하는 것이 좋다.
   - 담당자(박창현·김재철·백한비)에게는 아직 공유하지 않았다.
   - `SpaceOperatingHoursPolicy`의 메모리 오류 1건은 PIT 정책상 탐지로 처리된다.
4. 관련 결정 문서: [mutation-testing.md](../../../decisions/mutation-testing.md)

## 6. 리뷰 체크리스트

- [ ] `build.gradle` 변경이 대상 3개·테스트 4개 추가뿐이다
- [ ] 팀원 담당 파일(운영 코드·기존 테스트)을 수정하지 않았다
- [ ] 구멍 후보 5개 묶음이 코드와 맞는지 담당자가 확인한다
- [ ] `CreditService`가 인터페이스라 `CreditServiceImpl`로 측정한 정정이 타당하다

## 7. 사용자 결정이 필요한 질문

- 구멍 후보를 담당자에게 어떻게 공유할지(이슈 코멘트, 슬랙). 테스트를 직접 추가할지는 담당자 확인 뒤에 정한다.
- 이 단계 결과를 결정 문서 §5 누적 성과에 합산할지. 구멍 후보가 확정되지 않았으므로 "후보 6건"으로 따로 적고 구멍 개수에는 넣지 않는 것을 권장한다.
