# 03. API 계약 고정

**목적:** Java `record`와 Lombok DTO를 Kotlin 클래스로 옮겨도 HTTP·PG·저장된 이벤트의 계약을 유지한다. [상위 계획](00-kotlin-migration-plan.md)의 세 번째 P0 작업이다. 소스 대조 기준은 2026-10-20 / `d1a15ec`이며, 2026-10-20 기준선(1,048/1,048)은 계약 보강의 출발점이지 계약 검증 완료를 뜻하지 않는다.

## 계약의 기준 파일

- 공통 응답: [`ApiResponse`](../../../backend/src/main/java/com/ovengers/slotkey/global/common/response/ApiResponse.java), [`PageResponse`](../../../backend/src/main/java/com/ovengers/slotkey/global/common/response/PageResponse.java), [`GlobalExceptionHandler`](../../../backend/src/main/java/com/ovengers/slotkey/global/error/GlobalExceptionHandler.java).
- 바인딩·검증 예: [`SpaceCreateRequest`](../../../backend/src/main/java/com/ovengers/slotkey/space/dto/request/SpaceCreateRequest.java), [`SpaceUpdateRequest`](../../../backend/src/main/java/com/ovengers/slotkey/space/dto/request/SpaceUpdateRequest.java), [`AdminAuditLogSearchCondition`](../../../backend/src/main/java/com/ovengers/slotkey/audit/dto/AdminAuditLogSearchCondition.java), [`ReservationSearchCondition`](../../../backend/src/main/java/com/ovengers/slotkey/reservation/dto/request/ReservationSearchCondition.java).
- 소비자와 명세: [`frontend/src/types/api.ts`](../../../frontend/src/types/api.ts), [`docs/api/api-spec.md`](../../../docs/api/api-spec.md), [`frontend/tests`](../../../frontend/tests).

## 고정할 계약

