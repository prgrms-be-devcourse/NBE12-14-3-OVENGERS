# 뮤테이션 테스트 3단계 보고: `PricingService` 확장

> 작성: 2026-10-26. 구현자와 검증자가 같은 세션이므로 "자기검증"이다. 범위는 [계획](../../../plans/mutation-testing/00-mutation-testing-plan.md), 이유는 [결정 문서](../../../decisions/mutation-testing.md), 앞 단계는 [step-2](step-2.md).

## 1. 이번 단계에서 한 일

`build.gradle`의 PIT 설정에 `PricingService`와 그 단위 테스트(`PricingServiceTest`, Java)를 추가하고 `STRONGER` 변이 세트로 측정했다. 운영 코드와 테스트는 바꾸지 않았다.

## 2. 측정 결과

| 대상 | 생성 | 잡힘 | 살아남음 | 커버리지 없음 |
| --- | --- | --- | --- | --- |
| `PricingService` | 4 | 4 (100%) | 0 | 0 |
| 전체(정책 2개 + `PricingService`) | 43 | 43 (100%) | 0 | 0 |

라인 커버리지(대상 클래스) 35/35, 테스트 강도 100%, 실행 18초(Gradle 기동 포함).

| 위치 | 뮤턴트 | 죽인 테스트 |
| --- | --- | --- |
| `calculateSlotCount` | 나눗셈 → 곱셈 | `calculateSlotCount_twoHours_returnsFourSlots` |
| `calculateSlotCount` | 반환값 → 0 | `calculateSlotCount_twoHours_returnsFourSlots` |
| `calculateTotalAmount` | 곱셈 → 나눗셈 | `calculateTotalAmount_zeroSlots_returnsZero` |
| `calculateTotalAmount` | 반환값 → 0 | `calculateTotalAmount_multipliesPriceAndSlotCount` |

## 3. 해석

- **발견한 구멍: 0개.** 클래스가 21줄이고 연산이 나눗셈 1개, 곱셈 1개뿐이라 기존 테스트 5개가 모두 잡았다. 테스트를 추가하지 않았다.
- 포트폴리오에서는 "구멍을 못 찾은 클래스"도 도구가 테스트가 충분함을 확인해 준 사례로 쓸 수 있다. 기존 테스트에서 고르는 대표 사례 후보로 적는다. 다만 뮤턴트가 4개뿐이라 근거의 무게는 작다.
- PIT는 뮤턴트마다 죽인 테스트를 하나만 기록한다. 곱셈 → 나눗셈 뮤턴트는 `zeroSlots` 테스트가 0으로 나누는 예외로 죽였다고 기록됐지만, `5000 × 4` 테스트도 값이 달라져 죽였을 것이다.
- 관찰(수정하지 않음): `calculateTotalAmount`는 `Int * Int`라 이론상 오버플로가 가능하다. 변이 도구가 찾는 종류의 문제가 아니며, 요금이 100원 단위이고 하루 슬롯이 48개 이하라 실무 영향은 낮다. 필요하면 별도로 판단한다.
- 음수 구간(종료 < 시작)은 `calculateSlotCount`가 음수를 반환한다. 호출부 `ReservationTimePolicy.validate`가 앞서 막는다. 이 단위에서 검증하지 않는다.

## 4. 인계 보고

1. 변경 파일: `backend/build.gradle`(`targetClasses`, `targetTests`에 `PricingService` 추가), 이 보고서
2. 실행한 명령과 결과: `./gradlew --no-daemon pitest` → BUILD SUCCESSFUL, 43개 중 43개 잡힘. 운영 코드·테스트 변경이 없어 전체 테스트는 이 단계에서 실행하지 않았다
3. 가정·미확인: 없음. 4단계의 `Reservation`/`ChargeOrder`는 서비스·통합 테스트가 대부분이라 단위 테스트 유무 확인이 필요하다
4. 관련 결정 문서: [mutation-testing.md](../../../decisions/mutation-testing.md)

## 5. 리뷰 체크리스트

- [ ] `build.gradle` 변경이 `PricingService`와 `PricingServiceTest` 추가뿐이다
- [ ] 운영 코드와 기존 테스트를 수정하지 않았다
- [ ] `targetTests`가 `PricingServiceTest` 한 클래스로 한정돼 있다(서비스·통합 테스트가 같이 돌지 않는다)

## 6. 사용자 결정이 필요한 질문

없음. 다음은 4단계(상태 전이 확장)이며, `Reservation`·`ChargeOrder` 단위 테스트 유무를 먼저 확인한다.
