# 골든 현황 조사 — 4단계: HTTP 본문과 SlotReleased 소비 계약

## 1. 범위·판정

- 역할: 구현자(implementer). 이 문서의 실행·점검은 **자기검증**이다. 독립 검증은 완료됐다: [step-4-verification.md](step-4-verification.md)(통과). CI·사람 리뷰는 이 보고서의 완료 주장에 포함하지 않는다.
- 작업일: 2026-10-27(KST). `dev`의 `0703cc9c44ccd95d2116acb2099ac71ecbe1bba8`에서 `test/golden-http-bodies`를 만들었다.
- 요청된 6항목을 새 Kotlin 테스트 6클래스 **8건**과 공통 웹 테스트 지원 1파일로 추가했다. A의 실제 Java HTTP 본문 3개를 정적 JSON으로 고정했다.
- 운영 코드·기존 테스트·기존 골든·의존성·설정·마이그레이션·API 명세 변경 없음. 기존 추적 파일 수정은 요청된 `backend/test-baseline.txt` 하나다(`1314 → 1322`, 신규 8건 사유 주석 1줄).
- A 3건: 정적 파일 부재 RED → Java 비교 통과 → 동일 파일 Kotlin 비교 통과. B 5건: Kotlin 통과. B의 Java 비교는 요청상 불필요하다.
- 최종 검사: `ktlintCheck`, `clean build -x test`, 전체 `cleanTest test` 모두 종료 0. 전체 **1,361 / 실패 0 / 오류 0 / 스킵 0**, XML 189개. 신규 8건 포함을 클래스별로 확인했다.
- 보류 항목 없음. 커밋·stage·push·배포는 하지 않았다.

## 2. 항목별 대응·Java 기준 근거

아래 골든 경로는 `backend/src/test/resources/golden/` 기준이다. Java 기준은 모두 순수 Java 커밋 `a380133082fc323ed52d933799aa040ad2107b49`이며, 새 Java 테스트·보조 파일만 추가한 별도 worktree에서 실행했다. 서비스 응답은 고정된 Mockito 대역이고, 요청 바인딩·JWT/회원 주입·운영 컨트롤러·운영 응답 포장·Spring MVC 직렬화는 실제 경로다. DB/PG 처리 결과까지 새로 검증한다고 주장하지 않는다.

| 항목 | 사례·고정 입력 | 새 Kotlin 테스트 | 정적 기준·Java 근거 | 보호하는 계약 |
| --- | --- | --- | --- | --- |
| A1 | 회원 7, 기존 사례와 같은 주문 ID·1000원·CONFIRMING, 고정 테스트용 요청 식별값 | `ChargeConfirmHttpBodyGoldenTest.confirmingReturns202WithJavaBody` | `credit/confirm-confirming-accepted.json`; `ChargeConfirmHttpBodyJavaGoldenTest` | POST `/api/v1/credits/charge-orders/confirm`, 202와 전체 envelope/data; 호출 인자에 회원 ID와 멱등 키 전달 |
| A2 | 회원 7, 공간 3, 대기 ID 11, 2026-10-20 14:00~15:00, WAITING·position 2·생성 10/15 09:00 | `WaitlistApplyHttpBodyGoldenTest.waitingReturns201WithJavaBody` | `waitlist/apply-waiting-created.json`; `WaitlistApplyHttpBodyJavaGoldenTest` | POST `/api/v1/waitlist`, 201와 전체 envelope/data; 회원·공간·이용 시각 전달 |
| A3 | 회원 7, page=1/size=2/totalElements=5, 알림 ID 2→1, 서로 다른 본문·read=true/false·고정 생성 시각 | `NotificationListHttpBodyGoldenTest.listReturnsOrderedNotificationsAndJavaPageBody` | `notification/list-nonfirst-page-ok.json`; `NotificationListHttpBodyJavaGoldenTest` | GET `/api/v1/notifications`, 200; 목록 순서·두 알림의 모든 필드와 page/size/totalElements/totalPages=3, 회원·Pageable 전달 |
| B4 | 기존 웹훅 수신 경로, 수신 서비스 대역 | `TossWebhookEmptyBodyTest.receivedWebhookReturns200WithZeroBodyBytes` | Java·정적 JSON 불필요 | 200와 응답 바이트 길이 0; `null`, `{}`, envelope는 실패; 수신 인자와 1회 호출 확인 |
| B5 | 기존 `outbox/slot-released-{cancelled,force-cancelled,hold-expired}-payload.json` 원문 3개 | `SlotReleasedGoldenPayloadTest.onMessageForwardsGoldenIdentity`(파라미터 3건) | 기존 3단계 Java Outbox 골든 그대로 재사용; 새 Java 확보 불필요 | 실제 `onMessage`에 원문 투입 → 각 파일의 eventId·reservationId=10을 mock 핸들러로 정확히 1회 전달; Kafka/브로커 불필요 |
| B6 | 운영 리스너 공개 상수 | `SlotReleasedListenerContractTest.listenerKeepsTopicAndConsumerGroup` | Java·정적 JSON 불필요 | `TOPIC == "slot-released"`, `GROUP_ID == "slotkey-waitlist-notifier"` |

