# 목요일 담당 정책·상태값 Kotlin 전환 보고

> 구현: Codex. 작업 브랜치 `feat/kotlin-thursday-policies`, 시작 커밋 `b263e07`.
> 범위: 2026-10-22 이태호 담당 7개(환불 정책 재검증 + 나머지 6개 전환). 전환 구현과 최신 dev 반영 후 전체 clean build 자기검증을 마쳤다. 초기 ktlint 제약은 아래 PR 준비 기록에서 해소됐다.

## 작업 계획과 판단 기록

- [목요일 계획](../../../plans/kotlin-migration/daily/01-thursday.md)의 `step-1`만 진행한다. 금요일 DTO·서비스 전환으로 넘어가지 않는다.
- 기존 Kotlin 혼용 빌드와 `ReservationRefundPolicy`는 재작성하지 않고 테스트·패키징으로 재검증한다.
- 기존 시간 정책 테스트 14개, PG 오류 분류 테스트 35개는 기대값을 유지하며 Kotlin으로 함께 옮긴다.
- Kotlin이 직접 사용하는 Lombok getter는 해당 경계에서 명시적 Java API로 바꾼다. 엔티티 전체 선행 delombok은 별도 계획이므로 이번 순수 정책 전환에 섞지 않는다.
- Java 정적 호출은 `object` + `@JvmStatic`, `ReservationTargetInfo`는 `@JvmRecord data class`로 유지한다. Java `Long` 프로젝션 생성자와 null 허용을 보존하기 위해 `Long?`를 쓴다.
- `OutboxPublishException`은 Java 원본의 상속 가능성과 null message/cause를 유지한다.
- 전환 전 `clean build`는 기존 인증·회원·문의 Kotlin 소스 7개에서 ktlint 26건으로 실패했다. 범위 밖이므로 수정하지 않고 전체 테스트를 별도로 실행한다.

## ① 변경 파일

운영 Java 6개 삭제 + 같은 패키지의 Kotlin 6개 추가:

- `reservation/policy/ReservationTimePolicy`
- `reservation/entity/ReservationStatus`
- `reservation/dto/ReservationTargetInfo`
- `credit/pg/PgErrorClassifier`
- `outbox/OutboxPublishException`
- `waitlist/entity/WaitlistStatus`

추가 변경:

- `backend/src/main/java/com/ovengers/slotkey/global/error/BusinessException.java`: Lombok import/애노테이션 제거, 같은 시그니처의 getter 명시.
- `ReservationTimePolicyTest`, `PgErrorClassifierTest`: 기존 Java 삭제 + Kotlin 추가. 기존 기대값 유지.
- `backend/src/test/kotlin/com/ovengers/slotkey/reservation/repository/ReservationTargetInfoRepositoryTest.kt`: 실제 DB 프로젝션 회귀 테스트.
- `backend/test-baseline.txt`: 새 테스트 4개를 반영해 1,053 → 1,057.
- 아래 관련 결정 문서와 `docs/testing/test-results.md`: 이번 실행 결과 추가.
- 이 보고 파일. `docs/lab/`는 로컬 `.git/info/exclude`에 있으므로 사용자 PR 요청에 따라 이 파일을 명시적으로 강제 추가해 문서 커밋에 포함한다.

기존 `ReservationRefundPolicy.kt`와 전용 테스트·혼용 빌드 설정은 수정하지 않았다.

## ② 실행한 명령과 결과

