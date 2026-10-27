---
title: 공간·감사·통계 핵심 로직 PIT 선정과 생존 변이 분류
type: 테스트
status: 검토 완료
date: 2026-10-27
tags: [testing, mutation-testing, pitest, space, audit, stats]
related: [../plans/mutation-testing/00-mutation-testing-plan.md, ../decisions/mutation-testing.md]
---

# 공간·감사·통계 핵심 로직 PIT 선정과 생존 변이 분류

> 이슈 [#191](https://github.com/prgrms-be-devcourse/NBE12-14-3-OVENGERS/issues/191)(상위 #185).
> PIT 전체 도입 계획·결정은 [계획](../plans/mutation-testing/00-mutation-testing-plan.md)과
> [결정](../decisions/mutation-testing.md)을 따르고 여기에 복사하지 않는다.
> 이 문서는 space·audit·stats 세 도메인으로 확장한 선택·실행·분류 결과다. Codex attempt 1 리뷰와
> fix1 재검토(로컬 `.agents/04_CODE_REVIEWS/261027_1312_issue191_pit_scope_review_attempt1.md`,
> `.agents/04_CODE_REVIEWS/261027_1323_issue191_pit_scope_review_fix1.md`)의 보완 R1/R3을 반영했다.
> attempt 1 제출본과 fix1 제출본은 각각 `steps/02-fix/1/evidence/00_attempt1_original/`,
> `steps/03-fix/2/evidence/00_round1_original/`에 보존했다(둘 다 ignored 경로).

## 0. 요약

- 이전 기본 PIT 설정의 세 도메인 대상은 `SpaceOperatingHoursPolicy` 하나뿐이었다. 이번에 **새 소스 패턴 9개**를
  추가했다: `Space`, `Space$Companion`, `AdminSpaceService`, `SpaceQueryService`,
  `SpaceSlotAvailabilityService`, `AuditLogService`, `AuditLogQueryService`, `RankingPeriodType*`,
  `SpaceRankingAggregate`. (`SpaceOperatingHoursPolicy`는 이미 대상이라 새 추가로 세지 않는다.)
- 최종 PIT(전체 기본 union)는 **483 변이 = KILLED 423 / SURVIVED 47 / NO_COVERAGE 12 / TIMED_OUT 1**,
  종료 코드 0, `Total : 36 seconds`. PIT 콘솔이 `Killed`로 표시하는 424는 TIMED_OUT 1을
  detected에 포함한 값이므로 명시적 KILLED 423과 분리한다.
- 세 도메인 몫(최종): **360 변이 = KILLED 321 / SURVIVED 38 / TIMED_OUT 1 / NO_COVERAGE 0**.
- #187 대표 변이(`SpaceQueryService.isNotHalfHourAligned`)의 `%`→`*` MathMutator가
  원 감사 **SURVIVED → 최종 KILLED**로 바뀌었다.
- 보강 테스트(총 4파일 13건)로 세 도메인 SURVIVED를 50(시작) → 43(attempt 1) → 40(fix1) → 38(fix2)로 줄였다.
  남은 38 SURVIVED + 1 TIMED_OUT은 아래에서 근거별로 분류한다.

## 1. 선정과 제외 (비용/의미 대조)

이전 감사(2026-10-27, `...1051.../mutations.xml`)의 9개 소스 패턴을 검토했다.

| 소스 패턴 | 판단 | 근거 |
| --- | --- | --- |
| `space.policy.SpaceOperatingHoursPolicy` | 유지(이미 포함) | 운영시간 슬롯 분할. 기존 대상 테스트 존재 |
| `space.entity.Space` | 추가 | 수정 시 가격 version·운영시간 검증 분기 |
| `space.entity.Space$Companion` | 추가 | `validateOperatingHours`/`validatePricePerSlot`/`isHalfHourBoundary` 검증 로직. 상위 `Space` 패턴만으로는 변이되지 않음(원 XML에 `Space$Companion` 부재로 확인) |
| `space.service.AdminSpaceService` | 추가 | 생성/수정 검증·감사 로그·후보 운영시간 계산 |
| `space.service.SpaceQueryService` | 추가 | 가격·시간 필터 검증과 점유 제외 |
| `space.service.SpaceSlotAvailabilityService` | 추가 | 슬롯 가용성 계산 |
| `audit.service.AuditLogService` | 추가 | 직렬화·필수 인자 처리 |
| `audit.service.AuditLogQueryService` | 추가 | 날짜 반개구간 변환·고정 정렬 |
| `stats.entity.RankingPeriodType*` | 추가 | enum별 `startOf`/`endOf` 구현 |
| `stats.repository.SpaceRankingAggregate` | 추가 | 산술 값 클래스(`isAllZero`). SQL/DB 변이 주장 아님 |

제외: Stats 랭킹 조회·확정·백필 서비스와 SQL 문자열 전체, 컨트롤러·리포지토리·DTO·설정,
통합 테스트(Testcontainers) 의존 코드, 동시성/트랜잭션 검증. 점수 게이트·CI 편입·유료 Arcmutate
플러그인은 도입하지 않았다.

## 2. 설정 변경 (backend/build.gradle)

- `targetClasses`에 새 소스 패턴 9개(`Space$Companion` 포함)를 추가했다.
- `targetTests`에 대응 단위 테스트(`SpaceTest`(Java), `AdminSpaceServiceTest`,
  `SpaceQueryServiceTest`, `AuditLogQueryServiceTest`, `AuditLogServiceTest`(Java),
  `RankingPeriodTypeTest`, `SpaceRankingAggregateTest`)와 보강 테스트 4파일을 추가했다.
- 기존 `ReservationTimePolicy`·`ReservationRefundPolicy`·`PricingService`·`ChargeOrder`·
  `CreditServiceImpl`·`DoorAccessTimePolicy` 항목과 `SpaceOperatingHoursPolicy`·
  `SpaceSlotAvailabilityServiceTest` 항목, `STRONGER`·`threads=4`·`junit5PluginVersion`·
  `outputFormats`·`timestampedReports`·`reportDir`는 문자열 그대로 보존했다.
- 새 플러그인·전역 exclude·점수 임계값·kotlin 필터는 추가하지 않았다.

## 3. 재현 명령과 실행 메타데이터

모든 Gradle 실행은 `backend`에서 다음 접두부를 사용했다.

```bash
env -u SPRING_MAIL_USERNAME -u SPRING_MAIL_PASSWORD \
    -u VERIFICATION_MAIL_FROM -u SLOTKEY_KAFKA_ENABLED \
    JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew <task>
```

fix2(최종) 실행 메타데이터. argv·cwd·env 접두부·JDK·시각·실제 종료 코드를 보존했다.

| 실행 | argv | cwd | JDK | start | end | exit | raw 요약 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 변경 테스트 | `./gradlew test --console=plain --tests '*AdminSpaceServiceMutationTest'` | `backend` | `openjdk 21.0.12.1` | 2026-10-27T13:25:49+09:00 | 2026-10-27T13:25:57+09:00 | 0 | 5 tests / 0/0/0 |
| 최종 PIT | `./gradlew pitest --console=plain` | `backend` | `openjdk 21.0.12.1` | 2026-10-27T13:26:16+09:00 | 2026-10-27T13:26:54+09:00 | 0 | 483 생성, KILLED 423 / SURVIVED 47 / NO_COVERAGE 12 / TIMED_OUT 1, `Total : 36 seconds` |
| ktlint | `./gradlew ktlintTestSourceSetCheck` | `backend` | `openjdk 21.0.12.1` | — | — | 0 | 변경 Kotlin 통과 |

attempt 1(선택 9클래스 100건 exit 0)과 fix1의 AuditLogServiceMutationTest 2건은 그 회차 raw XML로
확보되어 재실행하지 않았다(현재 소스와 바이트 동일). fix2에서 바뀐 파일은
`AdminSpaceServiceMutationTest`(+2 테스트)뿐이므로 그 클래스만 재실행하고 최종 PIT를 다시 돌렸다.

## 4. 생존 분류 요약 (최종)

세 도메인 360변이 = KILLED 321 / SURVIVED 38 / TIMED_OUT 1 / NO_COVERAGE 0.
(시작 상태는 SURVIVED 50 **과** NO_COVERAGE 2 **를 별개로** 집계하며, 50에는 2가 포함되지 않는다.)

| 분류 | 개수 | 의미 |
| --- | --- | --- |
| `kotlin_generated_nonnull_contract` | 11 | 반환값이 API 계약상 non-null인 식(`orElseThrow`·`Page.map`·`save`·`LocalDateTime.of`·`plusMinutes`)의 Kotlin `checkNotNull*` 제거 |
| `unresolved_public_nullable_guard` | 9 | 공개 nullable 입력(`request!!`·`condition!!`·`date!!`) 또는 JPA nullable 컬럼(`space.openingTime!!`)의 `checkNotNull` 제거. 뒤 deref가 NPE일 가능성은 크지만 예외 시점·타입을 확정하지 못해 **미확정** |
| `unresolved` | 8 | 조건분기 제거 변이의 정확한 피연산자·도달성을 확정하지 못함. **미확정** |
| `equivalent_npe_preserved` | 5 | `RankingPeriodType*`의 `date ?: throw NPE` 제거. date가 null이면 뒤에서 같은 NPE |
| `equivalent_empty_return` | 3 | 이미 `emptyList()`를 반환하는 분기의 반환값 치환 |
| `equivalent_redundant_prevalidation` | 2 | `AdminSpaceService` L92의 검증을 추가로 강제하는 두 조건 변이(전제부 4-2) |
| `timed_out` | 1 | 시간 초과로 판정 보류(KILLED로 세지 않음) |

각 행의 안정 식별자(status·class·method·methodDescription·mutator·line·index·block·description)와
행별 근거(`reason`)는 [classifications.csv](space-audit-stats-mutation/classifications.csv)에 있다.
실제 변이 메서드 인벤토리는 [mutated-methods.csv](space-audit-stats-mutation/mutated-methods.csv)에 있다.

### 4-1. 생성 null 검사는 "생성됨"과 "관측 동치"를 구분한다 (11 + 9)

Kotlin 컴파일러가 넣은 `Intrinsics.checkNotNull*` 호출 제거 20건을 두 부류로 나눈다.

- `kotlin_generated_nonnull_contract` (11): `orElseThrow`/`Page.map`/`save`/`LocalDateTime.of`/
  `plusMinutes`처럼 반환값이 API 계약상 non-null인 값. 제거해도 관측 동작이 바뀌지 않아 등가로 본다.
- `unresolved_public_nullable_guard` (9): `request!!`, `condition!!`, `date!!`, JPA nullable 컬럼
  `space.openingTime!!`/`closingTime!!`처럼 **공개 nullable 계약**에서 들어오는 값. 가드를 지우면
  뒤 deref가 NPE를 낼 가능성이 크지만 예외 시점·타입이 동일하다고 확정하지 않아 **미확정**으로 남긴다.

### 4-2. `AdminSpaceService.updateSpace` 사전 검증 (2 남음)

`javap`(`controller-javap-AdminSpaceService.txt`)에서 `updateSpace`는 bytecode 63 `ifnonnull 73`
(opening 검사)과 bytecode 70 `ifnull 83`(closing 검사)로 OR 조건을 구성한다. PIT 1.22.1
`RemoveConditionalMethodVisitor`(`controller-javap-PIT-RemoveConditionalMethodVisitor.txt`)는
EQUAL_IF 변이를 stack pop 후 fall-through로, EQUAL_ELSE 변이를 원래 대상으로의 unconditional goto로
바꾼다.

| 변이 | 동작 | 판정 |
| --- | --- | --- |
| idx47/block10 EQUAL_IF (`ifnonnull` fall-through) | opening-only 변경 + closing=null이면 검증 생략 | **비등가**. opening-only 18:30 + closing null 테스트로 KILLED |
| idx50/block12 EQUAL_ELSE (`ifnull` 항상 jump) | opening=null + closing-only 변경이면 검증 생략 | **비등가**. closing-only 08:30 + opening null 테스트로 KILLED |
| idx47/block10 EQUAL_ELSE (항상 jump 73) | 검증을 추가로 강제 | 저장된 운영시간이 유효하다는 전제에서만 동치. 전제가 깨진 저장 데이터면 미확정 |
| idx50/block12 EQUAL_IF (fall-through) | 검증을 추가로 강제 | 위와 같음 |

즉 남은 2건은 "검증을 추가로 강제"하는 방향이라 저장된 `space.openingTime`/`closingTime`이
`validateOperatingHours`를 통과한다는 전제에서만 등가다. attempt 1의 "네 조건 변이 모두 동치" 오판은
이렇게 정정한다. L93 호출 제거 변이와 양쪽-non-null 비정상 축소는 fix1에서 KILLED다.

### 4-3. `AuditLogService.log` nullable 계약 (attempt 1 오판 → KILLED)

`action`/`targetType`은 공개 nullable이고, `requireNotNull` 검사 제거 변이는 예외 타입을 IAE→NPE로
바꾼다. 신규 `AuditLogServiceMutationTest`(action=null, targetType=null 각각 IAE + 저장 없음)로
L36/L37을 KILLED 처리했다.

### 4-4. `timed_out` (1)

`SpaceOperatingHoursPolicy.generateSlots` L51의 `if (currentEnd == validClosingTime) break` 탈출 조건
제거 변이가 원 감사·최종 두 회차 모두 TIMED_OUT이었다. 일반적으로는 다음 `currentEnd`가
`validClosingTime`을 초과하면 `if (currentEnd.isAfter(...))`에서 끝나지만, `closingTime`이 23:30처럼
자정 직전이면 `plusMinutes(30)`이 자정을 넘겨 종료 비교가 사라질 수 있다. 관측값은 TIMED_OUT이며
"항상 무한 루프"로 일반화하지 않는다. KILLED로 세지 않고 별도 집계한다.

## 5. #187 대표 변이 before/after

`SpaceQueryService.isNotHalfHourAligned`(`time.minute % 30 != 0 || ...`)의 동일 안정 식별자:

| 시점 | status | methodDescription | mutator | index | block | line | killingTest |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 원 감사 `...1051.../mutations.xml` | **SURVIVED** | `(Ljava/time/LocalTime;)Z` | MathMutator "Replaced integer modulus with multiplication" | 6 | 1 | 117 | (없음) |
| 최종 `steps/03-fix/2/evidence/02_final_pit/mutations.xml` | **KILLED** | `(Ljava/time/LocalTime;)Z` | MathMutator "Replaced integer modulus with multiplication" | 6 | 1 | 117 | `SpaceQueryServiceTest` `getSpacesPage_halfHourBoundary_succeeds(java.time.LocalTime, java.time.LocalTime)` `test-template-invocation:#3` |

`killingTest`는 첫 실패 테스트에서 멈출 수 있으므로 "이 변이에 모든 테스트가 실행됐다"고 주장하지 않는다.

## 6. 추가한 테스트 (총 4파일 13건)

생존 변이로 확인된 실제 업무 공백만, 기존 파일을 건드리지 않고 새 파일로 추가했다.

| 파일(신규) | 테스트 | 고정 규칙 |
| --- | --- | --- |
| `AdminSpaceServiceMutationTest.kt` | 5건 | 비어 있지 않은 DTO 매핑, null keyword 정규화, 양쪽 운영시간 동시 위반 거절, opening-only 비정상 축소 거절, closing-only 비정상 축소 거절 |
| `SpaceMutationTest.kt` | 4건 | null 요금 거절, 나노초 위반 거절, `:30` 허용, 양쪽 운영시간 동시 위반 |
| `AuditLogQueryServiceMutationTest.kt` | 2건 | dateFrom만/dateTo만 반개구간 변환 |
| `AuditLogServiceMutationTest.kt` | 2건 | `action=null`/`targetType=null` 각각 IAE + 저장 없음 |

이로써 `Space`(62)·`Space$Companion`(27)·`SpaceRankingAggregate`(7)·`AuditLogService`(10)는 전량 KILLED다.

## 7. SQL 수동 변조 후보 (HYPOTHESIS, NOT EXECUTED)

자동 PIT 범위 밖(Stats 랭킹 집계 SQL)의 후보다. **구현·실행하지 않았다.** 후보는 현재 코드에 실제로
존재하는 `SpaceRankingAggregationRepository` companion 상수의 특정 연산이며, 그 SQL을 실제로 소비하는
Java 통합 테스트(`SpaceRankingConfirmServiceIntegrationTest`) 메서드에 연결한다. `SpaceRankingQueryServiceIntegrationTest`는
스냅샷을 직접 insert하므로 이 SQL 변이를 검출하지 못한다.

| # | 실제 SQL/연산 (소스) | 변조 | 연결 테스트 메서드 | 예상 단언 |
| --- | --- | --- | --- | --- |
| S1 | `REVENUE_SQL`: `-SUM(t.amount) AS net_revenue` | 부호 제거(`SUM(t.amount)`) | `SpaceRankingConfirmServiceIntegrationTest.confirmedReservationCountsRevenueCountAndSlots` | `getNetRevenue() == 3L*PRICE`(양수) 대신 음수/부호 반전으로 실패 |
| S2 | `COUNT_SQL`: `WHERE status IN ('CONFIRMED','IN_USE','COMPLETED','NO_SHOW')` | 화이트리스트에서 상태 제거/확대 | `SpaceRankingConfirmServiceIntegrationTest.onlyCountableStatusesAreCounted` | `reservationCount==2`, `slotCount==3`, `netRevenue==0` 불일치로 실패 |
| S3 | `REVENUE_SQL` `created_at >= :from AND created_at < :to` / `COUNT_SQL` `start_time >= :from AND start_time < :to` | 경계 반개구간/포함 변경 | `SpaceRankingConfirmServiceIntegrationTest.midnightBoundaryForRevenueAndStartTime` | 23:59:59는 전날, 00:00:00은 다음 날로 잡히는 기대 불일치로 실패 |

실행에는 MySQL Testcontainers와 통합 테스트 실행이 필요하므로 이번 이슈에서는 후보만 남긴다.

## 8. 한계와 제외

- 이 문서의 수치는 2026-10-27 최종 실행이며 기준은 아래 "기준 상태"다. 원 감사 341
  (=`Space$Companion` 누락, #188/#189 이전 `AdminSpaceService` 67)과는 대상 집합이 달라 총점을
  직접 비교하지 않는다. before/after는 동일 클래스의 개별 변이 상태로만 비교한다.
- `unresolved`+`unresolved_public_nullable_guard` 17건과 L92 잔여 2건의 "저장 운영시간 유효" 가정은
  확정하지 않았다. 후속 확인이 필요하다.
- `killingTest`는 첫 실패 테스트에서 멈출 수 있어 변이별 전체 테스트 실행을 주장하지 않는다.
- SQL/트랜잭션/동시성 자동 변조, Stats 랭킹 조회·확정 서비스 전체는 범위 밖이다(§7은 후보만).
- `SpaceRankingAggregate`는 repository 패키지의 산술 값 클래스로, 변이는 `isAllZero` 산술에 한정된다.
- 컨트롤러/HTTP 골든·E2E·프론트 검증은 이번 범위가 아니다(#192 등).
- 로컬 전체 clean build, 필터 없는 전체 테스트, PIT 점수 게이트, CI는 실행·추가하지 않았다.

## 9. 기준 상태 (실행 시점)

- 브랜치 `test/space-audit-stats-pit-scope`, 시작 HEAD `e7ab168118048d49c8f8cdf5e03f63f8ef2140d4`.
- 변경 파일: `backend/build.gradle`(PIT 선정), 신규 테스트 4파일, 이 문서와 동반 CSV 2개.
- 운영 코드: 변경 없음. 임시 수동 변이 없음(해당 없음).
- 최종 raw XML/HTML/로그와 실행 메타데이터: `.agents/automation/261027_1253_issue191_freebuff/steps/03-fix/2/evidence/02_final_pit/`.
  attempt 1/fix1 raw는 상위 `.../261027_1253_issue191_freebuff/evidence/` 및 `steps/02-fix/1/evidence/`에 보존했다.
- 제출본 보존: attempt 1(`steps/02-fix/1/evidence/00_attempt1_original/`), fix1(`steps/03-fix/2/evidence/00_round1_original/`).
  attempt 1 `build.gradle`의 실제 바이트 상태는 백업 파일로 남아 있지 않고, 그 해시(`a87ada5a...`)와 diff는
  root `result.json`/`snapshot.json`의 `changed_files`·`build_gradle_diff`에 기록되어 있다. fix1 백업
  `steps/02-fix/1/evidence/00_attempt1_original/build.gradle.attempt1`은 fix1의 `targetTests` 추가 **이후**
  상태(sha256 `837a58ed...`)이므로 attempt 1 원본이 아니다.