기존 `confirm_confirming_returnsAccepted`, `apply_returns201`, `list_usesTokenMemberId`는 수정하지 않았다. 새 HTTP 골든은 기존 부분 필드 검증에 전체 본문 비교를 더한다. 알림은 단일 항목 첫 페이지보다 배열 순서·페이지 수 회귀를 분명히 잡기 위해 비첫 페이지와 서로 다른 2항목을 고정했다. 새 정책을 도입한 것은 아니다.

### 변동 필드·비교 방식

- A의 주문/대기/공간/알림 ID·모든 응답 시각은 대역 입력에 고정했다. 응답에 실행 시각·DB 자동 증가·난수가 없다. JWT만 실행 시 발급하며 응답 본문에 포함되지 않는다.
- 운영 MVC가 생성한 본문 바이트를 같은 주입 `ObjectMapper.readTree`로 읽어 정적 JSON의 트리와 직접 비교한다. 기존 `SurveyGoldenAssertions.readGolden`을 재사용한다.
- 객체 키 순서·공백만 무시한다. 필드 추가/누락·null·배열 순서·값·타입은 보존한다. 배열 정렬·null 제거·문자열 변환·ID/시각 치환·정규화 없음. 기대 DTO를 실행 코드로 재생성하지 않는다.
- B5는 기존 파일을 문자열로 직접 전달한다. 기대 eventId 3개와 reservationId는 테스트 표에 리터럴로 두어, 리스너의 파싱 코드로 기대값을 계산하지 않는다. `verify`와 `verifyNoMoreInteractions`로 중복/추가 호출을 막는다.
- B4는 JSON으로 파싱하지 않고 빈 바이트 배열을 검사한다. B6는 두 공개 상수 값을 검사하며 Kafka annotation 설정이나 실제 브로커 구독까지 검증한다고 주장하지 않는다.
- 모든 신규 HTTP 테스트는 `MockMvcPrint.NONE`으로 요청/토큰 자동 출력이 없도록 했다. 시크릿은 기존 테스트 프로파일 환경에서 주입받고 값은 기록하지 않는다.

## 3. Java worktree 재현