| 구간 | 대표 검증과 수정 방법 |
| --- | --- |
| 공통 응답 | 성공/실패의 `status`, `code`, `message`, 페이지의 `content/page/size/totalElements/totalPages`를 JSON 필드 단위로 확인한다. `ApiResponse`의 `@JsonInclude(NON_NULL)` 때문에 최상위 `data == null`일 때 **필드가 생략되는지** 확인한다. 중첩 DTO의 nullable 필드에는 이 규칙을 자동으로 확장하지 않는다. |
| 공간·예약 DTO | 공간 생성/수정, 예약 HOLD/결제/취소의 요청과 응답을 선택한다. `LocalDate`, `LocalTime`의 `HH:mm`, `LocalDateTime`, enum 문자열, `spaceVersion`, `refundAmount`, `penaltyAmount`의 값·`null`을 확인한다. |
| 오류와 인증 | `@Valid` 실패, 없는 ID, 소유권 위반, 권한 없음, 슬롯 충돌의 HTTP 상태와 오류 코드를 고정한다. 로그인/리프레시의 `Set-Cookie` 이름·`HttpOnly`·`Secure`·`SameSite`·경로를 현재 프로필 기준으로 확인한다. |
| 조회 쿼리 | `@ModelAttribute` 검색 조건의 생략/null, 날짜 범위, `Pageable` 기본 크기와 페이지 번호를 실제 MockMvc 요청으로 확인한다. |
| 문의 | 회원 문의 생성·목록·수정과 관리자 답변의 필드명 및 검증 오류를 [01 문서](01-test-baseline.md)의 테스트와 공유한다. |
| 인증·회원 | 인증번호 발급/확인, 가입의 `passwordConfirm/grant`, 비밀번호 재설정, 내 정보 수정·탈퇴의 필수/누락/null·오류를 고정한다. grant의 목적·이메일·만료·재사용 거절과 회원 상태도 포함한다. |
| Auth V2·세션 | 액세스 토큰의 `sid` 클레임과 `AuthSessionService.validate` 결과(유효/만료·폐기/조회 불가)를 구분한다. RT 회전 시 `consumed_at`을 남기고 이미 소비된 RT 재사용은 해당 세션과 세션의 모든 RT를 폐기한다. 로그아웃·탈퇴의 쿠키·폐기 결과도 고정한다. 검증은 `AuthControllerTest`(25), `V2SecurityRedTest`(15), `AuthSessionMigrationIntegrationTest`, `RefreshTokenServiceTest`. |
| 로그인 제한 | 같은 이메일의 10회/15분 초과 시 HTTP **429**, `LOGIN_RATE_LIMITED`, `Retry-After`(초) 헤더를 반환한다. 시도 기록은 `REQUIRES_NEW`로 본 인증 실패와 분리된다. 계정 제한(`ACCOUNT_INACTIVE`, `MEMBER_SUSPENDED`, `MEMBER_NOT_ACTIVE`, `VERIFICATION_ADMIN_LOCKED`) 안내의 고객지원 이메일은 프론트 `ErrorMessage` 계약이며 서버 응답 코드는 바꾸지 않는다. 검증은 `LoginRateLimitIntegrationTest`, `AuthControllerTest`, `frontend/tests`와 [`account-support-email`](../../decisions/account-support-email.md). |
| 관리자 회원 상세·이력 | `GET /api/v1/admin/members/{memberId}`, `/credit-transactions`, `/reservations`, `/status-history`의 필드·정렬·페이징과 404/403을 고정한다. `PATCH .../suspend`, `/restore`, `POST .../credits`의 상태·감사 로그 동반 커밋도 유지한다. 검증은 `AdminMemberDetailControllerTest`, `AdminMemberDetailServiceTest`, `AdminMemberControllerTest`, `AdminMemberServiceIntegrationTest`. |
| 비활성 회원 출입 로그 | 비활성 회원의 출입 시도는 거절 응답과 함께 거절 사유 로그를 남긴다. 로그 저장은 `REQUIRES_NEW`이며 응답 코드·본 트랜잭션을 바꾸지 않는다. 검증은 `InactiveMemberAccessIntegrationTest`, `InactiveMemberAccessFilterTest`, `InactiveMemberAccessLogService`. |
| 충전 주문·내역 | `/api/v1/credits/charge-orders`의 생성/목록/상세·`/confirm`의 금액(long)·상태·멱등 키와 nullable 결제수단/실패 필드를 고정한다. 예약 `/pay`의 크레딧 차감과 PG TOP_UP을 구분한다. |
| 대기·알림 | `/api/v1/waitlist` 신청·`/me` 목록·본인 취소, `/api/v1/notifications` 목록·미읽음 수·읽음 일괄 처리의 필드와 소유권/페이징을 고정한다. 대기 취소는 **빈 본문 204**이며 일괄 읽음은 POST `/read-all`이다. |
| 통계 | `/api/v1/stats/*`와 `/api/v1/admin/stats/*`의 기간·지표·조회 조건·정렬/동률, long 순매출과 int 건수, 일반/관리자 노출 필드·권한을 각각 고정한다. 일반 경로의 실제 인증 요구도 보안 필터 기준으로 확인한다. |
| 웹훅 | POST `/api/v1/webhooks/tosspayments`는 JWT 없이 수신하며 transmission-id 등 헤더·raw JSON의 처리/무시/거절 조건을 검사한다. 성공 응답은 **빈 본문 200**이다. 일반 ApiResponse 형식으로 바꾸지 않는다. |

## HTTP 외 직렬화와 저장 데이터 호환성

- [`SlotReleasedRecorder`](../../../backend/src/main/java/com/ovengers/slotkey/outbox/SlotReleasedRecorder.java)의 payload는 `eventId` 문자열, `reservationId` 정수, `reason` enum 문자열, `occurredAt` 시각을 포함한다. [`SlotReleasedListener`](../../../backend/src/main/java/com/ovengers/slotkey/notification/consumer/SlotReleasedListener.java)는 eventId와 정수 reservationId를 읽는다. 현재 listener가 일부 필드만 쓰더라도 전체 저장 형식을 임의로 바꾸지 않는다.
- 전환 전 JSON 표본을 고정해 **이미 DB에 저장된 Outbox → 전환 후 relay/listener** 호환성을 검사한다. `SlotReleasedListenerTest`와 `OutboxRecordIntegrationTest`를 재사용하며 실수형 ID·빈 eventId·잘못된 JSON 거절도 유지한다.
- [`TossPaymentsPgClient`](../../../backend/src/main/java/com/ovengers/slotkey/credit/pg/TossPaymentsPgClient.java)의 승인 요청/응답·조회 응답 및 오류 분류를 `TossPaymentsPgClientTest`로 확인한다. Jackson Kotlin 모듈 추가는 공유 ObjectMapper에도 영향을 줄 수 있으므로 Kotlin으로 옮기지 않은 Java HTTP/PG/이벤트 경로도 재실행한다.
- 검증 위치를 Bean Validation만으로 한정하지 않는다. 생성자 null/타입 오류·raw JSON 검사·서비스 거절이 최종 HTTP 상태/오류 코드에 동일하게 매핑되는지 확인한다. 웹훅의 본문 승인값은 충전 확정 근거가 아니며 [02](02-money-and-concurrency.md)의 PG 재조회 규칙을 유지한다.

