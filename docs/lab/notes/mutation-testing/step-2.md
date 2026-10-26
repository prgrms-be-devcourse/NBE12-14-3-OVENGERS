# 뮤테이션 테스트 2단계 보고: 살아남은 뮤턴트 분류와 구멍 보강 (정책 2개)

> 작성: 2026-10-26. 구현자와 검증자가 같은 세션이므로 "자기검증"이다. 범위는 [계획](../../../plans/mutation-testing/00-mutation-testing-plan.md), 이유는 [결정 문서](../../../decisions/mutation-testing.md), 앞 단계는 [step-1](step-1.md).

## 1. 이번 단계에서 한 일

1. 변이 세트를 PIT 기본값(`DEFAULTS`)에서 `STRONGER`로 올렸다. 기본값에서는 살아남은 뮤턴트가 0개라 구멍이 보이지 않았다.
2. 살아남은 뮤턴트를 분류했다(구멍 / 동치·도달 불가).
3. 구멍 1개를 죽이는 테스트와 주변 경계를 고정하는 테스트를 새 파일로 추가했다.

## 2. 측정 결과 (대상: `ReservationTimePolicy`, `ReservationRefundPolicy`, 단위 테스트만)

| 시점 | 변이 세트 | 생성 | 잡힘 | 살아남음 | 커버리지 없음 | 테스트 강도 |
| --- | --- | --- | --- | --- | --- | --- |
| step-1 | DEFAULTS | 24 | 23 (96%) | 0 | 1 | 100% |
| 2단계 시작 | STRONGER | 43 | 39 (91%) | 2 | 2 | 95% |
| 구멍 보강 후 | STRONGER | 43 | 40 (93%) | 1 | 2 | 98% |
| 도달 불가 조건 제거 후 | STRONGER | 39 | 39 (100%) | 0 | 0 | 100% |

## 3. 분류

| # | 위치 | 뮤턴트 | 분류 | 조치 |
| --- | --- | --- | --- | --- |
| 1 | `ReservationTimePolicy.kt:44` `validate`의 `withinHours` | 조건 제거(`RemoveConditionalMutator_EQUAL_IF`) | **구멍** | 테스트 추가, 죽음 |
| 2–4 | `ReservationTimePolicy.kt:66` `validateExtension`의 `newEnd == LocalTime.MIDNIGHT && closing != MIDNIGHT` | 조건 제거 3건(survived 1, no coverage 2) | **도달 불가 코드(동치 뮤턴트)** | 사용자 결정(2026-10-26)으로 조건 제거. 별도 커밋 |

### 구멍 #1 (대표 사례 후보)

- 상황: 기존 `validate_outsideOperatingHours_throwsInvalidTime`은 시작이 오픈 전(08:30~09:00)인 경우만 검증한다. 종료가 마감을 넘는 경우(`!end.isAfter(closing)`)를 검증하는 테스트가 없어서, 그 검사를 지워도 모든 테스트가 통과했다.
- 추가한 테스트 4개(새 파일 `ReservationTimePolicyBoundaryTest.kt`): 종료가 마감을 넘으면 예외(21:30~22:30), 종료가 마감과 같으면 통과(21:30~22:00), 시작이 오픈과 같으면 통과(09:00~09:30), 시작이 오픈 전이고 종료는 운영시간 안이면 예외(08:30~09:30).
- 확인: 추가 후 해당 뮤턴트가 `ReservationTimePolicyBoundaryTest.validate_endAfterClosing_throwsInvalidTime`에 의해 죽는 것을 PIT 보고서(`killingTest`)로 확인했다. 이 구멍을 메우는 데는 운영 코드를 수정하지 않았다.
- 한계: 4개 중 PIT가 `killingTest`로 기록한 것은 `validate_endAfterClosing_throwsInvalidTime` 하나뿐이다(PIT는 뮤턴트마다 죽인 테스트를 하나만 기록하므로 나머지가 아무것도 못 죽인다는 증명은 아니다). 나머지 3개는 마감·오픈 경계 값을 고정하는 보강 테스트로 추가했다.
- 의미: 운영시간 마감 이후 예약이 허용되는 버그가 생겨도 기존 테스트는 잡지 못했을 것이다. 라인 커버리지는 이미 100%였다.

