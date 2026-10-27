# 테스트 결과 보고서

> **문서 목적**: 로컬 및 CI 환경에서 실행된 백엔드 자동화 테스트의 실측 결과를 기록하고, 도메인별 검증 현황과 미실행/미검증 범위를 명확히 추적한다.
>
> **2026-10-18 안내 (`16b9ead`)**: 아래 2026-09-24/09-22 수치와 제약·“PG 미도입/문의 테스트 부재” 설명은 당시 실행의 스냅샷이다. 이후 PG 충전·관리자 문의 HTTP 테스트 등이 추가됐으며 현재 소스 대응표는 [Kotlin 전환 테스트 기준선](../plans/kotlin-migration/01-test-baseline.md)을 참조한다. 당시에는 빌드·테스트를 재실행하지 않았으나, 이후 2026-10-20 기능 동결 기준선 실행을 **§0-0**에 추가했다.

> **2026-10-20 Java 기준선 실행 결과** — 기능 동결 커밋 `d1a15eca3228c8b1b1f9260fc7a686860d2b1e44`(짧은 해시 `d1a15ec`)에서 `./gradlew clean build`를 실행해 **1048개 테스트 전원 통과(실패 0 / 오류 0 / 스킵 0)**를 확인했다. 상세는 아래 **§0-0**을 참조한다. 이 기록은 이후 커밋의 실행 결과를 뜻하지 않는다. 2026-10-18 안내와 아래 2026-09-24/09-22 수치도 각각 당시 실행의 기록이다.

---

## 0-MT. 뮤테이션 테스트 도입과 전체 회귀 (2026-10-26, 로컬)

> 결정과 상세 결과는 [뮤테이션 테스트 결정](../decisions/mutation-testing.md), 단계별 보고는 [`lab/notes/mutation-testing/`](../lab/notes/mutation-testing/)에 있다. 아래는 작업 단계마다 구현과 다른 세션에서 직접 재실행한 전체 회귀 결과다. 모두 Docker를 켠 로컬에서 `cleanTest test`로 실행했다.

| 시점 | 명령 | 클래스 | 테스트 | 실패·오류·스킵 | 시간 |
| --- | --- | ---: | ---: | ---: | --- |
| 4단계 (예약 조건부 UPDATE 수동 변조 보강) | `./gradlew cleanTest test` | 169 | 1,305 | 0 | 8분 49초 |
| 후속 #154 (`ChargeOrder` 전이 보강) | `./gradlew cleanTest test` | 175 | 1,318 | 0 | 9분 23초 |
| 5단계 (`CreditServiceImpl` 보강) | `./gradlew cleanTest test` | 175 | 1,322 | 0 | 9분 38초 |

