# 담당 도메인 Java 테스트 Kotlin 전환 — 1단계 독립 검증 결과 (Issue #177)

- 검증자: Claude (구현자 Codex와 별도 세션). 구현자 보고서: [step-1.md](step-1.md)
- 검증 시각: 2026-10-27 10:30~11:15(KST). 브랜치 `refactor/tests-kotlin-domains` @ `590d443`, 작업 트리에 변경만 있고 커밋은 없음.
- 환경: JDK 21.0.11, Gradle wrapper, Docker Testcontainers MySQL.

## 판정: 통과 (검증자가 1건 수정함)

| 확인 | 방법 | 결과 |
| --- | --- | --- |
| 전체 테스트 | `./gradlew cleanTest test`, XML 합산 | 종료 0, **1,361건 / 실패 0 / 오류 0 / 스킵 0**, XML 189개. 전환 전 `dev`와 같은 수 |
| CI 기준선 검사 | `check_test_count.py` | 통과 (`baseline=1322`, `test-baseline.txt` 변경 없음) |
| 변환 완전성 | 삭제된 Java 71개 ↔ Kotlin 파일 대응 | 71개 모두 대응, 동명 `.java`/`.kt` 공존 0 |
| 테스트 수 보존 | 클래스별 `@Test`·`@ParameterizedTest` 개수 비교 (전환 전 `HEAD`의 Java 소스 vs 새 Kotlin) | 71개 파일 모두 동일 |
| 판정 줄 보존 | 주석을 제외한 문자열·숫자 리터럴 다중집합, `assertThat`·`verify`·`jsonPath` 등 토큰 비교 | 의미 있는 차이 없음. 차이로 잡힌 것은 모두 문법 차이(아래 참고) |
| 동시성 테스트 | 스레드 수·재시도 횟수 등 숫자 리터럴 | 동일 |
| 변경 금지 | `git status` | 골든 JSON·운영 코드·`build.gradle`·`test-baseline.txt`·CI·Java 공유 `support` 변경 0, `@Disabled`·Lombok·시크릿 값 0 |
| lint·빌드 | `ktlintCheck --rerun-tasks`, `clean build -x test` | 종료 0 |
| 테스트가 결함을 잡는가 | 운영 코드 3곳을 일부러 변조(대기 한도 3→4, Outbox relay topic/key 순서 교체, 충전 확인 202→200)하고 해당 테스트 실행 | 각 테스트 실패(1건·1건·3건). 운영 코드는 원복해 바이트 동일 |
| 뮤테이션(PIT) 전후 비교 | 변환 전 `dev`(`590d443`)와 변환 후를 같은 설정으로 `./gradlew pitest` | 179개 뮤턴트 기준 **뮤턴트별 상태 비교**. 최초 1건 차이를 발견해 수정함(아래), 수정 후 차이 0 |

문법 차이로 판명한 항목: 문자열의 `$` 이스케이프(`\$`), Mockito `when` 백틱, 접근자 `status()` → 프로퍼티 `status`, `import` 줄의 토큰, Kotlin 범위 `1..51`.

## 발견과 수정 (검증자가 직접 수정한 변경 포함)

1. **`ChargeOrderServiceTest.kt` 76행 (수정함).** 전환 전 Java는 `saved.getMemberId()`로 `ChargeOrder`의 명시적 함수를 호출했다. Kotlin 변환은 `saved.memberId`(프로퍼티)로 바뀌어 이 함수를 더 이상 실행하지 않았고, PIT에서 `ChargeOrder.getMemberId` 뮤턴트가 "잡힘"에서 "커버리지 없음"으로 바뀌었다(기록된 12→13). 단언 값은 같았으므로 기능 회귀는 아니지만 원본 호출을 보존하도록 `saved.getMemberId()`로 되돌렸다. 수정 후 PIT 결과가 전환 전과 뮤턴트 단위로 완전히 같다(잡힘 148, 생존 18, 커버리지 없음 12, 메모리 오류 1).
   - 일반화: Kotlin에서는 `fun getX()`를 직접 정의한 클래스에서 `obj.x`와 `obj.getX()`가 다른 경로다. 이번 PIT 대상 클래스에서는 이 1건뿐이었지만, PIT 대상이 아닌 클래스에서 같은 일이 있었는지는 검사하지 못했다.
2. **문서의 깨진 링크 8개 (수정함).** `01-test-baseline.md`, `test-strategy.md`가 이전 PR에서 이미 Kotlin으로 옮겨진 테스트 4개를 `.java` 경로로 가리키고 있었다. 이번 작업이 만든 문제가 아니며 `.kt`로 고쳤다.

## PIT 기록 대조 (변환 후)

| 대상 | 기록 | 변환 후 |
| --- | --- | --- |
| 정책 2개(`ReservationTimePolicy`·`ReservationRefundPolicy`) | 39개 중 39 | 39개 중 39 |
| `PricingService` | 4개 중 4 | 4개 중 4 |
| `DoorAccessTimePolicy` | 16개 중 16 | 16개 중 16 |
| `CreditServiceImpl` | 36개 중 31 | 36개 중 31 |
| `ChargeOrder` | 28개: 잡힘 12 / 생존 4 / 커버리지 없음 12 | 수정 후 동일 |

## 한계·직접 검증하지 못한 것

- **타 담당 영역에 남은 낡은 테스트 경로 참조는 고치지 않았다.** 두 PR 병합 후 상태 기준 문서 검사에서 공간 이미지 테스트(`docs/api/admin-space-image-upload.md`, `docs/operations/troubleshooting.md`), 출입 테스트(`01-test-baseline.md`, `test-strategy.md`, `02-money-and-concurrency.md`의 `InactiveMemberAccess…`·`DoorAccessToken…`)가 `.java` 경로를 가리킨다. 각 담당자 영역(김재철·박창현)이라 알리기만 한다.

- 판정 줄 비교는 토큰·리터럴 수준의 정적 비교다. 동일한 리터럴을 쓰는 단언의 **논리 구조**(예: `isEqualTo` ↔ `isNotEqualTo` 교체)까지 증명하지는 못한다. 토큰 개수 비교와 운영 코드 변조 3건, PIT 전후 일치로 보완했다.
- 변조 실험은 3개 도메인에서 샘플로 했으며 71개 전체가 아니다.
- 프론트 검사, CI는 확인하지 못했다(PR에서 확인).
- 다음 두 PR로 나누어 올릴 때 각 브랜치 단독의 전체 테스트는 CI로 확인한다. 이 검증은 두 PR의 합집합 상태에서 했다.

## 다음 조치

- 도메인별 커밋으로 나눠 PR 2개(예약 / 나머지)를 올린다. `MockitoKotlinHelpers.kt`는 두 PR이 서로 기다리지 않도록 양쪽에 같은 내용으로 넣는다(동일 파일이라 병합 충돌 없음).