### 도달 불가 코드 #2–4

- `validateExtension`에서 이 줄에 도달하려면 앞 검사를 모두 통과해야 한다: `newEndTime`이 `currentEndTime`보다 늦고, 둘의 날짜가 같다. 그런데 `newEnd == 00:00`이면 같은 날짜의 `currentEndTime`은 00:00보다 이르거나 같아야 하는데, 같은 날짜의 `LocalDateTime`은 00:00 이상이므로 불가능하다. 따라서 `newEnd == MIDNIGHT`는 항상 거짓이다(코드를 읽어 도출한 것이고, 별도 테스트로 증명하지는 않았다).
- 이 분기에 대한 `survived`(조건을 false로 바꿔도 동작이 같음)와 `no coverage`(해당 분기 미도달)가 모두 이 해석과 일치한다.
- 조치: 사용자 확인 후 `if (newEnd.isAfter(closing) || (newEnd == LocalTime.MIDNIGHT && closing != LocalTime.MIDNIGHT))`에서 뒤쪽 조건을 지웠다. 동작은 같다. `closingTime`이 없으면 호출부가 `LocalTime.MAX`를 넘기므로 자정 마감은 이 경로로 들어오지 않는다. 제거 후 정책 테스트·`ReservationExtendServiceTest`·`SpaceSlotAvailability*` 테스트 통과, ktlint 통과, PIT 39개 중 39개 잡힘.
- 다른 파일(`ReservationOccupiedSlotAdapter.kt:51`)에 비슷한 자정 조건이 있으나 이번 범위가 아니므로 건드리지 않았다. 필요하면 별도 확인한다.

## 4. 인계 보고

1. 변경 파일: `ReservationTimePolicy.kt`(도달 불가 조건 1개 제거), `backend/build.gradle`(`mutators = ['STRONGER']` 한 줄), `backend/src/test/kotlin/.../reservation/policy/ReservationTimePolicyBoundaryTest.kt`(신규, 테스트 4개), 이 보고서
2. 실행한 명령과 결과:
   - `./gradlew --no-daemon ktlintCheck test --tests '*reservation.policy*' pitest`: BUILD SUCCESSFUL, 정책 테스트 통과, PIT 43개 중 40개 잡힘
   - 전체 `./gradlew test`: 처음에는 Docker가 없어 1,281건 중 532건이 Testcontainers 로딩 실패로 실패했다(환경 문제). Docker를 켠 뒤 다시 실행해 165개 클래스, 1,281건, 실패 0·오류 0·스킵 0, BUILD SUCCESSFUL(약 9분). 새 테스트 4개 포함. CI 결과는 별도로 확인한다
3. 가정·미확인: 도달 불가 해석(코드 읽기 기반), `STRONGER` 세트를 이후 클래스에도 쓸지(노이즈 양은 클래스마다 다름)
4. 관련 결정 문서: [mutation-testing.md](../../../decisions/mutation-testing.md)

## 5. 리뷰 체크리스트

- [ ] 새 테스트는 새 파일이고 기존 테스트 파일을 수정하지 않았다. 운영 코드 변경은 도달 불가 조건 제거 1건뿐이다
- [ ] 각 테스트가 하나의 경계만 확인한다(마감 초과, 마감 일치, 오픈 일치, 오픈 전 시작)
- [ ] `STRONGER`로 바꾼 이유(기본 세트로는 구멍 0개)와 영향(뮤턴트 24 → 43, 실행 시간 비슷)을 이해했다
- [ ] 도달 불가 해석이 맞는지 `validateExtension`의 앞 검사 순서로 확인했다
- [ ] 로컬 전체 테스트 통과(Docker 켠 상태)를 CI 결과로도 확인한다

## 6. 사용자 결정

- 도달 불가 조건 제거: 이 브랜치에서 적용(확정)
- 이후 클래스에도 `STRONGER` 사용(확정)
