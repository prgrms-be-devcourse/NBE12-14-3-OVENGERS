# 뮤테이션 테스트 1단계 보고: PIT 설치와 정책 2개 baseline

> 작성: 2026-10-26. 구현자와 검증자가 같은 세션이므로 "자기검증"이다. 범위·순서는 [계획](../../../plans/mutation-testing/00-mutation-testing-plan.md), 이유는 [결정 문서](../../../decisions/mutation-testing.md)에 있다.

## 1. 사전 확인 결과 (계획 §4)

| 항목 | 결과 | 출처 |
| --- | --- | --- |
| PIT Gradle 플러그인 버전 | `info.solidsoft.pitest` 1.19.0 (Gradle Plugin Portal 최신, 2026-03-29). 1.7.0부터 Gradle 6.4 이상 필요하므로 8.10.2에서 사용 가능 | https://plugins.gradle.org/m2/info/solidsoft/pitest/info.solidsoft.pitest.gradle.plugin/maven-metadata.xml, https://github.com/szpak/gradle-pitest-plugin |
| JUnit 5 연동 | `junit5PluginVersion = '1.2.3'` (pitest-junit5-plugin 최신, Maven Central). 플러그인이 `org.pitest:pitest-junit5-plugin`을 추가하고 testPlugin을 junit5로 설정 | https://repo1.maven.org/maven2/org/pitest/pitest-junit5-plugin/maven-metadata.xml |
| Java 테스트가 Kotlin 클래스를 대상으로 실행되는지 | 실행됨. `NonNullPolicyContractTest`(Java)가 examined 테스트에 포함 | 실행 결과 |
| Kotlin 노이즈 뮤턴트 | 이 두 클래스에서는 문제 없음. 24개 생성, 의미 없어 보이는 뮤턴트는 없음. 다른 클래스(`when`, null 체크가 많은 곳)는 확장 때 다시 본다 | 실행 결과 |
| 실행 시간 | 분석 1초 미만, 전체 `./gradlew pitest` 43초(`--no-daemon`, Gradle 기동 포함) | 실행 결과 |
| `Reservation` 상태 전이 단위 테스트 유무 | 이번 단계 범위 아님. 4단계에서 확인 | |

## 2. baseline (대상: `ReservationTimePolicy`, `ReservationRefundPolicy`)

대상 테스트는 `com.ovengers.slotkey.reservation.policy.*`로 한정했다(단위 테스트만).

| 지표 | 값 |
| --- | --- |
| 생성된 뮤턴트 | 24 (`ReservationTimePolicy` 20, `ReservationRefundPolicy` 4) |
| 잡힘(killed) | 23 (96%) |
| 살아남음(survived) | 0 |
| 커버리지 없음(no coverage) | 1 |
| 라인 커버리지(대상 클래스만) | 31/31 (100%) |
| 테스트 강도(test strength) | 100% |
| 실행된 테스트 | 5개 테스트 클래스·메서드 단위로 examined, 28회 실행 |

### 남은 뮤턴트 1건

- 위치: `ReservationTimePolicy.kt:66` `validateExtension`의 `newEnd == LocalTime.MIDNIGHT && closing != LocalTime.MIDNIGHT` 조건 (`NegateConditionalsMutator`)
- 상태: NO_COVERAGE (어떤 테스트도 이 줄의 해당 분기에 도달하지 않음)
- 해석(가설, 확정 아님): 앞 검사에서 `newEndTime`이 `currentEndTime`보다 늦고 같은 날짜여야 하므로 `newEnd`가 자정(00:00)일 수 없다. 그렇다면 `newEnd == MIDNIGHT` 분기는 도달 불가능한 방어 코드이고, 이 뮤턴트는 "구멍"이 아니라 "동치/도달 불가"로 분류될 가능성이 높다. 2단계에서 `closing`이 자정인 운영시간 예외가 이 분기에 의존하는지 확인한 뒤 분류한다. 운영 코드는 수정하지 않는다.

## 3. 인계 보고

1. 변경 파일: `backend/build.gradle`(플러그인 1개와 `pitest` 설정 블록 추가), 이 보고서
2. 실행한 명령과 결과: `./gradlew --no-daemon pitest` → BUILD SUCCESSFUL. 보고서는 `backend/build/reports/pitest/`(`.gitignore`의 `build/`로 제외됨). 전체 테스트는 이 단계에서 실행하지 않았다(운영 코드·테스트 변경 없음)
3. 가정과 미확인: 다른 클래스의 Kotlin 노이즈, `Reservation` 단위 테스트 유무, CI 영향 없음(`pitest`는 수동 실행 태스크이며 `build`에 연결하지 않음)은 확인하지 못했거나 후속 단계에서 확인한다. 남은 뮤턴트 해석은 가설이다
4. 관련 결정 문서: [mutation-testing.md](../../../decisions/mutation-testing.md)

## 4. 리뷰 체크리스트

- [ ] `pitest`가 `build`/`test`/CI에 연결되지 않았다 (`build.gradle`에 `dependsOn` 없음)
- [ ] 대상이 `ReservationTimePolicy`, `ReservationRefundPolicy` 2개로 한정돼 있다
- [ ] 운영 코드, 기존 테스트 파일을 수정하지 않았다
- [ ] 플러그인·JUnit5 연동 버전의 출처가 위 표에 적혀 있다
- [ ] 라인 100%인데 NO_COVERAGE가 1건 남은 이유(조건식 일부 분기 미도달)를 이해했다

## 5. 사용자 결정이 필요한 질문

- 이 변경(`build.gradle` 코드)을 문서 PR #142에 같이 둘지, 별도 브랜치·PR로 나눌지. `AGENTS.md`는 문서 커밋과 코드 커밋을 나누라고 한다. 같은 PR에 두려면 커밋은 나누고 PR 본문의 "문서만 추가" 설명을 고쳐야 한다.
