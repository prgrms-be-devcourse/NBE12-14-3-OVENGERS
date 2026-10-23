# 금요일 Optional DTO 전환 실행 계획

> **For agentic workers:** 구현은 `superpowers:executing-plans`로 한 묶음씩 수행한다. 저장소 규칙에 따라 사용자가 다음이라고 하기 전 다음 묶음으로 진행하지 않는다. 커밋은 대상 파일을 보여주고 사용자 확인 후 수행하며 코드·문서를 분리한다.

**Goal:** 이번 담당 Kotlin DTO에서 nullable 없이 값 부재를 표현하며 HTTP·메시지·DB 계약을 보존한다.

**Architecture:** [설계 제안](../../decisions/kotlin-friday-absence-without-nullable.md)에 따라 필수값은 일반 타입, 검증 전 입력과 선택값은 non-null `Optional<T>`를 사용한다. Java 호출부의 변환만 함께 수정하고 업무 서비스는 그대로 둔다.

**Tech Stack:** Kotlin 1.9.25, JDK 21, Spring Boot 3.3.4, Jackson 2.17.2, Jakarta Bean Validation, JUnit/MockMvc, MySQL Testcontainers.

**Spec:** [값 부재 설계](../../decisions/kotlin-friday-absence-without-nullable.md). 선택 이유·대안·Trade-off는 이 문서에 복제하지 않는다.

## Global Constraints

- DTO와 이벤트 값 객체는 `@JvmRecord data class`, `PgClient`는 일반 `interface`, `PgWebhookEventResult`는 기존 이름·순서의 `enum class`다.
- 대상 Kotlin 운영·신규 테스트 코드에 nullable 선언, 안전 호출·엘비스 연산, 강제 non-null 단언을 추가하지 않는다.
- HTTP·메시지·DB 계약, 검증 메시지, 금액 범위, 결제 멱등성·트랜잭션·락 순서를 유지한다.
- 원본 Java 삭제와 같은 클래스 Kotlin 추가를 같은 코드 커밋에 담는다.
- 기존 Java 파일 수정은 Optional 감싸기·꺼내기와 필요한 엔티티 delombok만 포함한다.
- 커밋·push·PR 생성·머지는 각각 기존 사용자 권한 범위 안에서만 수행한다. 사용자가 첫 예약 묶음의 테스트 고정과 전환을 승인했다.

## Review Focus

- 필수 입력 누락·명시적 null이 생성자 예외 대신 기존 검증 오류로 응답하는가: Task 1의 HTTP 기준과 Task 2의 재실행.
- 빈 Optional이 응답에서 필드 누락이나 객체로 바뀌지 않는가: Task 1의 상태별 골든과 Task 2의 같은 파일 비교.
- 선택 검색 조건의 생략·빈 문자열·공백·오류 형식이 기존 전체 조회/거절을 유지하는가: Task 1의 query binding 및 Task 2의 재실행.
- Optional 반환형 변경으로 다른 Java 소비자가 잘못된 null 비교를 하는가: Task 2의 전수 호출 검색·컴파일·검색/인가 통합 테스트.
- 공유 ObjectMapper와 OpenAPI가 다른 담당 DTO/이벤트에 영향을 주는가: Task 2의 공통 응답·Outbox/PG 회귀와 schema 검사.

## Task 1: 첫 예약 묶음의 Java 기준 확보

**실행 상태:** 완료. Java 골든·HTTP·OpenAPI 기준과 의도적 변이 RED를 확보했다. 상세 수치와 명령은 [단계 보고](../../lab/notes/kotlin-friday-dtos/step-3.md)에 기록한다.

**Files:**