[3단계의 기준 작업 트리와 재현 방법](step-3.md#기준-작업-트리와-재현-방법)을 따라 새 worktree를 만들었다. 이전 worktree를 재사용하지 않았다.

```bash
# 저장소 루트
git switch -c test/golden-http-bodies dev
git worktree add --detach /private/tmp/slotkey-golden-http-java-a380133 a380133082fc323ed52d933799aa040ad2107b49
```

1. `git show 61f7cd2:<경로>`로 기존 `FridayRemainingDtoGoldenTest.java`, `ReservationDtoGoldenTest.java`와 `golden/reservation/*.json`을 같은 상대 경로로 복사했다. 기존 소스 변경은 없다.
2. Java worktree 루트에서 `git apply <현재 저장소>/docs/testing/golden-survey/step-3-java-baseline.patch`를 적용하고 현재 저장소의 기존 `golden/**/*.json`을 같은 위치로 복사했다. 신규 A의 3파일은 최초 RED 때 아직 없었다.
3. 4단계 Java 테스트 3개와 지원 클래스 1개를 추가했다. 전체 추가분은 [step-4-java-baseline.patch](step-4-java-baseline.patch)에 보존했다. 운영 코드·기존 테스트 수정이 없는 **4파일 추가 전용 패치**다.
4. 아래 RED로 생성한 별도 캡처 JSON 3개의 필드·타입·값을 검토한 다음 Java 정적 리소스로 복사했다. Java GREEN을 확인한 뒤 같은 바이트를 현재 Kotlin 리소스로 복사했다. 골든 파일은 캡처 원문 그대로이며 재포맷·값 편집을 하지 않았다.

```bash
# 재실행: 위 1~2를 준비한 새 Java worktree 루트에서
# 처음 확보를 다시 할 때만 신규 골든 3개를 복사하지 않는다.
git apply <현재 저장소>/docs/testing/golden-survey/step-4-java-baseline.patch
# 현재 저장소의 신규 A 골든 3개를 backend/src/test/resources/golden/<domain>/에 복사
cd backend
./gradlew cleanTest test --tests '*HttpBodyJavaGoldenTest'
```

- 실제 Java 실행 위치: `/private/tmp/slotkey-golden-http-java-a380133/backend`. Java 실행·패치·원문 일치 확인 후 이 임시 worktree는 `git worktree remove --force /private/tmp/slotkey-golden-http-java-a380133`으로 제거했다. 위 절차와 보존 패치로 새 경로에서 재현한다.
- 패치 SHA-256: `202c6de86bd35dbf00683034841ec14b4074d0ba2c5a21b60a19e440870dcd8f`.
- 패치를 임시 빈 디렉터리에 `git apply --check` → `git apply`한 뒤 4파일 모두 실제 실행 소스와 바이트 일치를 확인했다.
- Java helper의 `GOLDEN_HTTP_CAPTURE_DIR`는 **최초 확보 전용**이다. 골든 리소스 경로가 아닌 별도 디렉터리에 `CREATE_NEW`로만 기록하므로 기존 출력 덮어쓰기가 불가능하다. Java 비교 재실행에는 변수를 지정하지 않는다. Kotlin 테스트에는 쓰기·생성·갱신 분기가 없다.
- 새 Java 지원 클래스와 Kotlin 지원 클래스의 MVC 설정·인증 설정·응답 입력은 같다. Kotlin 지원 클래스는 B4를 위해 웹훅 컨트롤러·수신 mock도 로드한다. A 응답에 영향을 주는 직렬화 설정은 바꾸지 않았다.

### 정적 JSON 원문 일치 근거

Java 최초 캡처 ↔ Java 정적 리소스 ↔ 현재 Kotlin 정적 리소스를 바이트 비교했다(3개 일치). 각 파일 SHA-256:

| 골든 | SHA-256 |
| --- | --- |
| `credit/confirm-confirming-accepted.json` | `3e53c0d46bd97524328e1ee9a525e87b1a5d1e1aa5763035b68b5a4a89914747` |
| `waitlist/apply-waiting-created.json` | `df5df5fff3566916cc7bc0c1702a7ec2d5fcf91e60863da9375ac0ad1aa5b61c` |
| `notification/list-nonfirst-page-ok.json` | `3faa76ae85f9ed179ef1da42dbd1ccc166166403feff5b395c94b06b46efa131` |

## 4. 실행 명령과 결과

환경: JDK 21.0.11(Microsoft OpenJDK), Gradle wrapper 8.10.2. 전체 테스트는 기존 Docker/Testcontainers MySQL을 사용한다. 신규 웹 계층·리스너 테스트에는 DB·브로커·외부 PG 연결이 없다. 아래 시각은 XML의 UTC이며 KST는 +9시간이다. 수치는 **실행 / 실패 / 오류 / 스킵**이다.

### RED → Java → Kotlin

| 순서 | 실행 | XML 시작 시각(UTC) | 종료 코드 | 실행 / 실패 / 오류 / 스킵 | 판정 |
| --- | --- | --- | --- | --- | --- |
| 1 | Java A 3건, 파일 부재·최초 캡처 | `2026-10-26T15:00:21`~`15:00:22` | 1 | 3 / 3 / 0 / 0 | 3개 모두 `Java a380133 HTTP golden: <path> / Expecting actual not to be null` |
| 2 | Kotlin A 3건, 파일 부재 | `2026-10-26T15:00:41` | 1 | 3 / 3 / 0 / 0 | 3개 모두 `Java a380133 golden: <path> / Expecting actual not to be null` |
| 3 | Java A 3건, 고정 파일 비교 | `2026-10-26T15:01:06` | 0 | 3 / 0 / 0 / 0 | A1/A2/A3 각 1건 통과 |
| 4 | Kotlin B5, 빌드 리소스만 일시 제외 | `2026-10-26T15:01:23` | 1 | 3 / 3 / 0 / 0 | 기존 Outbox 3경로 각각 부재 단언 실패 |
| 5 | Kotlin A·B 신규 6클래스 전체 | `2026-10-26T15:02:09`~`15:02:10` | 0 | 8 / 0 / 0 / 0 | A 3건·B4 1건·B5 3건·B6 1건 포함 |

```bash
# 1: Java backend, 최초 확보 전용. 캡처 경로는 매 재현 시 새 빈 경로를 쓴다.
GOLDEN_HTTP_CAPTURE_DIR=/private/tmp/golden-step4-capture ./gradlew cleanTest test --tests '*HttpBodyJavaGoldenTest'
# 2: Kotlin backend, 신규 A 정적 JSON이 아직 없는 상태
./gradlew cleanTest test --tests '*HttpBodyGoldenTest'
# 3: Java backend, 캡처를 검토해 Java 정적 리소스로 고정한 뒤
./gradlew cleanTest test --tests '*HttpBodyJavaGoldenTest'
# 4: Kotlin backend, build/resources/test/golden/outbox/의 대상 3개만 일시 이동한 뒤
./gradlew cleanTest test -x processTestResources --tests '*SlotReleasedGoldenPayloadTest'
# 5: 빌드 리소스 복원 + A의 동일 정적 JSON을 Kotlin에도 복사한 뒤
./gradlew cleanTest test --tests '*HttpBodyGoldenTest' --tests '*TossWebhookEmptyBodyTest' \
  --tests '*SlotReleasedGoldenPayloadTest' --tests '*SlotReleasedListenerContractTest'
```

- B5 RED는 `src/test/resources` 원본을 수정/삭제하지 않았다. `build/resources/test` 사본만 `/private/tmp/golden-step4-outbox-build-resource-backup/`으로 이동하고 `processTestResources`를 제외했다. 실패 XML 확보 후 원본과 바이트 일치하는 사본을 복원했다. 이후 정상 `processTestResources`와 전체 clean 빌드/테스트를 사용한다.
- **B4/B6는 정적 JSON 파일이 없는 계약이므로 파일 부재 RED가 적용되지 않는다.** 빈 응답·문자열 상수 검증에 가짜 JSON 파일을 도입하거나 운영 코드를 바꿔 실패시키지 않았다. 첫 실행부터 Kotlin 직접 단언 통과로 기록하며 RED 또는 Java 통과가 있었다고 주장하지 않는다. B5의 Java 기준은 기존 골든 출처이고 새 소비 테스트에 Java 비교는 없다.
- Kotlin 최초 시도는 새 지원 클래스의 `@Value` 문자열 이스케이프 누락으로 `compileTestKotlin` 단계에서 실패했다(XML 없음). 새 파일에서 수정한 뒤 위 2번을 재실행했다. 컴파일 실패를 RED 테스트 수에 포함하지 않았다.
- 각 성공·실패 묶음의 XML은 다음 `cleanTest` 전에 복사했다. `/private/tmp/golden-step4-evidence/{java-red,kotlin-red,java-green,listener-red,kotlin-green}/TEST-*.xml`. 이 경로는 세션 로컬 임시 근거이며 영구 산출물이 아니다. 영구 재현 근거는 이 보고서·정적 JSON·Java 패치다.
- 각 실행 로그: `/private/tmp/golden-step4-{java-red,kotlin-red,java-green,listener-red,kotlin-green}.log`.

### 최종 검사

| 명령(Kotlin backend) | 결과 |
| --- | --- |
| `./gradlew ktlintCheck` | 종료 0. 첫 실행의 새 4파일 chain-method-continuation 14건과 중간 수동 수정의 들여쓰기/체인 오류를 해당 새 파일에서만 수정 후 통과. 기존 파일 자동 포맷 없음 |
| `./gradlew clean build -x test` | 종료 0, `BUILD SUCCESSFUL in 9s`, main/test lint 포함. 테스트 실행을 대체하지 않는다 |
| `./gradlew cleanTest test` | 종료 0, **1,361 / 0 / 0 / 0**, XML 189개, `BUILD SUCCESSFUL in 9m 57s`; 신규 6클래스 8건 모두 포함 |
| `python3 .github/scripts/check_test_count.py backend/build/test-results/test backend/test-baseline.txt`(저장소 루트) | 종료 0, `suites=189 tests=1361 skipped=0 failures=0 errors=0 baseline=1322` |
| `git diff --check` 및 변경 범위 점검 | 종료 0. 기존 추적 파일 변경은 baseline 하나, stage 비어 있음; 기존 운영 코드·테스트·골든·배포 설정 변경 없음 |

전체 XML 시작 범위는 `2026-10-26T15:04:36`~`15:14:04` UTC(KST 2026-10-27 00:04:36~00:14:04)다. 원본 사본은 `/private/tmp/golden-step4-evidence/kotlin-full/`, 실행 로그는 `/private/tmp/golden-step4-full.log`다. 전체 XML의 신규 클래스별 실행 수는 충전/대기/알림/웹훅/상수 각각 1건, Outbox 소비 3건이며 전부 실패·오류·스킵 0이다. 하한은 요청대로 기존 1,314에 신규 8만 더했으므로 실제 전체 실행 수 1,361과 다르다(이전 하한과 실제 수의 간격까지 임의로 재설정하지 않았다).

프론트 변경이 없어 프론트 검사는 이번 범위에 포함하지 않았다. 기존 Kotlin Lombok compiler plugin 경고와 JVM class sharing 경고는 설정을 변경하지 않고 그대로 기록한다. 테스트를 비활성화하거나 스킵시키지 않았다.

## 5. 변경 파일 목록

새 파일 12개(Kotlin 7개·JSON 3개·Java 패치 1개·보고서 1개), 기존 파일 수정 1개. 커밋 대상 검토용 목록이며 stage하지 않았다.

```text
backend/src/test/kotlin/com/ovengers/slotkey/credit/controller/ChargeConfirmHttpBodyGoldenTest.kt
backend/src/test/kotlin/com/ovengers/slotkey/credit/webhook/TossWebhookEmptyBodyTest.kt
backend/src/test/kotlin/com/ovengers/slotkey/notification/NotificationListHttpBodyGoldenTest.kt
backend/src/test/kotlin/com/ovengers/slotkey/notification/SlotReleasedGoldenPayloadTest.kt
backend/src/test/kotlin/com/ovengers/slotkey/notification/SlotReleasedListenerContractTest.kt
backend/src/test/kotlin/com/ovengers/slotkey/support/HttpBodyGoldenSupport.kt
backend/src/test/kotlin/com/ovengers/slotkey/waitlist/controller/WaitlistApplyHttpBodyGoldenTest.kt
backend/src/test/resources/golden/credit/confirm-confirming-accepted.json
backend/src/test/resources/golden/notification/list-nonfirst-page-ok.json
backend/src/test/resources/golden/waitlist/apply-waiting-created.json
backend/test-baseline.txt
docs/testing/golden-survey/step-4-java-baseline.patch
docs/testing/golden-survey/step-4.md
```

향후 커밋 시 코드/테스트와 문서 커밋은 분리한다. 이번에는 커밋하지 않는다.

## 6. 가정·미확인·보류·리뷰가 필요한 판단

- **기능/Java 기준 재현 보류: 없음. 사용자 결정이 필요한 질문: 없음.** 정적 파일이 없는 B4/B6의 파일 부재 RED는 위와 같이 비적용으로 기록했다.
- 구현자 자체 실행 결과다. 독립 검증자가 별도 세션에서 전체 테스트와 Java 재현을 직접 실행해 통과로 판정했다([step-4-verification.md](step-4-verification.md)). CI와 사람 확인은 PR에서 한다.
- 새 웹 테스트는 HTTP 응답 계약을 확인하고 서비스 계층을 대역으로 둔다. 실제 PG 상태 전이·DB 정합성·웹훅 수신 저장/복구는 기존 통합 테스트의 범위다. 수신 mock은 성공 수신 뒤 200 빈 본문만 격리한다.
- 외부 API 엔드포인트/필드/헤더/오류 코드를 새로 설계하거나 실제 PG를 호출하지 않았다. 웹훅 요청은 기존 컨트롤러 입력을 대역에 전달하는 로컬 사례이며 외부 서비스 공식 스키마의 새 검증 근거로 주장하지 않는다. 공식 외부 MCP 조회가 필요한 신규 API 설계는 없다.
- 리뷰가 필요한 테스트 구성: 공통 웹 지원 클래스에 A의 3컨트롤러와 B4 웹훅 컨트롤러·서비스 mock을 함께 로드해 같은 MVC/보안 설정을 재사용했다. 운영 빈·직렬화 설정은 바꾸지 않았다.
- 배열 순서와 페이지 metadata를 분명히 보호하도록 A3에 서로 다른 두 알림을 사용했다. 현재 목록 정렬 정책 자체나 저장소 조회 로직을 새로 확정하지 않는다.
- 신규 기능·라이브러리·인프라·정책 도입이 없어 새 decision 문서나 기존 decision 수정은 없다. API·스키마 동작 변경도 없어 api-spec/erd 수정은 없다.
- 예약 30분·HOLD→결제·JWT 클레임·소유권/관리자 경로·예약당 활성 출입 토큰·멱등성·오케스트레이션 방향에 대한 운영 변경 없음. 에이전트 코드/상태 변경 도구·Lombok·엔티티·시크릿·배포 job도 변경하지 않았다.

## 7. 관련 문서

- `AGENTS.md`(로컬 지침 파일, 저장소에 추적되지 않음): 작업 범위·RED·인계·커밋 금지 기준.
- [Java → Kotlin 골든 가이드](../java-kotlin-golden-guide.md): Java 실제 출력 고정, 동일 JSON 구조 비교.
- [3단계 보고](step-3.md), [3단계 독립 검증](step-3-verification.md): 기준 재현 절차·기존 Outbox 골든의 출처.
- [Kotlin 테스트 보호 장치](../../decisions/kotlin-test-guardrails.md): 새 테스트 Kotlin·기존 테스트 보존·테스트 수 하한.
- [PG 충전](../../decisions/pg-charge.md): CONFIRMING 202·웹훅 수신 계약의 배경.
- [대기열](../../decisions/waitlist.md), [Outbox + Kafka](../../decisions/outbox-kafka.md): WAITING·알림 조회·리스너 계약.
- [핵심 도메인 결정](../../core-domain-decisions.md): 설계 기준. 이번 단계에 새 설계 판단 없음.

## 8. 리뷰 체크리스트

- [ ] `dev@0703cc9`에서 만든 `test/golden-http-bodies`이며 커밋·stage·push가 없는가.
- [ ] 운영 코드·기존 테스트·기존 골든이 그대로이고 기존 파일 수정은 baseline 주석 1줄과 +8뿐인가.
- [ ] A1/A2/A3 Java 기준이 `a380133`이며 새 4파일 패치만 추가해 실제 MVC 응답을 얻었는가.
- [ ] A1의 202 CONFIRMING envelope/data 전체가 정적 골든과 같은가.
- [ ] A2의 201 WAITING·position·ID·이용/생성 시각 전체가 같은가.
- [ ] A3의 200·두 알림 순서·read 값·시각·페이지 1/2/5/3이 같은가.
- [ ] ID·시각을 대역 입력에서 고정하고 본문 정규화·필드 삭제·타입 변환으로 숨기지 않았는가.
- [ ] JSON 필드 누락/추가·null·배열 순서·값·타입을 트리 동등 비교하는가.
- [ ] Java 캡처와 Java/Kotlin 골든 3개의 바이트 및 SHA-256이 같은가.
- [ ] 신규 Kotlin 코드에 골든 쓰기·갱신 코드가 없고 Java 최초 확보는 별도 경로 CREATE_NEW뿐인가.
- [ ] B4가 200과 정확히 0바이트를 검사하며 수신 mock 외부 호출이 없는가.
- [ ] B5가 기존 3개 payload 원문으로 실제 리스너를 호출하고 독립 리터럴 eventId/reservationId의 1회 전달을 검사하는가.
- [ ] B6가 TOPIC/GROUP_ID의 요청된 두 값을 검사하는가.
- [ ] A의 파일 부재 RED·Java GREEN·Kotlin GREEN을 XML로 확인하고 B4/B6의 RED 비적용을 구분했는가.
- [ ] 신규 8건이 전체 XML에 포함되고 실패·오류·스킵 0 및 하한 증가분이 맞는가.
- [ ] ktlintCheck·clean build -x test·cleanTest test와 전체 XML 수를 직접 재확인했는가.
- [ ] 기존 배포 설정·환경변수·스키마·정책 변경이 없고 구현자 자기검증과 독립 검증을 구분해 표시했는가.
