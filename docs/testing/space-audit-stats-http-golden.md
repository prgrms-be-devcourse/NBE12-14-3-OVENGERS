# 공간·감사·통계 대표 HTTP 본문 골든

> Issue [#192](https://github.com/prgrms-be-devcourse/NBE12-14-3-OVENGERS/issues/192). 상위 #185.
> 최초 검토·143건 실행 기준 HEAD `1ce6f7fe90b0f406eba8f59145c22fe2e2d01728`, branch `test/space-audit-stats-http-golden`.
> 실행 코드를 바꾸지 않는 테스트 보강 작업의 사례·근거·한계를 기록한다. fix round 1 반영.

## 1. 목적과 범위

세 도메인(Space·Audit·Stats)의 **대표 HTTP 응답 본문**(성공·오류)을 실제 MockMvc 응답과 정적 JSON으로 비교하고, HTTP 상태·인증/인가·오류 매핑을 함께 단언한다. 기존 `@JsonTest` DTO 골든 16개(공간 6·감사 3·통계 7)와 기존 컨트롤러/보안·DB 통합 테스트를 **재사용**하고, HTTP 계층이 새로 보호하는 부분만 최소로 추가한다.

- 추가: 도메인별 실제 Spring MVC 슬라이스 테스트 3개 + 새 HTTP 본문 골든 8개(성공 5·오류 3).
- 제외: 운영 코드, 공통 지원/보안/예외/설정/build.gradle/CI, 기존 16개 DTO 골든 변경, 전 엔드포인트 골든.

## 2. 사례표

| 상황 | 입력·고정 fixture | 엔드포인트 | 골든 | 테스트 |
| --- | --- | --- | --- | --- |
| 공간 정상 상세 | id 11, 판교 A룸, description·imagePath 존재, version 3 | `GET /api/v1/spaces/11` | `golden/space/space-detail-http-success.json` | `SpaceHttpBodyGoldenTest` |
| 공간 선택 null 상세 | 판교 B룸, description·imagePath null | `GET /api/v1/spaces/11` | `golden/space/space-detail-http-null-optionals.json` | 〃 |
| 공간 생성 필수값 거절 | name 누락 JSON | `POST /api/v1/admin/spaces` | `golden/space/space-create-missing-name-error.json` | 〃 |
| 관리자 경로 익명 차단 | 무토큰 | `POST /api/v1/admin/spaces` | (오류 본문, 상태/코드 단언) | 〃 |
| 감사 페이지(2건) | id100 정상 + id101 actor/reason/before/after null | `GET /api/v1/admin/audit-logs` | `golden/audit/audit-log-page-http-multi.json` | `AuditHttpBodyGoldenTest` |
| 감사 익명 차단 | 무토큰 | 〃 | (오류 본문, 상태/코드 단언) | 〃 |
| 통계 공개 랭킹(매출 비노출) | DAILY, date 2026-10-14, metric 기본 RESERVATION_COUNT | `GET /api/v1/stats/space-ranking` | `golden/stats/public-space-ranking-http-success.json` | `StatsHttpBodyGoldenTest` |
| 통계 공개 NET_REVENUE 거절 | metric=NET_REVENUE | 〃 | `golden/stats/public-space-ranking-net-revenue-error.json` | 〃 |
| 통계 관리자 랭킹 | DAILY, date 2026-10-14, 기본 NET_REVENUE | `GET /api/v1/admin/stats/space-ranking` | `golden/stats/admin-space-ranking-http-success.json` | 〃 |
| 통계 필수 period 누락 | date만 전달 | 〃 | `golden/stats/admin-space-ranking-missing-period-error.json` | 〃 |

제외: 공간 목록·슬롯, 관리자 공간 목록/수정/이미지, 감사 필터 세부, 지역 랭킹, 상태 건수 등은 각각 기존 컨트롤러·통합·DTO 골든이 이미 확인하므로 중복을 피해 제외했다.

## 3. 검증 범위와 경계 (실제로 무엇을 증명하는가)

- **실제 포함:** Spring MVC 슬라이스(`@WebMvcTest`)의 운영 `ObjectMapper`(Jackson `JavaTimeModule`, `NON_NULL` 봉투), Bean Validation, `GlobalExceptionHandler`, `SecurityConfig` 필터 체인(`CustomAuthenticationFilter` + 세션 검증), `@JsonFormat`/enum/number 직렬화.
- **대역(mock):** 도메인 서비스(공간 조회·관리자 공간·감사 조회·통계 랭킹)는 `@MockBean`. 따라서 **JPA 영속성·Repository 쿼리·MySQL 정렬·기간 경계 계산은 이 테스트가 검증하지 않는다.**
- **DB·보안 통합 근거:** `AdminSpaceSecurityIntegrationTest`, `AdminAuditLogSecurityIntegrationTest`(실제 필터체인+DB), `StatsControllerIntegrationTest`, `AdminStatsControllerIntegrationTest`(Testcontainers MySQL)가 담당한다. 이번 fix 실행(§6)에 이 클래스들을 포함해 통과를 확인했다. 단, **`AuditLogRepositoryTest`는 이번 실행에 포함하지 않았다**(그 클래스를 실행했다고 주장하지 않는다).
- 응답 모의 테스트를 DB 롤백·영속성 검증으로 표현하지 않는다.

## 4. Java 기준(provenance)

두 가지 Java 근거를 분리해 기록한다.

1. **(fix round 1, HTTP 경로) 실제 Java 컨트롤러 MockMvc 비교 — 완료.** 전환 전 Java 커밋 `a380133082fc323ed52d933799aa040ad2107b49`를 `git archive a380133 backend`로 임시 디렉터리(`/tmp/issue192-java`)에 풀고(현재 checkout 불변), 그 안에 임시 `@WebMvcTest`(`Issue192JavaHttpBaselineTest`)를 추가했다. 이 테스트는 **a380133의 실제 Java 컨트롤러**(`SpaceController`·`AdminSpaceController`·`AdminAuditLogController`·`StatsController`·`AdminStatsController`)를 운영 보안/검증/예외 구성과 함께 MockMvc로 호출하고, **현재 Kotlin 테스트와 동일한 정적 골든 8개**와 비교한다. 결과: phase2 `6 tests, 0 failures/errors/skipped`, `BUILD SUCCESSFUL`, `EXIT=0` — 즉 5개 성공 + 3개 오류 본문이 Java HTTP 경로 출력과 동일하다. 오류 3개 골든은 phase1 캡처(비교 비활성)로 얻은 실제 Java 응답 바이트를 그대로 저장한 뒤, phase2에서 그 파일과 비교해 일치를 확인했다(매 실행마다 기대 파일을 덮어쓰지 않는다).
2. **(attempt1, DTO 직렬화 보조 근거) `@JsonTest` 캡처 — 보조로 유지.** `Issue192JavaBaselineTest`(`@JsonTest`)가 `objectMapper.writeValueAsString(ApiResponse.success(<JavaDTO>))`로 DTO 봉투를 캡처한 것은 DTO 필드 직렬화의 보조 근거로만 인정한다.

- 증거: `steps/02-fix/1/evidence/java-http-a380133/`(임시 테스트 소스, phase1/phase2 로그·XML, 캡처 바이트, `PROVENANCE.txt`), `evidence/java-a380133/`(attempt1 보조 캡처).
- 임시 아카이브/테스트는 저장소에 커밋하지 않으며, 현재 checkout/HEAD·운영 소스는 변경하지 않았다.
- 본 작업 자체로 제품 정책을 새로 만들지 않았고, #186 삭제 공간 정책과도 무관하다.

## 5. 고정값과 동적 필드

- ID·날짜·시각·enum·금액·정렬 순서를 모두 고정했다(`id=11`, `date=2026-10-13~14`, `computedAt=2026-10-14T00:01:00`, `createdAt=2026-10-14T10:15:30/10:20:00` 등).
- 순수 동적 값은 없다. 인증 토큰의 `exp`만 실행 시 생성되지만 응답 본문에 포함되지 않는다.
- 배열 정렬, null 제거, 숫자 문자열화로 차이를 숨기지 않는다. 선택 null 필드의 존재와 null을 별도 단언한다.

## 6. 실행 근거

환경 접두부(모든 실행 동일): `env -u SPRING_MAIL_USERNAME -u SPRING_MAIL_PASSWORD -u VERIFICATION_MAIL_FROM -u SLOTKEY_KAFKA_ENABLED JAVA_HOME=/usr/lib/jvm/java-21-openjdk`.

### attempt1 (최초 구현)

| id | 시각(KST) | 대상 | 종료 | 집계 |
| --- | --- | --- | --- | --- |
| run1 | 14:05 | 신규 HTTP 골든 3클래스 | 1 | 10중 2 실패(matcher 오류) |
| run2 | 14:06 | 〃 | 1 | 10중 2 실패 → matcher 제거 |
| run3 | 14:07 | 신규 3클래스 | 0 | 10/0/0/0 |
| run4 | 14:08 | **관련 기존 10클래스만**(신규 3 미포함): DTO 골든 3 + 컨트롤러 3 + 보안·통합 4 | 0 | 133/0/0/0 |
| run5 | 14:10 | `SpaceHttpBodyGoldenTest` 재실행(불필요 import 제거 후) | 0 | 4/0/0/0 |
| ktlint | 14:10 | `:ktlintTestSourceSetCheck` | 0 | — |
| diff | 14:10 | `git diff --check` | 0 | — |

- run4 클래스별: `SpaceGoldenTest` 6, `AuditGoldenTest` 3, `StatsGoldenTest` 7, `SpaceControllerTest` 14, `AdminSpaceControllerTest` 39, `AdminAuditLogControllerTest` 4, `AdminSpaceSecurityIntegrationTest` 11, `AdminAuditLogSecurityIntegrationTest` 4, `StatsControllerIntegrationTest` 8, `AdminStatsControllerIntegrationTest` 37. **`AuditLogRepositoryTest`는 포함되지 않았다.**
- attempt1 XML: `evidence/run{3,4,5}-*/`.

### fix round 1

| id | 시작–종료(KST) | 대상 | 종료 | 집계 | 원시 로그/XML |
| --- | --- | --- | --- | --- | --- |
| java-http-phase1 | ~14:17 | a380133 임시 `@WebMvcTest`(오류 비교 비활성, 캡처) | 0 | BUILD SUCCESSFUL | `steps/02-fix/1/evidence/java-http-a380133/http-baseline-phase1.log` |
| java-http-phase2 | ~14:18 | 〃 (성공5+오류3 정적 골든 비교 활성) | 0 | 6/0/0/0 | `.../http-baseline-phase2.log`, `.../phase2-TEST-Issue192JavaHttpBaselineTest.xml` |
| run-fix1-all | 14:19:38–14:20:57 | 신규 3 + 관련 기존 10 = 13클래스 | 0 | 143/0/0/0 | `steps/02-fix/1/evidence/run-fix1-all/`(gradle.log, meta.txt, XML) |
| ktlint | 14:21 | `:ktlintTestSourceSetCheck` | 0 | — | `run-fix1-ktlint.log`, `run-fix1-ktlint.meta.txt` |
| diff | 14:21 | `git diff --check` | 0 | — | `run-fix1-diffcheck.log` |

run-fix1-all 클래스별(143): `SpaceHttpBodyGoldenTest` 4, `AuditHttpBodyGoldenTest` 2, `StatsHttpBodyGoldenTest` 4, `SpaceGoldenTest` 6, `AuditGoldenTest` 3, `StatsGoldenTest` 7, `SpaceControllerTest` 14, `AdminSpaceControllerTest` 39, `AdminAuditLogControllerTest` 4, `AdminSpaceSecurityIntegrationTest` 11, `AdminAuditLogSecurityIntegrationTest` 4, `StatsControllerIntegrationTest` 8, `AdminStatsControllerIntegrationTest` 37. 소스 해시: `run-fix1-source-hashes.txt`.

로컬 전체 클린 빌드·필터 없는 `test`는 현재 최소 타깃 테스트 정책상 필수로 요구하지 않으며 실행하지 않았다. PR CI는 이 단계에서 생성하지 않는다.

### 전체 재현 명령

다음 명령은 독립 검토자가 성공 XML의 13개 클래스를 기준으로 구성한 **동등 범위의 재현 명령**이다. 원본 메타의 `13 filters`·`env -u ...`는 축약 표기이며, 아래 내용을 보존된 원본 argv로 주장하지 않는다. 실행 성공·실제 범위는 Gradle 원시 성공 로그, 종료 코드/시각 메타, 13개 XML과 검증 소스 해시로 확인했다.

저장소의 `backend/`에서:

```bash
env -u SPRING_MAIL_USERNAME -u SPRING_MAIL_PASSWORD -u VERIFICATION_MAIL_FROM -u SLOTKEY_KAFKA_ENABLED JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  ./gradlew test \
    --tests 'com.ovengers.slotkey.audit.AuditGoldenTest' \
    --tests 'com.ovengers.slotkey.audit.controller.AdminAuditLogControllerTest' \
    --tests 'com.ovengers.slotkey.audit.controller.AdminAuditLogSecurityIntegrationTest' \
    --tests 'com.ovengers.slotkey.audit.controller.AuditHttpBodyGoldenTest' \
    --tests 'com.ovengers.slotkey.space.SpaceGoldenTest' \
    --tests 'com.ovengers.slotkey.space.controller.AdminSpaceControllerTest' \
    --tests 'com.ovengers.slotkey.space.controller.AdminSpaceSecurityIntegrationTest' \
    --tests 'com.ovengers.slotkey.space.controller.SpaceControllerTest' \
    --tests 'com.ovengers.slotkey.space.controller.SpaceHttpBodyGoldenTest' \
    --tests 'com.ovengers.slotkey.stats.StatsGoldenTest' \
    --tests 'com.ovengers.slotkey.stats.controller.AdminStatsControllerIntegrationTest' \
    --tests 'com.ovengers.slotkey.stats.controller.StatsControllerIntegrationTest' \
    --tests 'com.ovengers.slotkey.stats.controller.StatsHttpBodyGoldenTest'
```

Java 기준의 임시 `backend/`에서:

```bash
env -u SPRING_MAIL_USERNAME -u SPRING_MAIL_PASSWORD -u VERIFICATION_MAIL_FROM -u SLOTKEY_KAFKA_ENABLED \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  ./gradlew test --tests 'com.ovengers.slotkey.issue192.Issue192JavaHttpBaselineTest'
```

Java phase2 XML의 테스트 시작 시각은 `2026-10-27T05:18:09Z`(KST 14:18:09)이며, 셸 명령 시작 시각과 구분한다. 현재 Kotlin 검증 143건과 역사적 Java 검증 6개 메서드(본문 8개)는 서로 다른 실행이다. 반복 실행 수를 고유 테스트 수에 더하지 않는다.

## 7. 기존 16 DTO 골든 보존

기존 16개 JSON은 **수정하지 않았다**(`git status`에 나타나지 않음, 관련 골든 테스트 16건 재통과). 새로 추가한 8개만 HTTP 봉투 포함 본문이다. 새 HTTP 골든의 `data`가 기존 DTO 골든과 값까지 항상 동일한 것은 아니다 — 예: 감사 HTTP 골든은 2건 페이지이고, 공개 통계 HTTP 골든은 `metric=RESERVATION_COUNT` fixture로 기존 `public-space-ranking.json`과 `metric`·항목 수가 다르다. 형식·필드·타입만 재사용하고 개별 사례 값은 각자 고정한다.

## 8. 변경 파일

새 파일(운영 코드 무변경):

- `backend/src/test/kotlin/com/ovengers/slotkey/space/controller/SpaceHttpBodyGoldenTest.kt`
- `backend/src/test/kotlin/com/ovengers/slotkey/audit/controller/AuditHttpBodyGoldenTest.kt`
- `backend/src/test/kotlin/com/ovengers/slotkey/stats/controller/StatsHttpBodyGoldenTest.kt`
- `backend/src/test/resources/golden/space/space-detail-http-success.json`
- `backend/src/test/resources/golden/space/space-detail-http-null-optionals.json`
- `backend/src/test/resources/golden/space/space-create-missing-name-error.json`
- `backend/src/test/resources/golden/audit/audit-log-page-http-multi.json`
- `backend/src/test/resources/golden/stats/public-space-ranking-http-success.json`
- `backend/src/test/resources/golden/stats/public-space-ranking-net-revenue-error.json`
- `backend/src/test/resources/golden/stats/admin-space-ranking-http-success.json`
- `backend/src/test/resources/golden/stats/admin-space-ranking-missing-period-error.json`
- `docs/testing/space-audit-stats-http-golden.md`

## 9. 남은 한계·미검증

- 서비스 대역 때문에 실제 DB·쿼리·정렬·기간 계산은 이 테스트로 증명하지 않는다(§3의 통합 테스트가 담당).
- 2026-10-27 14:27 KST Codex 독립 검토 **PASS**: 현재 소스·정적 JSON·실행 로그/XML·Java 재현 소스와 원본 커밋을 대조했다. 본문 8개 Java HTTP 비교, Kotlin 타깃 143건, ktlint 성공 및 기존 16개 골든 보존을 확인했다. 전체 애플리케이션·전 엔드포인트 보장을 뜻하지 않는다.

## 10. 게시 전 최신 dev 통합 확인

최초 검토 이후 `dev`의 #206(`9fd9e35`)을 반영했다. 변경은 auth·member·inquiry 테스트 전환과 테스트 의존성 `mockito-kotlin` 추가이며, 이번 대상 운영 코드·기존 테스트·16개 골든 및 신규 테스트/JSON은 그대로다.

통합 검증 HEAD `d35750eb07c36b1876809d0143acf8bb5e0fa8ae`에서 신규 HTTP 테스트3클래스만 다시 실행해 **10 tests, 실패/오류/스킵 0**, exit0을 확인했다(KST 2026-10-27T14:29:13.792671+09:00–2026-10-27T14:30:16.711375+09:00). 테스트 명령은 위 환경 접두부의 `./gradlew test`에 `SpaceHttpBodyGoldenTest`·`AuditHttpBodyGoldenTest`·`StatsHttpBodyGoldenTest`의 전체 클래스명 필터를 각각 적용했다. 원시 명령 argv·로그·XML·소스 해시는 게시 작업 기록에 보존했다.

최초143건은 최초 검토 소스의 결과이고, 이번10건은 최신 dev 통합 후 결과다. 동일 사례 재실행을153개의 고유 테스트로 합산하지 않는다. 이 문서의 게시 기록만 덧붙였으며 테스트/JSON은 검증 소스와 동일하다.