- 기존 운영 Java DTO: `backend/src/main/java/com/ovengers/slotkey/reservation/dto/request/` 6개와 `response/` 5개. 명단은 설계 §2-4를 따른다. 다른 담당 응답 2개는 전환하지 않는다.
- 신규 테스트: `backend/src/test/java/com/ovengers/slotkey/reservation/dto/ReservationDtoGoldenTest.java`.
- 신규 HTTP 기준 테스트: `backend/src/test/java/com/ovengers/slotkey/reservation/controller/ReservationDtoContractTest.java`.
- 골든: `backend/src/test/resources/golden/reservation/`.
- 재사용: `ReservationControllerTest`, `AdminReservationControllerTest`, `ReservationSearchSecurityIntegrationTest`, `AdminReservationSecurityIntegrationTest`, `ReservationQueryServiceIntegrationTest`, `AdminReservationSearchIntegrationTest`.

**Interfaces:** 현재 Java DTO·운영 ObjectMapper·MockMvc를 소비하고, 변환 후에도 바꾸지 않을 JSON fixture와 HTTP 입력/출력 기준을 제공한다.

- [ ] 최신 dev·작업 트리·선행 PR을 확인하고 `feat/kotlin-friday-reservation-dtos` 작업 브랜치를 준비한다. 작업 공간 선택은 기존 사용자 선호를 따른다. 엔티티 delombok·공통 응답/오류의 실제 준비 상태를 확인한다.
- [ ] 각 응답의 고정 입력과 Java JSON을 확보한다. HOLD/CONFIRMED/취소/이용 중/완료, 관련 공간·회원 부재, 빈 이력, 날짜·숫자·null의 표본을 정적 파일에 둔다. 골든 이름은 `hold-response.json`, `confirmed-response.json`, `cancelled-response.json`, `detail-empty-history.json`, `list-missing-space.json`, `admin-missing-related-data.json`를 사용한다. 목록·상세별 구조 차이는 별도 파일로 고정한다.
- [ ] `ReservationDtoGoldenTest`에서 운영 ObjectMapper로 직렬화한 JsonNode가 해당 정적 파일과 일치하는지 단언한다. `checkedInAt` 등은 필드 존재와 null을 함께 확인한다. `spaceVersion = 0`, 환불액 0과 null의 차이도 단언한다.
- [ ] `ReservationDtoContractTest`에서 인증·CSRF 등 기존 보안 fixture를 재사용한다. 생성/연장/결제/강제취소의 필수 필드를 하나씩 누락·null로 요청하고, 문자열 빈 값·공백·500/501자, Int/Long 범위 초과와 잘못된 날짜를 구분해 기존 status/code/message를 고정한다. 다중 누락의 메시지도 실제 Java 응답을 기준으로 저장한다. 결제 키 누락/빈 값과 소유권·역할 거절의 서비스 미호출을 기존 테스트와 연결한다.
- [ ] 본인/관리자 GET의 조건 생략, 빈 값, keyword 공백, 유효 날짜·상태·공간, 잘못된 enum·날짜·공간 형식, 0·음수 공간을 실제 MockMvc로 고정한다. `/v3/api-docs`에서 필드 타입·format·필수 여부·검증 제약을 구조로 기록한다.
- [ ] `cd backend` 후 `./gradlew test --tests '*ReservationDtoGoldenTest' --tests '*ReservationDtoContractTest' --tests '*ReservationControllerTest' --tests '*AdminReservationControllerTest' --rerun-tasks`를 실행한다. Java 기준 테스트는 통과해야 한다. 기존 동작 회귀용 기준 테스트의 최초 통과와, 다음 Task의 신규 Optional 계약 테스트의 RED를 구분한다.
- [ ] 의도적으로 Java DTO 값을 한 번 바꾸는 임시 변이로 적어도 응답 null/필드명 골든이 실패하는지 확인하고 즉시 복구한다. 기준 파일을 실제 출력으로 재생성해 통과시키지 않는다.
- [ ] 시각·기준 커밋/미커밋 diff·명령·종료 코드·실행/실패/오류/스킵을 기록한다. 기준 확보가 실패하면 해당 DTO 전환을 보류한다.

## Task 2: 예약 11개 DTO와 직접 Java 경계 전환

**실행 상태:** 구현·자기검증 완료. 전체 1,140개와 프론트 80개가 통과했다. 사용자 확인 전이라 커밋·push·PR은 수행하지 않았다.

**Files:**