## 2026-10-22 합의: 골든 파일 테스트

공통·도메인·HTTP 검증의 역할 분담, 기준 파일 확보·재사용, 전환 후 비교와 불일치 처리는 [Java → Kotlin 골든 테스트 사용 가이드](../../testing/java-kotlin-golden-guide.md)를 따른다.

전환 전후 JSON이 같다는 증거를 눈 비교가 아니라 테스트로 남긴다. **Java 코드 상태에서 먼저** 주요 API 응답을 파일로 저장하고, 전환 후 같은 파일과 비교한다.

- 대상: 로그인, 충전, 예약(HOLD), 결제, 출입, 문의, 오류 응답. 필드 누락·`null`·날짜·숫자 형식을 포함한다.
- 동적 ID·시각은 입력을 고정하거나 형식·의미로 검증해 전체 문자열 비교의 취약함을 피한다.
- 요청 DTO는 Java가 `null`을 받던 필드를 null 가능 타입으로 유지하고 `@field:` 검증을 그대로 둔다. 필드 누락·`null`·빈 문자열 요청의 오류 응답이 같은지 이 테스트로 확인한다([data class 결정 2-5](../../decisions/kotlin-data-class-scope.md)).
- 근거와 기각한 대안: [테스트 보호 장치](../../decisions/kotlin-test-guardrails.md).

## 실행 순서

1. 위 계약 표를 바탕으로 컨트롤러·`docs/api/api-spec.md`·`frontend/src/types/api.ts`의 **엔드포인트·권한·요청 DTO·응답/상태·소비자·테스트** 대응표를 만든다. 웹훅/이벤트처럼 프론트 소비자가 없는 계약도 포함한다. 명세와 코드가 다르면 현재 구현을 확인해 차이를 기록한다.
2. 기존 컨트롤러 테스트의 `MockMvc`에 대표 요청을 추가한다. 검증 오류에는 필드 누락과 명시적 `null`을 각각 넣는다. 동적 ID·시각은 입력을 고정하거나 형식과 의미를 검증해 취약한 전체 문자열 스냅샷을 피한다.
3. Kotlin DTO 전환 시 생성자 인자의 nullable 여부와 기본값을 기존 입력 계약에 맞춘다. Bean Validation은 `@field:NotBlank`, 계산된 `is...` 검증은 필요에 따라 `@get:AssertTrue`처럼 실제 읽는 위치를 지정한다. `@JsonFormat`·OpenAPI 애너테이션도 직렬화/문서 결과로 확인한다.
4. Java `record` 접근자 `name()`과 Kotlin 프로퍼티 getter의 JVM 호출 형태가 달라질 수 있으므로 Java 호출부를 함께 컴파일한다. 공개 JSON이 같아도 Java 소스 호환이 자동으로 유지되지는 않는다.
5. HTTP/공유 직렬화에 영향이 있는 전환 PR은 `cd frontend && node --test tests/*.cjs && npx tsc --noEmit`을 실행한다. [`frontend-ci.yml`](../../../.github/workflows/frontend-ci.yml)은 lint·타입 검사·Node 테스트·static export 빌드를 수행하지만 `frontend/**`/자신의 workflow 경로 필터가 있어 **백엔드만 변경하면 실행되지 않는다**. 필요한 명령을 별도 실행해 PR에 기록하거나 CI 트리거 보완을 별도 변경으로 정한다. 의도된 계약 변경은 백엔드·프론트 타입·API 명세에 함께 반영한다.

## 완료 조건

- 대표 경로의 유효/무효 요청, JSON/빈 본문, 오류·상태·권한과 기존 메시지의 재소비 호환성이 확인된다. 기존 테스트/보강/신규 작성은 [01](01-test-baseline.md)에 구분한다.
- DTO 전환과 Jackson 모듈 도입 전후 결과가 같다. 의도된 계약 변경은 언어 전환과 분리하고, 실제 변경 내역을 명시한다.
- 관련 프론트 타입 검사·Node 테스트가 통과한다. 공개 타입/화면 또는 프론트 빌드 입력이 바뀐 PR은 CI와 같은 `npm run lint`·`STATIC_EXPORT=true npm run build`도 실행한다. Fake PG·직접 listener 검증을 실 PG/브로커 통과로 표현하지 않는다.

이후 작업: [혼용 빌드](04-mixed-build.md), [단계별 코드 전환](07-code-migration.md).