- 테스트 수 증가는 뮤테이션 작업의 새 테스트(4단계 8건, #154 2건, 5단계 6건)와 같은 기간에 병합된 다른 PR의 테스트가 함께 반영된 결과다.
- **재현 주의:** `./gradlew test`만 다시 실행하면 입력이 같을 때 Gradle이 `up-to-date`로 이전 결과를 재사용해 5초 만에 끝난다. 실제 재실행 근거는 `cleanTest`를 붙이거나 소스가 바뀐 실행만 쓴다.
- PIT 실행(`./gradlew pitest`, 단위 테스트 한정)은 Gradle 기동 포함 약 22~43초다. 통합 테스트까지 포함하면 7분 17초였다.

---

## 0-KT2. 목요일 전환 타입 non-null 후속 변경 (2026-10-22, 로컬)

> 사용자의 nullable 제거 요청을 반영한 코드 `3c65285`, 브랜치 `feat/kotlin-thursday-policies`, PR #26. 초기 boxed Long·null 입력 보존 계약은 [non-null 결정](../decisions/kotlin-thursday-non-null-contract.md)으로 변경했다. 외부 PG 누락 값의 결과 모름 처리와 HTTP Bean Validation 경계는 유지했다.

| 실행 | 결과 |
| --- | --- |
| 변경 전 Java null 계약 테스트 | 4개 모두 실패 (예상된 RED) |
| 변경 후 선택 테스트 | 8 스위트·107개 통과, 실패/오류/스킵 0 |
| 전체 `env -u SPRING_MAIL_USERNAME -u SPRING_MAIL_PASSWORD -u VERIFICATION_MAIL_FROM -u SLOTKEY_KAFKA_ENABLED ./gradlew --no-daemon clean build` | **종료 0, 9분 27초**, ktlint 위반 0 |
| 전체 테스트 | **140 스위트·1,085개 통과, 실패/오류/스킵 0** |
| 테스트 수 검사 | 종료 0, `tests=1085 baseline=1085 skipped=0 failures=0 errors=0` |
| XML 시각 | 2026-10-22 16:42:39~16:51:36 KST |

- 기존 Kotlin null 테스트 4개를 Java non-null 계약 테스트 4개로 대체하고, PG 코드 누락·명시적 null·null 본문 테스트 3개를 더했다. 테스트 하한은 1,082 → 1,085다.
- 실제 MySQL JPQL 생성자 조회, Java 결제 소유권 비교, PG 비정상 JSON과 Outbox 실패 경로를 포함한다. Kotlin 운영·테스트 소스에서 물음표와 이중 느낌표는 모두 0건이다.
- 환경은 앞선 macOS/JDK 21/Gradle 8.10.2/Docker MySQL Testcontainers와 같다. 로그 `/tmp/slotkey-non-null-full.log`, XML `/tmp/slotkey-non-null-full-xml/`는 임시 파일이다.
- 구현자 **자기검증**이다. 독립 전체 검증·새 CI 결과·실제 외부 서비스·운영 배포는 별도다. 변경 파일·가정·리뷰 체크리스트는 [step-2 보고](../lab/notes/kotlin-thursday-policies/step-2.md)에 있다.

---

## 0-KT1-PR. 최신 dev 반영 후 목요일 전환 PR 검증 (2026-10-22, 로컬)

> 코드 `1190d04`(`feat/kotlin-thursday-policies`), 기반 `origin/dev` `2186ca1`. 아래 초기 실행 이후 dev에 머지된 #21의 ktlint 수정과 #24까지의 테스트 보강을 반영했다. 초기 clean build 실패를 이 최종 결과와 구분한다.

| 항목 | 결과 |
| --- | --- |
| 명령 | `env -u SPRING_MAIL_USERNAME -u SPRING_MAIL_PASSWORD -u VERIFICATION_MAIL_FROM -u SLOTKEY_KAFKA_ENABLED ./gradlew --no-daemon clean build` |
| 전체 빌드 | **종료 0, BUILD SUCCESSFUL in 8m 54s**, ktlint 운영/테스트 위반 0 |
| 테스트 | **139 스위트·1,082개 통과, 실패/오류/스킵 0** |
| XML 시각 | 2026-10-22 16:12:05~16:20:34 KST |
| 테스트 수 검사 | 종료 0, `tests=1082 baseline=1082 skipped=0 failures=0 errors=0` |
| 실행 근거 | `/tmp/slotkey-thursday-pr-build.log`, XML `/tmp/slotkey-thursday-pr-xml/` (임시 파일) |

- 기존 dev 기준선 1,078개에 이번 전환의 4개를 더해 하한을 1,082개로 올렸다. Kotlin 전환 테스트의 기존 기대값은 유지했다.
- 환경은 아래 초기 실행과 같은 macOS/JDK 21/Gradle 8.10.2/Docker MySQL Testcontainers다. 초기 실패 원인이던 기존 ktlint 26건은 dev #21로 해소됐으며 이 전환 PR에서 별도로 고치지 않았다.
- 구현자의 **자기검증**이다. 별도 검증자의 전체 재실행, GitHub CI, 운영 배포 및 실제 외부 서비스 연결은 이 결과에 포함하지 않는다. 인계는 [단계 보고](../lab/notes/kotlin-thursday-policies/step-1.md)를 참조한다.

---

## 0-KT1. 목요일 담당 정책 Kotlin 전환 (2026-10-22, 로컬)

> `b263e07`에서 분기한 `feat/kotlin-thursday-policies`의 미커밋 작업 트리. 기존 환불 정책을 재검증하고 이태호 담당 나머지 6개와 정책 테스트를 Kotlin으로 전환했다. 구현자의 **자기검증**이며, 별도 세션 코드 검토는 실행 결과 재검증과 구분한다.

| 실행 | 결과 |
| --- | --- |
| 전환 전 전체 `./gradlew --no-daemon test` | 종료 0, 136 스위트·1,053개 통과, 실패/오류/스킵 0, 8분 1초 |
| 새 나노초 경계 테스트 변이 검증 | 검사 누락 임시 변이에서 2개 모두 실패(종료 1), 변이 복구·전환 후 통과 |
| 전환 후 선택 테스트 | 종료 0, 6 스위트·70개 통과, 실패/오류/스킵 0 |
| 전환 후 `./gradlew --no-daemon clean compileKotlin compileJava test bootJar` | **종료 0, 137 스위트·1,057개 통과, 실패/오류/스킵 0**, 7분 53초 |
| CI와 같은 테스트 수 검사 스크립트 | 종료 0, `tests=1057`, `baseline=1057`, `skipped=0` |
| 전환 전·후 필터 없는 `clean build` | 종료 1, 기존 auth/member/inquiry Kotlin 7개 파일의 ktlint 26건. 전환 후 새 Kotlin 파일의 위반은 0건 |
| 프론트 `node --test tests/*.cjs` / `npx tsc --noEmit` | 80/80 통과, 스킵 0 / 종료 0 |

- 실행 환경: macOS, Microsoft OpenJDK 21.0.11, Gradle 8.10.2, Docker/MySQL Testcontainers, Fake PG/메일·Kafka OFF. 백엔드 테스트 명령은 `env -u SPRING_MAIL_USERNAME -u SPRING_MAIL_PASSWORD -u VERIFICATION_MAIL_FROM -u SLOTKEY_KAFKA_ENABLED`로 실행했다.
- 전체 테스트 XML 타임스탬프: 2026-10-22 15:30:14~15:37:48 KST. 전체 실행 로그 `/tmp/slotkey-thursday-full.log`, XML `/tmp/slotkey-thursday-full-xml/`. 전환 전 XML `/tmp/slotkey-thursday-baseline-xml/`. `/tmp` 자료는 임시 파일이다.
- 기존 시간 정책 14개·PG 분류 35개·환불 정책 5개를 유지했고, 초/나노초 경계 2개·Kotlin 오류 접근 1개·실제 DB JPQL 프로젝션 1개를 추가했다. 테스트 수 하한도 1,053 → 1,057로 올렸다.
- 전환 후 `clean build`는 전체 테스트 산출물을 보존하려고 임시 init 스크립트로 build 디렉터리만 분리했다. 검사·테스트 제외 옵션은 사용하지 않았다. 전체 테스트·패키징 통과와 `clean build` 실패를 구분한다.
- `javap`의 Java 정적 호출·record 접근자·boxed `Long` 생성자 및 실행 JAR의 대상 7개/Kotlin 런타임 포함을 확인했다. CI, 독립 전체 테스트 재실행, 실행 JAR의 HTTP 기동, 운영 배포와 실제 외부 PG/SMTP/Kafka 연결은 확인하지 않았다.
- 상세 변경 목록·기존 ktlint 파일 목록·경미한 진단 문자열 차이는 [단계 보고](../lab/notes/kotlin-thursday-policies/step-1.md), 설계 근거는 [Lombok 경계](../decisions/kotlin-lombok-boundary.md)·[data class 범위](../decisions/kotlin-data-class-scope.md)를 참조한다.

---

## 0-0. 기능 동결 기준선 실행 기록 (2026-10-20, 로컬)

> 화·수 계획과 목요일 혼용 빌드 시범 전의 **기능 동결(Feature Freeze) 기준선**이다. 아래 수치는 로컬 실측이며 실제 배포 환경 검증과 별개다. 이번 실행은 대단위 1 범위이며 Kotlin 코드·프로덕션 로직은 변경하지 않았다.

| 항목 | 기록 |
| --- | --- |
| 실행 일시 | 2026-10-20 15:50~16:02 KST (테스트 단계) |
| 대상 | `dev` 브랜치, 기능 동결 커밋 `d1a15eca3228c8b1b1f9260fc7a686860d2b1e44` (짧은 해시 `d1a15ec`) |
| 실행 명령 | `env -u SPRING_MAIL_USERNAME -u SPRING_MAIL_PASSWORD -u VERIFICATION_MAIL_FROM -u SLOTKEY_KAFKA_ENABLED ./gradlew --no-daemon clean build` |
| 실행 환경 | Linux, OpenJDK 21.0.12.1 (`/usr/lib/jvm/java-21-openjdk`), Gradle 8.10.2, Docker 29.8.2 + MySQL 8.4 Testcontainers |
| 전체 빌드 | **`BUILD SUCCESSFUL in 11m 44s`**, 종료 코드 `0` |
| 테스트 단계 소요 | 11분 19초 (XML 첫 타임스탬프 15:50:42 ~ 마지막 16:02:01 KST) |
| 실행 테스트 클래스(스위트) | **135** |
| 총 실행 테스트 메서드 | **1048** |
| 통과 | **1048** |
| 실패 | **0** |
| 오류 | **0** |
| 스킵 | **0** |
| `@Disabled` | **0건** (테스트 소스 전체) |
| 최종 판정 | **전원 통과 (ALL PASSED)** |

### 기준선 해석과 이전 기록과의 차이

- 테스트 소스 파일은 148개다. 이 중 실제 실행 스위트는 135개, `@Test`가 없는 빈 클래스 5개, 지원·픽스처 클래스 8개(`IntegrationTestSupport`, `ReservationIntegrationTestSupport`, `TestFixture`, `FakePgClient`, `FakeOutboxPublisher`, `FixedClockConfig`, `MySqlTestContainerConfig`, `SettableClockConfig`)다.
- `V2SecurityRedTest`는 15개 테스트가 모두 활성 상태로 통과했고 `@Disabled`는 0건이다. 2026-10-15 CI에서 실패했던 11건은 현재 소스에서 활성 회귀 안전망으로 동작한다.
- Auth V2 관련 실행 스위트는 `AuthControllerTest`(25), `V2SecurityRedTest`(15), `AuthSessionMigrationIntegrationTest`, `LoginRateLimitIntegrationTest`, `RefreshTokenServiceTest`, `PasswordResetIntegrationTest`, `EmailSignupIntegrationTest`, `VerificationFlowIntegrationTest`, `VerificationMemberStatusIntegrationTest` 등이다.
- `16b9ead..d1a15ec` 사이 추가된 테스트 클래스는 10개다: `InactiveMemberAccessIntegrationTest`, `AuthSessionMigrationIntegrationTest`, `LoginRateLimitIntegrationTest`, `CreditTransactionRepositoryTest`, `GlobalExceptionHandlerTest`, `InactiveMemberAccessFilterTest`, `AdminMemberDetailControllerTest`, `AdminMemberDetailServiceTest`, `ReservationControllerTest`, `ReservationMemberCountRepositoryTest`. 같은 기간 `AuthControllerTest`, `V2SecurityRedTest`, `RefreshTokenServiceTest`, `AdminMemberControllerTest`, `AdminMemberServiceIntegrationTest` 등이 보강됐다.

### 실행 환경 주의 (재현 조건)

이 로컬 셸에는 로컬 기동용 `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, `VERIFICATION_MAIL_FROM`, `SLOTKEY_KAFKA_ENABLED=true`가 내보내져 있었다. 이 값들은 테스트 프로필(`application-test.yml`)의 빈 SMTP 값과 `slotkey.kafka.enabled=false` 기본값을 덮어써 다음 두 유형의 실패를 만든다.

1. `SmtpVerificationMailSender` 생성자의 `VERIFICATION_MAIL_FROM must match SPRING_MAIL_USERNAME` 위반으로 444건 컨텍스트 로드 실패.
2. `KafkaConsumerDisabledTest`, `OutboxRelayDisabledTest`의 "기본 비활성" 빈 부재 단언 실패 2건.

따라서 **테스트 기준선은 위 네 환경 변수를 해제한 뒤 실행**해야 한다. 처음 두 실행에서 관측한 실패는 소스 회귀가 아니라 실행 셸 환경 변수에 의한 것이며, 해제 후 전체 1048건이 통과했다. 테스트 프로필이 외부 SMTP·Kafka 환경 변수에 영향받지 않도록 격리하는 후속 개선은 이번 기준선 범위 밖이다.

---

## 0-V3. V3 Redis 실험 브랜치 실행 기록 (2026-10-20~21, 로컬, dev 미병합)

> 아래는 로컬 실험 브랜치 `auth/improvement/v3`에서 실행한 기록이며 dev와 배포 코드의 결과가 아니다. **2026-10-21 최종 판단으로 V2 운영을 유지하고 V3는 실험 구현으로 보존하며 도입을 보류했다.** 결과 요약, 성능 비교와 판단 근거는 [V3 인증 실험 결과](auth-v3-experiment-results.md)에 있다. 각 기록은 당시 기준으로 보존하며 서로 합산하지 않는다. `/tmp` 아래 XML 위치는 당시 리뷰어가 직접 집계한 경로이며 이후 재부팅으로 사라졌다.

### V3-4. V3 전체 회귀와 실패 테스트 보완 (2026-10-21, 로컬) — 최종 전체 회귀 통과

> 브랜치 `auth/improvement/v3`, HEAD `04437f1acf3b40b85de47bf7dde69d3a95a348eb`(코드 기준 `229517c`)의 작업 트리에 테스트 6개 보완이 포함됐고, 해당 테스트 변경은 이후 커밋 `27262ab`로 고정됐다. 전체 회귀는 커밋 생성 전 작업 트리에서 실행된 것으로 커밋 단독 결과가 아니다. 리뷰어는 재실행하지 않았다. 아래 세 기록은 별개이며 합산하지 않는다(73 + 1,065로 더하지 않는다). 로컬 실행이며 미Push, 미병합, 미배포다.

| 실행 | 시각 (KST) | 전체 | 통과 | 실패 | 오류 | 건너뜀 | 근거 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 최초 V3 전체 백엔드 회귀 | 2026-10-21 (보고 기준) | 1,065 | 1,019 | 46 | — | — | 구현 보고. 보고된 아티팩트 `/tmp/v3-final-regression-20261021-003448/`가 리뷰 시점에 없어 직접 집계하지 못함 |
| 보완 후 실패 클래스 선택 실행 | 09:52:24~09:53:00 | 73 | 73 | 0 | 0 | 0 | `backend/build/test-results/test`의 XML 6개를 리뷰어가 직접 집계(보고된 `/tmp` 아티팩트는 없음). 같은 XML이 전체 실행 전에 `/Users/aaa/slotkey-test-artifacts/v3-full-regression-20261021-1006-kst/selected-73/xml/`로 보존돼 있음을 재확인 |
| **최종 V3 전체 백엔드 회귀** (`./gradlew test` 1회) | 10:07:14~10:16:12 | 1,065 | 1,065 | 0 | 0 | 0 | `/Users/aaa/slotkey-test-artifacts/v3-full-regression-20261021-1006-kst/full-regression/xml/`의 XML 138개를 리뷰어가 직접 집계(스위트 타임스탬프 10:07:31~10:16:03). `gradle.log`에서 `> Task :test` 실행, `5 actionable tasks: 1 executed, 4 up-to-date`, `BUILD SUCCESSFUL in 8m 42s` 확인 |

- 선택 실행 대상: `AdminStatsControllerIntegrationTest` 36, `StatsControllerIntegrationTest` 7, `AdminMemberServiceTest` 10, `AdminSpaceSecurityIntegrationTest` 11, `AdminAuditLogSecurityIntegrationTest` 4, `PasswordResetIntegrationTest` 5. 마지막 테스트 수정(09:52:01) 이후 실행됐다. 중간 선택 실행 실패 기록(보고상 00:50 UTC 무렵)도 아티팩트가 없어 확인하지 못했다.
- 보완 내용(제품 코드 변경 없음, 테스트 6개):
  - 통계 테스트 2개: 고정 시계(통계 기준일 2026-10-16, 17)로 세션을 만들면 Redis 절대 만료가 과거가 되어 키가 바로 사라졌다. 토큰 발급 동안만 시계를 실제 시각으로 바꾸고 `finally`에서 통계 기준 시각으로 되돌린다. 세션 만료 검증은 그대로이며, 기준일 이동 뒤 토큰을 다시 발급하던 단계는 없앴다.
  - `AdminMemberServiceTest`: 복구 경로가 `findEmailById` → Guard 잠금 해제 → `findByIdForUpdate` 순서로 바뀐 것에 목을 맞췄다. 기존 상태, 감사 로그, `TARGET_IS_ADMIN` 단언은 유지된다.
  - 관리자 공간, 감사 로그 보안 테스트: DB 상태를 직접 바꾸던 "비활성 ADMIN" 시나리오를 실제 `AdminMemberService.suspend()`로 정지한 USER의 기존 AT 시나리오로 바꿨다. 정지 상태와 `SUSPEND_MEMBER` 감사 로그를 확인하고, 요청이 401(인가 단계의 403이 아님)이며 공간, 감사 로그가 추가되지 않는지 확인한다. USER 403, ACTIVE ADMIN 200 테스트는 그대로 있다. 탈퇴(WITHDRAWN) 사례는 이 두 클래스에서 빠졌다.
  - `PasswordResetIntegrationTest`: DB 세션 폐기 실패 시 비밀번호와 grant 소비가 롤백되고 DB 세션은 활성으로 남지만, 먼저 실행된 Redis generation 교체로 기존 AT는 401, RT는 `INVALID_REFRESH_TOKEN`이 되고 기존 비밀번호로 새 로그인은 된다는 V3 동작을 확인한다.
- 최종 전체 회귀: 138개 스위트(중복 없음), `V2SecurityRedTest` 19개 통과. 컴파일 태스크는 UP-TO-DATE였고, 마지막 테스트 소스 수정(09:52:01) 이후 `backend/src`, `build.gradle`에 바뀐 파일이 없어 이전 컴파일 결과가 현재 소스와 일치한다.
- 73개 선택 실행은 전체 회귀 통과의 근거가 아니며 별도 기록으로 둔다. 최초 실패 46개는 아티팩트가 없어 직접 확인하지 못했다.
- 이 기록 시점에는 실제 perf 앱 기동, smoke, V2/V3 성능 비교, 운영 Redis 장애와 복구 검증을 실행하지 않았다. 성능 비교는 같은 날 이후 실행했고 결과는 [V3 인증 실험 결과](auth-v3-experiment-results.md)에 있다. 운영 장애와 복구 검증은 도입 보류로 수행하지 않았다.

### V3-3. V3 3번 RT 상태, Rotation Redis 이전 실행 기록 (2026-10-20, 로컬)

> 브랜치 `auth/improvement/v3`, 코드 커밋 `229517c` (로컬). 리뷰어가 아래 XML을 직접 집계했고 재실행하지 않았다. **아래 63개는 클래스별 최신 선택 실행을 모은 것이며 단일 전체 실행이 아니다.**

| 클래스 | 최신 실행 (KST) | XML 위치 | 전체 | 통과 | 실패 | 오류 | 건너뜀 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `PasswordResetControllerTest` | 20:58:04 | `/tmp/v3-3-rt-redis-related-20261020` | 1 | 1 | 0 | 0 | 0 |
| `AuthSessionRedisIntegrationTest` | 20:58:32 | 같은 위치 | 5 | 5 | 0 | 0 | 0 |
| `RefreshTokenServiceTest` | 20:58:42 | 같은 위치 | 1 | 1 | 0 | 0 | 0 |
| `VerificationMemberStatusIntegrationTest` | 20:58:53 | 같은 위치 | 5 | 5 | 0 | 0 | 0 |
| `AuthControllerTest` | 20:59:38 | `/tmp/v3-3-auth-controller-green-20261020` | 25 | 25 | 0 | 0 | 0 |
| `AdminMemberServiceIntegrationTest` | 21:01:20 | `/tmp/v3-3-admin-member-green-20261020` | 7 | 7 | 0 | 0 | 0 |
| `V2SecurityRedTest` | 21:04:10 | `/tmp/v3-3-v2-security-final2-20261020` | 19 | 19 | 0 | 0 | 0 |
| 합계(클래스별 최신) | | | 63 | 63 | 0 | 0 | 0 |

- 대체된 실행: 같은 날 20:57:49 `AuthControllerTest`는 25개 중 1개 실패("DB에 저장된 리프레시 토큰이 만료되었으면 갱신을 거절한다")였고, 테스트 수정(20:59:16) 뒤 20:59:38에 25개 통과했다. 20:58:15 `V2SecurityRedTest` 17개는 21:04:10 실행으로 대체했다. 합산하지 않는다.
- 소스 대조: `RedisAuthSessionStore` 20:53:23, `RefreshTokenService` 20:53:46 수정 뒤 모든 실행이 이뤄졌다. `AuthService`(21:01:51), `AuthController`(21:02:41)는 일부 실행 뒤 바뀌었지만 diff는 주석과 Swagger 설명뿐이라 동작 변경이 없다. 이 두 파일 수정 뒤 실행된 클래스는 `V2SecurityRedTest`뿐이다. 각 테스트 소스는 해당 실행 전에 마지막으로 수정됐다.
- `V2SecurityRedTest` 변경(15 → 19): V2 테스트 1개 삭제(`replacement_insert_failure_rolls_back_consumption` — DB 새 RT 저장 실패 시 소비 롤백, V3에서는 DB RT 행이 없어 해당 없음), V3 테스트 5개 추가.
  - `refresh_token_is_rotated_without_a_database_token_row` — 로그인 RT가 DB 행 없이 Redis에서 회전되는지.
  - `database_only_legacy_refresh_token_is_not_accepted_after_namespace_change` — DB에만 남은 구형 RT가 새 Redis namespace에서 갱신되지 않는지.
  - `logout_racing_refresh_cannot_leave_the_session_usable` — 로그아웃과 refresh 경합 뒤 세션과 두 RT가 모두 차단되는지.
  - `suspension_racing_refresh_cannot_leave_the_session_usable` — 관리자 정지와 refresh 경합 뒤 토큰이 모두 차단되는지.
  - `redis_rotation_response_failure_leaves_a_complete_atomic_state` — Redis 회전 반영 뒤 503이 나도 구 RT `CONSUMED`, 새 RT `ACTIVE`로 완전히 바뀌어 있고, 옛 RT 재시도는 재사용으로 세션을 폐기하는지.
- 다른 회원 ID, sid에 임의 난수를 넣은 RT, 형식이 잘못된 RT에 대한 refresh, logout 테스트는 없다(코드 리뷰로만 확인). 전체 백엔드 회귀와 운영 복구 검증은 실행하지 않았다.

### V3-2. V3 2번 Redis 세션 인증 전환 실행 기록 (2026-10-20, 로컬) — 코드 커밋 `cab2ad1`, 리뷰 완료

> 브랜치 `auth/improvement/v3`, 코드 기준 커밋 `cab2ad1`. 리뷰어가 아래 XML을 직접 집계했고 재실행하지 않았다. 같은 클래스의 반복 실행은 합산하지 않는다.

| 실행 (XML 위치) | 시각 (KST) | 대상 | 전체 | 통과 | 실패 | 오류 | 건너뜀 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1차 관련 실행 (`/tmp/v3-2-related-run-final`) | 17:44:45~17:46:09 | `AuthControllerTest` 25, `PasswordResetControllerTest` 1, `V2SecurityRedTest` 15, `AuthSessionRedisIntegrationTest` 5, `RefreshTokenServiceTest` 1, `VerificationMemberStatusIntegrationTest` 2, `AdminMemberServiceIntegrationTest` 7 | 56 | 56 | 0 | 0 | 0 |
| 감사 로그 테스트 단독 (빌드 결과 디렉터리) | 17:47:04 | `InactiveMemberAccessIntegrationTest` 1 | 1 | 0 | 1 | 0 | 0 |
| B1, B3 보완 후 (`/tmp/v3-2-b1-b3-related-20261020`) | 18:03:06~18:03:19 | `InactiveMemberAccessIntegrationTest` 1, `VerificationMemberStatusIntegrationTest` 3, `InactiveMemberAccessFilterTest` 7 | 11 | 11 | 0 | 0 | 0 |
| B2 보완 후 세션, 관리자 (`/tmp/v3-2-b2-session-admin-20261020`) | 18:03:44~18:04:10 | `AuthControllerTest` 25, `AuthSessionRedisIntegrationTest` 5, `AdminMemberServiceIntegrationTest` 7 | 37 | 37 | 0 | 0 | 0 |
| B1 테스트 단일 재실행 (`/tmp/v3-2-b1-final-20261020`) | 18:05:21 | `VerificationMemberStatusIntegrationTest`의 Redis 장애 테스트 1 | 1 | 1 | 0 | 0 | 0 |
| **최종: 자동 정지 Redis 차단 재시도 후 9개 클래스** (`/tmp/v3-auto-suspension-final-9classes-20261020`) | 20:25:45~20:27:15 | `AuthControllerTest` 25, `PasswordResetControllerTest` 1, `V2SecurityRedTest` 15, `AuthSessionMigrationIntegrationTest` 2, `AuthSessionRedisIntegrationTest` 5, `RefreshTokenServiceTest` 1, `SessionRevocationRetryProcessorTest` 2, `VerificationMemberStatusIntegrationTest` 5, `AdminMemberServiceIntegrationTest` 7 | 63 | 63 | 0 | 0 | 0 |

- 소스 변경 시점: 마지막 제품 코드, 설정 수정은 18:00:25, `run-app-perf.sh`는 18:04:26, `VerificationMemberStatusIntegrationTest` 소스는 18:04:59.
- **최종 소스 기준 고유 통과는 48개**다(`InactiveMemberAccessIntegrationTest` 1, `InactiveMemberAccessFilterTest` 7, `VerificationMemberStatusIntegrationTest` 3, `AuthControllerTest` 25, `AuthSessionRedisIntegrationTest` 5, `AdminMemberServiceIntegrationTest` 7). 단일 재실행 1개는 중복이라 더하지 않는다. `VerificationMemberStatusIntegrationTest`의 나머지 2개는 테스트 소스 마지막 수정(18:04:59) 전에 실행됐다.
- 1차 실행은 B1, B3 제품 코드 수정(17:58~17:59) 전이다. 그 뒤 `PasswordResetControllerTest`, `V2SecurityRedTest`, `RefreshTokenServiceTest`는 다시 실행하지 않았다.
- 17:47:04 실패(`MEMBER_INACTIVE` 로그 0건)는 B3 보완 후 18:03:06 실행에서 테스트 기대를 바꾸지 않고 통과했다.
- **최종 판정 근거는 마지막 행(63개)이다.** 마지막 소스 수정(제품 코드 20:23:22, 테스트 20:24:41) 이후 실행됐으며 앞선 실행과 합산하지 않는다. 관리자 복구 경합(C1), Redis 장애 중 자동 정지, 대기 행 커밋, 이전 namespace 대기 행, 완료 기록 실패 후 재실행, 다중 인스턴스 동시 처리 테스트를 포함한다.
- 테스트 Redis는 별도 Testcontainers 컨테이너(`redis:7.4.11-alpine`, `MySqlTestContainerConfig`의 `@ServiceConnection`)이고 namespace는 `test`다. XML 출력에서 클래스별 컨테이너 기동을 확인했다. 테스트, 제품 코드에 `FLUSHDB`, `FLUSHALL` 사용은 없다. 재시도 스케줄러는 `test` 프로필에서 꺼져 있다.
- `InactiveMemberAccessIntegrationTest`, `InactiveMemberAccessFilterTest`는 18:03 실행 뒤 다시 실행하지 않았다(그 뒤 `AuthSessionService`, `AuthSessionRepository` 변경). 2번 변경 뒤 전체 백엔드 테스트, 실제 프로필 앱 기동, 운영 복구 검증은 실행하지 않았다.

### V3-1. V3 1번 Redis 연결 설정 실행 기록 (2026-10-20, 로컬)

> 브랜치 `auth/improvement/v3`, 기준 HEAD `d1a15ec` + 미커밋 변경. 리뷰어가 `backend/build/test-results/test`의 XML을 직접 확인했고 재실행하지 않았다.

| 실행 | 시각 (KST) | 전체 | 통과 | 실패 | 오류 | 건너뜀 |
| --- | --- | --- | --- | --- | --- | --- |
| `RedisConnectionIntegrationTest` 단독 (Testcontainers `redis:7.4.11-alpine`) | 14:34:42 | 1 | 1 | 0 | 0 | 0 |

- 저장, 조회, 만료(500ms TTL)를 별도 Redis 컨테이너로 확인했다. Spring Boot의 Redis 자동 설정, `application.yml` 바인딩은 이 테스트의 범위가 아니다.
- 실행은 관련 소스 수정(14:33:55) 이후다. `.env.example`(주석)은 실행 뒤 14:35:12에 바뀌었다.
- 스타터 추가 후 기존 `@SpringBootTest` 컨텍스트와 전체 백엔드 테스트는 실행하지 않았다. 인증은 아직 DB 기반이다.

---

## 0-1. V2 1번 이슈 실행 기록 (2026-10-18, 로컬)

> 구현은 이후 브랜치 `auth/improvement/v2`의 커밋 `e671c475be90c90804712a1866e708bc7d145432`로 고정됐다(로컬 커밋 완료, 미Push·미병합·미배포). 아래는 커밋 전 작업 트리(기준 `16b9ead` + V2 1번 변경)에서 Codex가 실행한 결과를 리뷰어가 JUnit XML로 다시 집계한 것이다. dev HEAD의 결과가 아니며, 이 문서 갱신 중에 재실행하지 않았다. 원시 결과는 임시 디렉터리에 있어 영구 보관되지 않는다.

| 실행 | 시각 (KST) | 전체 | 통과 | 실패 | 오류 | 건너뜀 |
| --- | --- | --- | --- | --- | --- | --- |
| 수정 전 첫 전체 실행 | 15:27:54~15:34:11 | 995 | 936 | 49 | 0 | 10 |
| 최종 전체 실행 | 15:37:25~15:43:38 | 995 | 985 | 0 | 0 | 10 |
| UTC 선택 실행 (5개 클래스, 테스트 JVM `user.timezone=UTC`) | 16:01:12~16:01:36 | 68 | 68 | 0 | 0 | 0 |
| 마지막 보완 이후 `AuthControllerTest` 선택 실행 | 16:10:16 | 25 | 25 | 0 | 0 | 0 |
| **2번 이슈** 관련 6개 클래스 선택 실행 (미커밋 작업 트리) | 16:44:20~16:44:56 | 37 | 33 | 0 | 0 | 4 |
| **2번 이슈** 전체 실행 (126개 스위트, 미커밋 작업 트리) | 16:45:20~16:51:52 | 999 | 995 | 0 | 0 | 4 |
| **3번 이슈** 관련 5개 클래스 선택 실행 (미커밋 작업 트리) | 17:25:26~17:26:13 | 47 | 46 | 0 | 0 | 1 |
| **3번 이슈** 마이그레이션 테스트 수정 후 `AuthSessionMigrationIntegrationTest` (미커밋 작업 트리) | 17:36:53 | 2 | 2 | 0 | 0 | 0 |
| **4번 이슈** 선택 실행 (`LoginRateLimitIntegrationTest` 7, `AuthSessionMigrationIntegrationTest` 2, `AuthControllerTest` 3개 메서드, `V2SecurityRedTest` 1개 메서드; 미커밋 작업 트리) | 17:55:25~17:56:02 | 13 | 13 | 0 | 0 | 0 |
| **4번 이슈** 영향 클래스 전체 실행 (`AuthControllerTest` 25, `V2SecurityRedTest` 15, `PasswordResetControllerTest` 1; 미커밋 작업 트리) | 18:04:04~18:04:28 | 41 | 41 | 0 | 0 | 0 |

- 집계 기준: XML `tests`에 건너뜀이 포함된다(통과 = tests − failures − errors − skipped).
- 첫 실행의 실패 49개: `AdminStatsControllerIntegrationTest` 35, `ReservationSearchSecurityIntegrationTest` 9, `StatsControllerIntegrationTest` 5.
- 건너뜀 10개는 모두 `V2SecurityRedTest`의 `@Disabled`(V2 2~4번 이슈 범위). 상세는 [인증 보안 테스트](auth-security-tests.md).
- 그 전에 보고된 987/871/116/10 실행은 아티팩트가 없어 확인하지 못했다.
- UTC 선택 실행은 `JwtProviderTest`가 출력한 `AUTH_TEST_JVM_ZONE_ID=UTC`로 테스트 JVM 시간대를 확인했다. 전체 실행 이후 테스트 4개의 이름·주석·시간대 기록이 바뀌었고, 그중 `AdminSpaceSecurityIntegrationTest`(이름만 변경)는 바뀐 뒤 다시 실행되지 않았다.
- 995개 전체 실행과 UTC 68개 선택 실행은 마지막 예외 처리 보완(`TransactionException`) **이전** 코드 기준이다. 보완 이후에는 `AuthControllerTest` 25개 선택 실행만 통과했다(2026-10-18 16:10 KST, 커밋 직전 작업 트리). 보완 이후 전체 실행은 없다.
- 2번 이슈 두 실행은 1번 커밋 위의 미커밋 2번 변경 기준이며, 마지막 소스 수정(16:43:39) 이후 실행이다(파일 시각 기준). 남은 `@Disabled` 4개: RT 재사용 불가·RT Rotation 후 계속 갱신·동일 RT 동시 사용 1회 성공(3번 이슈), 반복 로그인 실패 429(4번 이슈).
- 3번 이슈 실행은 마지막 소스 수정(17:24:32) 이후다. 남은 `@Disabled` 1개는 반복 로그인 실패 429(4번 이슈). `AuthSessionMigrationIntegrationTest`의 Flyway 버전 단언 차단 사항은 수정 후 단독 실행(2개 통과)으로 해결됐다. 두 3번 실행은 별도 선택 실행이며 합쳐서 전체 회귀 통과로 보지 않는다. `V2SecurityRedTest`는 이후 표시 이름·주석만 바뀌고 다시 실행되지 않았다. **3번 변경 이후 전체 테스트는 실행하지 않았다.**
- V2 4번 이슈는 로컬 구현 커밋 `ee39f91`(미Push·미병합·미배포)으로 고정됐다. 첫 실행은 메서드 단위 선택 실행이었고, 이후 영향 클래스 3개를 클래스 전체로 실행해 41개 통과했다(마지막 소스 수정 17:55:03 이후). 두 실행은 별도 기록이다. `V2SecurityRedTest` 15개 전체 통과는 이 클래스의 결과이며, **4번 변경 뒤 전체 백엔드 테스트는 실행하지 않았다.** 테스트 소스에 남은 `@Disabled`는 없다.
- 이 결과는 로컬 테스트 근거이며 실제 배포 환경 검증과는 별개다.

---

## 0. V1 인증 고도화 PR 실행 기록 (2026-10-15, CI)

> 출처: [인증 보안 테스트](auth-security-tests.md) §2, [V1 인증 문서 지도](../plans/auth/README.md) §5. 테스트별 의미와 V2 통과 기준은 `auth-security-tests.md`를 단일 기준으로 둔다.

| 항목 | 기록 |
| --- | --- |
| 대상 | PR #302 head, 브랜치 `auth/v1-frontend-integration`, 커밋 `5c60fa444dfc427016954fdacc44d3dfd293c004` |
| 실행 | GitHub Actions 백엔드 CI — [실행 #38684148319](https://github.com/prgrms-be-devcourse/NBE12-14-2-OVENGERS/actions/runs/38684148319) |
| 결과 | 전체 694 / 통과 682 / **실패 11** / 건너뜀 1 |
| 실패 내용 | 11개 모두 `com.ovengers.slotkey.auth.controller.V2SecurityRedTest`의 의도적 보안 단언 실패(로그아웃·재설정·정지·탈퇴 후 기존 토큰 거부, RT 재사용·회전·동시 사용, 반복 로그인 실패 429). 일반 회귀 실패는 기록되지 않음 |
| 함께 기록된 V1 회귀 | `PasswordResetIntegrationTest` 5개 통과, `EmailSignupIntegrationTest`·`VerificationMemberStatusIntegrationTest`는 실패 목록에 없음 |

**당시 실행과 현재 설정의 구분**

| 구분 | 커밋 | `V2SecurityRedTest` 11개 |
| --- | --- | --- |
| 위 CI 실행 | PR head `5c60fa4` | `@Disabled` 없음 → 11개 실패 |
| 현재 소스 | dev 병합본 `46526a5` 이후 (HEAD `16b9ead`까지 확인) | 11개 모두 `@Disabled("V2 구현 전까지 CI에서 비활성화")` → 실행 시 건너뜀으로 집계 |

병합 후의 전체 실행 수치는 기능별 결정 문서에 기록돼 있다(예: [outbox-kafka.md](../decisions/outbox-kafka.md)의 `./gradlew cleanTest test` 814 tests, 실패 0, 건너뜀 11 — 11건은 `V2SecurityRedTest`의 `@Disabled`). 이 기록들은 각 기능 작업 시점의 결과이며 현재 HEAD 결과가 아니다. 따라서 694/682/11/1을 현재 결과로 쓰지 않는다.

---

## 1. 로컬 전체 테스트 실행 결과 (2026-09-24 기록)

- **실행 일시**: 2026-09-24 12:16 KST
- **실행 명령**:
  ```bash
  JAVA_HOME=/opt/homebrew/opt/openjdk ./gradlew test --rerun-tasks --no-daemon
  ```
- **실행 환경**: Mac OS X (aarch64), JDK 21 (OpenJDK 21.0.12.1 Toolchain), Docker MySQL 8.4 Testcontainers
- **실행 소요 시간**: 2분 7초 (`BUILD SUCCESSFUL in 2m 7s`)
- **실행 결과 요약**:
  - **테스트 소스 클래스**: 62개 (지원 클래스 5개 제외, 유효 57개 + 빈 클래스 5개)
  - **실행된 XML 테스트 클래스**: **57개**
  - **총 실행 테스트 케이스**: **354개**
  - **실패 (Failures)**: **0**
  - **에러 (Errors)**: **0**
  - **스킵 (Skipped)**: **0**
  - **최종 판정**: **전원 통과 (ALL PASSED)**

### 도메인별 테스트 현황 (2026-09-24 실측)

| 도메인 | 클래스 수 | 테스트 수 | 주요 검증 범위 |
| --- | --- | --- | --- |
| **reservation** | 21 | 124 | HOLD 임시 확보, 결제, 연장, 취소, 체크아웃, 만료 스케줄러, 노쇼 배치, 슬롯 동시성(단일 슬롯, 인접 슬롯, 부분 겹침, 취소 경합, 멱등 결제 경합) |
| **space** | 15 | 112 | 공간 CRUD, 운영시간 정책, 슬롯 가용성, 관리자 보안 필터, 대표 사진 파일 저장/롤백, 5MB 크기 제한(413 `IMAGE_SIZE_EXCEEDED`), 이미지 스트리밍 GET 200, OpenAPI/Swagger 문서화 |
| **access** | 5 | 33 | 출입 토큰 발급/검증/폐기, 시간 경계(시작 15분 전/후), 소유권 인가, 동시 발급 경합 |
| **audit** | 6 | 25 | 감사 로그 단위 기록(`AuditLogServiceTest`), `MANDATORY` 트랜잭션 전파(`AuditLogServiceTransactionTest`), 관리자 감사 로그 페이징 조회 API, 보안 인가 체인, 필터 쿼리 (※ 회원 상태·크레딧 동반 커밋 및 감사 실패 시 비즈니스 롤백은 `member` 패키지의 `AdminMemberServiceIntegrationTest`에서 검증, 비즈니스 예외 시 선행 감사 롤백은 미작성) |
| **credit** | 3 | 22 | 관리자 크레딧 지급, 크레딧 거래 단위 생성(`balanceAfter` 검증), 회원 상태 변경과 크레딧 증가 동시 경합 시 최신 잔액 보존 (동시 차감 경합 및 SUM 무결성 DB 검증은 미구현) |
| **auth** | 2 | 19 | 로그인/로그아웃, Refresh 재발급, 정지 계정 토큰 만료 전 API 허용 및 재발급 차단 (2026-09-24 시점. V1 이메일 인증 테스트는 이후 PR #302에서 추가 — §0) |
| **member** | 2 | 16 | 관리자 회원 목록/상세 조회, 상태 변경(ACTIVE/SUSPENDED), 자기 자신 크레딧 지급 거절(`SELF_GRANT_NOT_ALLOWED`), 인가 필터, 회원 변경·크레딧 지급과 감사 로그 동반 커밋 및 감사 저장 실패(SpyBean 예외 주입) 시 비즈니스 롤백 실 DB 검증(`AdminMemberServiceIntegrationTest`) |
| **global** | 3 | 3 | CORS 설정, JPA Auditing, JwtProvider 토큰 생성 및 파싱 |
| **합계** | **57** | **354** | **실패 0 / 에러 0 / 스킵 0** |

---

## 2. 빈 테스트 클래스 (2026-10-27 갱신: 모두 삭제)

2026-09-24 실측 당시 소스 코드가 존재하지만 내부 `@Test` 메서드가 없는 빈 클래스(`public class ... {}`)가 5개 있었고, JUnit 5 실행 대상에서 제외되어 XML 결과가 생성되지 않았습니다. 2026-10-27에 5개를 모두 삭제했습니다(동작·테스트 수 변화 없음, 아래 "삭제됨" 표시). 남아 있던 2개는 auth·member 테스트 Kotlin 전환 때 함께 삭제했습니다.

1. `com.ovengers.slotkey.SlotKeyApplicationTests`: 스프링 부트 기본 컨텍스트 로드 테스트 클래스 (미작성 상태) — **삭제됨(2026-10-27).** 컨텍스트 로드는 `IntegrationTestSupport`를 쓰는 통합 테스트들이 이미 검증한다.
2. `com.ovengers.slotkey.auth.service.AuthServiceTest`: 인증 서비스 단위 테스트 (통합 컨트롤러 테스트 `AuthControllerTest`에서 대체 검증 중) — **삭제됨(2026-10-27).** 인증 흐름은 `AuthControllerTest`, `V2SecurityRedTest`가 검증한다.
3. `com.ovengers.slotkey.member.authorization.MemberAuthorizationServiceTest`: 회원 인가 서비스 단위 테스트 — **삭제됨(2026-10-27).** `MemberAuthorizationService`는 관리자 회원 WebMvcTest에서 실제 빈으로 검증한다.
4. `com.ovengers.slotkey.payment.gateway.MockPaymentGatewayTest`: PG 연동 모의 테스트 — **삭제됨(2026-10-27).** 대응하는 `payment` 운영 패키지가 없고, PG는 `credit/pg`의 구현과 `FakePgClient`로 검증한다.
5. `com.ovengers.slotkey.payment.service.PaymentServiceTest`: 결제 서비스 단위 테스트 — **삭제됨(2026-10-27).** 위와 같은 이유.

---

## 3. 과거 로컬 실행 기록 (2026-09-22 스냅샷 보존)

> 다음 수치는 2026-09-22 당시 로컬 실행 결과(`backend/build/test-results/test/*.xml`, 실행 시각 2026-09-21 03:29 UTC)의 역사적 기록 스냅샷입니다.

- **테스트 소스 클래스**: 55개, `@Test` 메서드 298개
- **당시 실행 클래스/테스트**: 42개 클래스, 258개 테스트 (실패 0 / 에러 0 / 스킵 0)
- **당시 미실행 클래스 (13개)**: 9/21~9/22 추가되었던 감사 로그(4), 동시성(4), 관리자 회원(2), 결제(2), 인증(1) 관련 테스트들.
- **2026-09-24 재실행 비교**: 당시 미실행 13개 중 유효 테스트를 가진 8개 클래스 및 추가된 공간 이미지 관련 4개 클래스를 포함하여 57개 클래스, 354개 테스트로 전체 정상 통합 실행 완료됨.

---

## 4. 알려진 제약 및 미검증 범위

1. **Docker 및 Testcontainers 의존성**:
   - 비관적 락, UNIQUE 제약 및 동시성 통합 테스트는 실제 Docker MySQL 8.4 컨테이너가 필수적입니다. Docker 데몬이 구동되지 않는 환경에서는 실행이 불가합니다.
2. **회원 1:1 문의 (Inquiry) 백엔드 자동화 테스트 부재**:
   - V10 마이그레이션 및 문의 API/화면은 구현되었으나, 백엔드 전용 단위/통합 테스트는 아직 작성되지 않은 미검증 상태입니다.
3. **프론트엔드 자동화 테스트의 범위와 한계**:
   - 프론트엔드는 Node.js 내장 test runner(`node --test tests/*.test.cjs`)를 통해 컴포넌트 정적 렌더링, 시간 포맷팅(`HH:mm`), 검색 필터, 문의 API 연동 규격을 검증하는 **14개 테스트가 통과**되어 있습니다 (이 14개는 백엔드 JUnit XML의 354개 수치와 별개로 집계).
   - 반면 Jest/Vitest 통합 테스트 환경, Playwright/Cypress 브라우저 E2E 테스트, 실제 브라우저 UI 상호작용 자동화 검증은 부재합니다.
4. **CI 및 외부 부하 테스트 (k6) / 브라우저 Swagger UI**:
   - 본 수치는 로컬 전체 실행 실측치이며, k6 부하 테스트 및 브라우저 환경에서의 Swagger UI multipart 업로드 실사용은 별도 미실시 상태입니다.
5. **세부 복합 시나리오 통합 테스트 부재**:
   - 상태 전이 불가 6종 조합 전수 매트릭스 검증, 사용자 일반 취소의 동일 예약 2회 순차 재취소 검증, 이용 시작 시각 정각/경과 후 취소 시도 거절 시간 경계 검증, 취소 시 REFUND·PENALTY 원장 2건 동시 영속성 검증, 관리자 강제 취소 ↔ 사용자 연장 동시 경합(데드락 부재) 검증, 비즈니스 로직 예외 발생 시 감사 로그 동반 롤백 검증, 스케줄러 자동 체크아웃의 `checked_out_at = end_time` DB 영속성 검증, 노쇼 시 크레딧 잔액 미환불 DB 영속성 검증, 일반 회원의 관리자 크레딧 지급 API(`POST /api/v1/admin/members/{memberId}/credits`) 호출 차단 검증, 타인 예약 조회 API 소유권 검증, 가격 변경 후 기존 확정 예약 조회 시 원래 금액 유지 복합 시나리오 검증은 엔티티/조건부 UPDATE/권한 필터 설계로 방어되나 전용 통합 테스트는 미작성 상태입니다.