- Task 1의 Java 11개 삭제 + 같은 패키지·이름의 `backend/src/main/java/com/ovengers/slotkey/reservation/dto/`에 Kotlin 11개 추가.
- 기존 `ReservationController.java`, `AdminReservationController.java`, `ReservationQueryService.java`, `AdminReservationService.java`와 대상 팩토리/생성자/접근자의 직접 Java 소비자. `rg`로 확정한 파일 목록을 보고에 남긴다.
- `Reservation.java`, `ReservationSlot.java`: 필요한 명시적 Java API가 선행 PR에 없을 때 delombok만 수행한다.
- 신규 테스트: `backend/src/test/java/com/ovengers/slotkey/reservation/dto/ReservationDtoOptionalContractTest.java`.
- `backend/test-baseline.txt`: 실제 추가된 실행 테스트 수만 반영한다.

**Interfaces:** 설계 §2-4의 Optional 필드·기존 이름의 record 접근자·`@JvmStatic` 팩토리를 제공한다. 필수 요청 값은 검증 뒤 `Optional.orElseThrow()`, 선택값은 Java 경계에서 `Optional.ofNullable`/`orElse(null)`로 기존 업무 코드와 연결한다.

- [ ] 전환 전에 신규 Optional 계약 테스트를 작성한다. `ReservationPayRequest`의 record component가 Optional인 것, 운영 ObjectMapper에서 `{}`·`{"spaceVersion":null}`를 읽어 해당 값이 빈 Optional인 것, Validator 결과의 property path가 `spaceVersion`이고 기존 필수 메시지인 것을 확인한다. 두 입력은 독립 사례로 둔다. Java 원본에서는 record component 타입 불일치로 실패하는 RED를 실행·기록한다.
- [ ] 예약 요청 6개를 Optional과 기존 제약의 unwrap payload로 전환한다. `ReservationCreateRequest.toStartDateTime()/toEndDateTime()`는 입력 검증 후 값만 조합한다. 검색 포맷과 OpenAPI 애너테이션의 위치를 명시한다.
- [ ] 응답 5개와 정적 팩토리를 전환한다. 실제 선택 필드만 Optional로 만들고 null 포함 정책을 고정한다. 공간 부재 팩토리는 `Optional<Space>`를 받는다.
- [ ] 모든 직접 Java 생성자/팩토리/접근자를 검색하고 감싸기·꺼내기만 수정한다. Optional 자체를 null과 비교하는 잔존 코드, Optional의 `toString()`을 결제 fingerprint·감사 메시지·로그 값으로 쓰는 변경이 없는지 확인한다. 테스트 입력은 같은 의미의 Optional로만 바꾼다.
- [ ] 신규 Optional 계약 테스트를 재실행해 GREEN을 확인한다. Task 1의 같은 골든·HTTP 테스트와 검색/보안 통합 테스트를 재실행한다. Bean Validation·query binding 차이가 나면 전역 설정을 변경하지 말고 차이와 결정 재검토 필요성을 기록한다.
- [ ] OpenAPI가 Optional의 `present/empty` 객체로 변하지 않고 기존 값 타입·필수 여부를 유지하는지 비교한다. 타입 정보 수정이 필요한 경우 해당 DTO의 `@Schema`만 조정한다. Springdoc 전역 변경이 필요하면 별도 결정으로 분리한다.
- [ ] 변경된 대상 `.kt`와 새 Kotlin 테스트에서 `rg -n '\?|!!' <대상 파일 목록>`을 실행한다. 주석·문자열과 코드 위치를 구분하고 대상 Kotlin 코드에서 0건을 확인한다. Lombok 및 엔티티 data class 금지도 확인한다.
- [ ] `cd backend` 후 `./gradlew clean build`를 JDK 21·Docker 환경에서 실행한다. 테스트 제외 플래그를 사용하지 않는다. XML의 실제 수치를 집계하고 저장소 루트에서 `python3 .github/scripts/check_test_count.py backend/build/test-results/test backend/test-baseline.txt`를 실행해 기준 이상·스킵 0을 확인한다.
- [ ] `cd frontend` 후 `node --test tests/*.cjs`와 `npx tsc --noEmit`을 실행한다. 공유 Jackson을 쓰는 기존 공통 응답·PG·Outbox/consumer 테스트가 전체 빌드에 실제 포함됐는지도 확인한다.
- [ ] 관련 decision 검증 결과와 `docs/lab/notes/kotlin-friday-dtos/step-3.md`를 갱신한다. 새 테스트 목적·모든 명령/결과·기준 커밋·가정·미확인·리뷰 체크리스트를 포함한다. 독립 전체 검증·CI는 별도 상태로 둔다.
- [ ] 사용자에게 코드/문서 커밋 대상 목록을 보여준다. 확인 후에만 각각 커밋한다. PR 생성 권한이 생기면 Java 기준·최종 소스 커밋·날짜·명령·종료 코드·테스트 수를 기록한다. 다음 묶음은 사용자 지시를 기다린다.