- `git status --short --branch`: 시작 시 `dev`, 작업 트리 깨끗함.
- `git switch -c feat/kotlin-thursday-policies`: 성공.
- `env -u SPRING_MAIL_USERNAME -u SPRING_MAIL_PASSWORD -u VERIFICATION_MAIL_FROM -u SLOTKEY_KAFKA_ENABLED ./gradlew --no-daemon clean build`: 종료 1, 기존 ktlint 위반. `/tmp/slotkey-thursday-baseline.log`.
- 전환 전 전체 테스트: 종료 0, 136 스위트·1,053개, 실패/오류/스킵 0. `/tmp/slotkey-thursday-baseline-tests.log`, XML 보존 `/tmp/slotkey-thursday-baseline-xml/`.
- 새 Kotlin 테스트 분리 컴파일: 성공. Java 컴파일을 거친 Kotlin 테스트에서는 기존 Lombok getter도 접근 가능했다. 현재 변경 범위에서 미해결 참조가 발생했다고 해석하지 않는다.
- 나노초 검사 누락 임시 변이: 새 테스트 2개 모두 AssertionError로 실패(종료 1). `/tmp/slotkey-thursday-mutation-red.log`. 임시 변이는 복구했으며 최종 Kotlin에 초·나노초 검사가 모두 있다.
- 전환 후 선택 실행: 6 스위트·70개 통과, 실패/오류/스킵 0, 종료 0. `/tmp/slotkey-thursday-green.log`, XML `/tmp/slotkey-thursday-red-build/test-results/test/`.
- `javap`: 정적 validate/validateExtension/classify, `ReservationTargetInfo(Long, Long)`·spaceId()/memberId(), 비final Outbox 예외 생성자 확인. `/tmp/slotkey-thursday-javap.txt`.
- `jar tf`: 대상 7개·kotlin-stdlib/reflect 1.9.25·jackson-module-kotlin 2.17.2 포함 확인.
- 프론트 `node --test tests/*.cjs`: 80/80 통과, 스킵 0, 종료 0. `npx tsc --noEmit`: 종료 0. 잠금 파일의 의존성을 `npm ci --ignore-scripts`로 설치했으며 프론트 소스·lockfile 변경은 없다.
- 전환 후 전체 `env -u SPRING_MAIL_USERNAME -u SPRING_MAIL_PASSWORD -u VERIFICATION_MAIL_FROM -u SLOTKEY_KAFKA_ENABLED ./gradlew --no-daemon clean compileKotlin compileJava test bootJar`: 종료 0, `BUILD SUCCESSFUL in 7m 53s`. 137 스위트·1,057개 전원 통과, 실패·오류·스킵 0. XML 시각 2026-10-22 15:30:14~15:37:48 KST. `/tmp/slotkey-thursday-full.log`, XML 보존 `/tmp/slotkey-thursday-full-xml/`.
- `python3 .github/scripts/check_test_count.py backend/build/test-results/test backend/test-baseline.txt`: 종료 0, `suites=137 tests=1057 skipped=0 failures=0 errors=0 baseline=1057`.
- 전환 후 필터 없는 `./gradlew --no-daemon --project-cache-dir /tmp/slotkey-thursday-final-cache --init-script /tmp/slotkey-thursday-final.gradle clean build`: 종료 1, 기존 ktlint 26건. init 스크립트는 진행 중인 전체 테스트와 산출물이 겹치지 않도록 build 디렉터리만 `/tmp/slotkey-thursday-final-build`로 바꾼다. 검사·테스트를 제외하지 않았다. `/tmp/slotkey-thursday-clean-build.log`.
- 전환 파일만 ktlint format 적용 후 전체 check: 기존 main/java의 26건만 남음. 새 파일의 위반은 0건. 외부 임시 init 스크립트는 저장소 설정 변경에 포함하지 않는다.

## ③ 가정한 것과 확인하지 못한 것