## Task 3: 충전·PG·웹훅·대기열·알림 16개 전환

**실행 상태:** 구현·커밋·push·Draft PR #44·소스 루트 이동 후 최종 검증 완료. 예약 PR #42 위에 쌓였으며, 첫 PR 머지 뒤 `dev` 기준으로 정리한다.

**Files:** `credit/dto` 5개, `credit/pg` 4개, `credit/webhook` 2개, `waitlist/dto` 2개, `notification/dto/response` 3개의 Java 삭제와 같은 패키지 Kotlin 추가. Controller·Service·Store·Scheduler·Repository는 타입 연결에 필요한 값 감싸기·꺼내기만 수정한다.

**Interfaces:** 필드 분류는 결정 문서 §2-4를 그대로 소비한다. 요청의 검증 전 필수값과 PG/웹훅/충전 내역의 실제 부재는 non-null Optional, 나머지는 일반 non-null 타입이다. PG 결과의 상태 판정과 `paymentObjectReceived` 의미를 유지한다.

- [x] 기존 충전·PG·웹훅·대기열·알림 테스트를 Java 원본에서 통과시키고 JSON·검증·팩토리 경계의 골든을 고정한다.
- [x] Optional record component, 요청 누락/null/빈 값, Long 범위, PG 상태별 부재와 웹훅 null을 원하는 API로 먼저 작성해 RED를 확인한다.
- [x] 16개 타입을 `@JvmRecord data class`, 일반 Kotlin interface, `enum class`로 전환하고 직접 Java/Kotlin 소비자만 맞춘다.
- [x] 골든·HTTP·PG 승인/조회·recovery·webhook·대기열·알림 테스트를 GREEN으로 만든다. 전역 Jackson·Validation·Springdoc 설정은 변경하지 않는다.
- [x] 대상 Kotlin의 `?`, `!!`, Lombok과 범위 밖 클래스 변경이 0건인지 확인한다.
- [x] JDK 21·Docker에서 전체 `clean build`, 테스트 수 하한·스킵 0, 프론트 테스트·타입 검사를 실행한다.
- [x] 결정 문서 검증 결과와 `docs/lab/notes/kotlin-friday-dtos/step-4.md`를 갱신하고 별도 전체 리뷰를 수행한다.

## 후속 묶음의 경계

예약 이후 범위는 Task 3으로 구체화했다. 아래 표는 검증 경계를 요약한다.

| 순서 | 범위 | 추가로 반드시 검증할 경계 |
| --- | --- | --- |
| 2 | 충전 DTO + PG 모델/인터페이스 + 웹훅 값 객체/enum | 충전 Long 금액, 필수 검증 메시지, PG 상태별 부재/불완전 응답/결과 모름, recovery/webhook 호출부, 기존 승인/락/멱등 규칙 |
| 3 | 대기열·알림 DTO | 대기 신청 누락·시간 규칙·본인 데이터, position Long, 알림 read boolean·count 범위·빈 본문 응답 |

Outbox payload 자체와 실제 토스 endpoint/필드/헤더/오류 코드를 변경하지 않는다. PG 관련 새로운 외부 사실이 필요할 때만 공식 MCP 확인과 출처 기록을 수행한다.