- 외부 API 규칙을 새로 도입하거나 수정하지 않는다. PG 오류 코드 목록과 판정 우선순위는 기존 구현 그대로 유지한다.
- 공식 `tosspayments-integration-guide` MCP의 `get-documents`로 오류 코드 출처를 재확인했다. [API 에러 코드](https://docs.tosspayments.com/reference/error-codes)(문서 ID 60)에서 24개, [환경 설정하기](https://docs.tosspayments.com/guides/v2/get-started/environment)(문서 ID 12)의 테스트 에러 재현 예시에서 `REJECT_CARD_PAYMENT`를 확인해 코드 내 25개 문자열의 출처를 확인했다. 사용자 거절/가맹점 오류 구분과 조회 우선순위는 기존 프로젝트 정책이며 공식 문서의 자동 분류라고 주장하지 않는다.
- CI·운영 배포·실제 PG/SMTP/Kafka 연결 및 실행 JAR 프로세스의 HTTP 기동은 확인하지 않았다. 통합 테스트의 Spring 컨텍스트 기동과 실행 JAR 생성·포함물 확인까지만 검증했다.
- 기존 `VerificationSecretHasher.kt:16`의 프로퍼티 초기화 관련 Kotlin 컴파일 경고도 남아 있다. 이번 변경 대상이 아니므로 수정하지 않았다.
- 기존 ktlint 오류 파일: `auth/entity/VerificationPurpose.kt`, `auth/mail/SmtpMailCredentialsValidator.kt`, `auth/service/VerificationSecretGenerator.kt`, `auth/service/VerificationSecretHasher.kt`, `inquiry/entity/InquiryStatus.kt`, `member/entity/MemberRole.kt`, `member/entity/MemberStatus.kt` (모두 `backend/src/main/java/com/ovengers/slotkey/` 아래). 총 26건이며 이 파일들은 변경하지 않았다.
- 2026-10-22 사용자의 “pr 올려줘” 요청에 따라 작업 브랜치에서 코드·문서 커밋을 분리하고 `origin`에 push해 `dev` 대상 Draft PR을 생성한다. 머지는 수행하지 않는다.

## ④ 관련 결정 문서

- [Kotlin Lombok 경계](../../../decisions/kotlin-lombok-boundary.md)
- [data class 적용 범위](../../../decisions/kotlin-data-class-scope.md)
- [테스트 보호 장치](../../../decisions/kotlin-test-guardrails.md)
- [Kotlin 플러그인 버전](../../../decisions/kotlin-plugin-version.md)

## 추가한 테스트와 검증 내용

- 예약 시간 초·나노초 정렬: 시작/종료 및 연장 양쪽 경계를 검사한다.
- Kotlin 오류 처리: `BusinessException.errorCode` 접근과 기존 상세 메시지를 확인한다. Java 컴파일 후 Kotlin 테스트에서 getter를 읽을 수 있음을 확인했고, 같은 공개 API를 명시적 메서드로 보존했다.
- JPQL 프로젝션: 실제 MySQL 예약에서 공간/소유자 식별자를 생성자로 조회한다.

## 리뷰가 필요한 설계 판단

- 새로운 설계는 도입하지 않는다. 기존 결정의 정적 호출·record·nullable·Lombok 경계 보존을 검토한다.

## 리뷰 체크리스트

- [x] 대상 7개가 Kotlin이며 동일 클래스 Java 원본이 없다.
- [x] 기존 테스트 49개와 환불 테스트 5개의 기대값·실행 수를 보존했다.
- [x] 예약 시간 null 오류 코드·검사 순서·30분/초/나노초 정렬·동일 날짜·운영시간 검사가 같다.
- [x] PG 코드 목록·이미 처리된 결제/서버 오류/사용자 거절/가맹점 오류의 우선순위가 같다.
- [x] Java 정적 호출과 boxed Long JPQL 생성자·record 접근자가 같다.
- [x] Enum 이름·순서, 예외 message/cause·상속 가능성이 같다.
- [x] Kotlin Lombok 사용이 없고 Java Lombok 의존성은 남은 사용처 때문에 유지했다.
- [x] HTTP·메시지·DB 스키마, 트랜잭션·인증·배포 조건이 바뀌지 않았다.
- [x] 전체 테스트 수·실패·스킵 및 기존 ktlint 문제를 구분해 기록했다. 단, 전체 clean build·CI·독립 검증 완료는 주장하지 않는다.

## 별도 코드 검토

- 별도 세션에서 변경 소스·원본·컴파일된 JVM API를 읽기 전용으로 비교했다. Critical/Important 없음.
- Minor(수정 보류): `ReservationTargetInfo.toString()`은 Java record의 대괄호에서 Kotlin data class의 소괄호 형식으로 바뀐다. 의존 호출부는 검색되지 않았다. 채택된 `@JvmRecord data class` 전환의 진단 문자열 차이이며 HTTP/DB 계약으로 쓰지 않는다.
- 검토자가 범위 밖으로 제외한 동작: 없음.
- 전체 테스트 명령은 구현자가 실행한 **자기검증**이다. 별도 세션 검토자는 테스트를 재실행하지 않았으므로 AGENTS.md의 독립 전체 검증·CI·사람 확인은 남아 있다.

## 사용자 결정이 필요한 질문

- 현재 없음. 초기 ktlint 문제는 dev의 #21로 해소됐다. 독립 전체 검증·CI·사람 리뷰는 여전히 필요하다.

## PR 준비: 최신 dev 반영 (2026-10-22)

- 관련 이슈: [#12 예약 시간 규칙·상태값, PG 오류 분류, 대기열 상태값 Kotlin 전환](https://github.com/prgrms-be-devcourse/NBE12-14-3-OVENGERS/issues/12).
- 사용자 요청: 작업 브랜치에서 커밋·push 후 PR 생성. `dev`에 직접 커밋하거나 머지하지 않는다.
- `git fetch origin dev`에서 `origin/dev`가 `2186ca1`까지 진행된 것을 확인했다. `57462d1`(#21)이 기존 ktlint 오류를 수정했고, `2186ca1`(#24)이 전체 테스트 기준선을 1,078개로 올렸다.
- `git rebase --autostash origin/dev`로 작업 브랜치 기준을 최신 dev로 옮겼다. 자동 보관 적용 중 `backend/test-baseline.txt` 하나만 충돌했고, 양쪽 추가 테스트를 보존해 1,078 + 4 = **1,082개**로 해결했다.
- 자동 보관 `dd44469`는 복구용으로 남겨 두었다. 코드·문서 커밋은 별도로 만든다.
- 최신 소스 전체 `env -u SPRING_MAIL_USERNAME -u SPRING_MAIL_PASSWORD -u VERIFICATION_MAIL_FROM -u SLOTKEY_KAFKA_ENABLED ./gradlew --no-daemon clean build`: **종료 0, BUILD SUCCESSFUL in 8m 54s**. ktlint 운영/테스트 검사 모두 위반 0. 전체 **139 스위트·1,082개 통과, 실패·오류·스킵 0**.
- XML 타임스탬프: 2026-10-22 16:12:05~16:20:34 KST. 로그 `/tmp/slotkey-thursday-pr-build.log`, XML 보존 `/tmp/slotkey-thursday-pr-xml/`.
- 테스트 수 검사: `suites=139 tests=1082 skipped=0 failures=0 errors=0 baseline=1082`, 종료 0.
- 검증한 코드는 `1190d04` (`refactor(kotlin): migrate Thursday policies and value types`)로 고정했다. 결정 문서·테스트 실행 기록·이 보고서는 별도 문서 커밋에 포함한다.
- 최신 dev 반영으로 초기 clean build 차단은 해소됐다. 독립 검증·CI·사람 리뷰가 남아 있으므로 Draft PR로 올린다.
