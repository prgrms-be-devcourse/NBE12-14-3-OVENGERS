# Slot Key API 명세서

> **구현 기준: 기능 동결 커밋 `d1a15ec` / 문서 갱신일: 2026-10-21**. 이번 변경은 코드 변경 없이 인증·오류 응답·Enum 설명을 정리한 문서 최신화이며, 기능 동결 기준을 변경하지 않는다. 아래 날짜별 갱신 기록은 당시 이력이며, 현재 계약은 본문의 API 설명을 따른다. 운영 배포 및 외부 서비스 실동작을 새로 검증한 기록은 아니다.

> **2026-10-19 갱신**: 멱등키 헤더 누락·공통 서버 오류 응답 및 비활성 회원 출입 거절 로그를 구현 기준으로 반영했다. 출입 로그 변경의 배포 완료를 의미하지는 않는다.

> claude.ai 프로젝트 문서 `claude/api-명세서.md` (v0.1, 2026-09-09, 기획서 확정본 기준) 동기화본. 구현 중 세부 규칙이 바뀌면 이 문서도 함께 갱신한다.
> **2026-09-15 갱신**: `docs/core-domain-decisions.md`(확정본) 반영 — 예약 생성이 1단계(즉시 결제)에서 2단계(HOLD → 결제 확인)로 변경, `payment` 삭제·`credit_transaction` 도입, 연장/체크아웃/크레딧 지급 엔드포인트 신규, 도어 토큰 발급·체크인 시간 규칙 변경. 충돌 시 `core-domain-decisions.md`가 우선한다.
> **2026-09-17 갱신 (docs/api)**: 실제 컨트롤러 코드 기준으로 동기화 — Base URL `/api/v1`, 취소 API `POST .../cancel`, 요청/응답 필드, 관리자 예약 목록 필터 미지원, 미구현 API 표시. **프론트는 이 문서를 기준으로 한다.** (claude.ai 프로젝트 문서 `claude/api-명세서.md`는 구버전 — 참고 금지)
> **2026-09-24 갱신 (대단위 1)**: 컨트롤러·DTO·`SecurityConfig`·`ErrorCode` 전수 조사 기반 정합성 동기화 — 실제 응답 DTO 필드, 엔드포인트별 페이지네이션 반환 형태(`PageResponse` vs Spring `Page`), 오프셋 없는 ISO-8601 LocalDateTime(KST), 멱등성 캐시 동작, 실존 `ErrorCode` 반영 및 미구현/결정 필요 항목(`termsVersion`, 동일 멱등키 충돌 검사, 정지 계정 즉시 차단) 분리 명시.

> **2026-10-18 이력**: DB 로그인 세션을 먼저 문서화했다. 당시 미병합 표기는 과거 상태이며, 현재는 PR #350으로 병합된 §2-5 계약을 따른다.
> **2026-10-18 이력 (V1 인증)**: PR #302의 이메일 인증·grant 기반 회원가입·비밀번호 재설정을 반영했다. 이후 DB 세션·RT 회전·로그인 제한은 PR #350에서 구현됐다. 정책 설명은 [인증 정책](../decisions/authentication-policy.md)을 따른다.
> **2026-10-16 갱신 (베스트 공간 랭킹)**: 관리자 통계 API(§14: 공간·지역 랭킹, 상태별 건수)를 구현 기준으로 확정했고, 공간 등록·수정의 `location`을 판교·강남·하남으로 제한했다. 결정 기록은 [decisions/space-ranking.md](../decisions/space-ranking.md).
> **2026-10-16 갱신 (5단계)**: 예약 대기 API(§12), 내 알림 조회 API(§13), `WAITLIST_*` 에러 코드 5개, 새 테이블(`waitlist_entry`, `processed_event`, `notification`)을 반영했다. 슬롯이 풀리면 Outbox → Kafka → 컨슈머가 대기자에게 알림을 저장한다(알림은 힌트이며 예약은 선착순, 자리를 보장하지 않는다). 결정 기록은 [decisions/waitlist.md](../decisions/waitlist.md), [decisions/outbox-kafka.md](../decisions/outbox-kafka.md)를 본다.

## 공통 사항

- Base URL: `/api/v1` (예: `/spaces` → 실제로는 `/api/v1/spaces`)
  - 출입(§7) API도 `/api/v1`로 통일됨(2026-09-17, 기존 `/api`).
- 인증: Access Token(JWT, `Authorization: Bearer <token>`), Refresh Token(DB에 해시 저장, `POST /auth/refresh`, `POST /auth/logout`에서 HttpOnly 쿠키로 전달, Path=`/api/v1/auth`, Max-Age=남은 세션 수명). 세션 수명은 로그인 시점부터 24시간이며 재발급으로 연장하지 않는다.
- JWT 페이로드에는 `id`, `email`, `role`, `sid`, 만료 시각(`exp`)이 포함된다. 인증 필터는 JWT 검증 뒤 `AuthSessionService`로 DB 세션의 소유자·폐기·만료와 회원 `ACTIVE` 상태를 확인한다. 조건을 충족하지 못하면 `INVALID_ACCESS_TOKEN`(401), 세션 조회 장애는 `AUTHENTICATION_SERVICE_UNAVAILABLE`(503)로 차단한다. 세부 계약은 §2-5, 비활성 회원 출입 로그는 §7을 따른다.
- 인증 불필요 API: 이메일 인증번호 발급·확인, 회원가입, 로그인, 비밀번호 재설정, 토큰 재발급, 로그아웃, 공간 목록/상세 조회, 슬롯 가용성 조회, 일반 공개 랭킹(`GET /stats/**`, §14-2), 공간 이미지 공개 조회(`GET /space-images/{fileName}`), 토스페이먼츠 웹훅 수신(`POST /webhooks/tosspayments`, §5-0-3 — 이 경로의 `POST`만 연다).
- 공통 응답: `{ status, code, message, data }` (`status`: SUCCESS | FAIL, 성공 시 `code`: "OK"). 단, `ApiResponse` 클래스에 `@JsonInclude(JsonInclude.Include.NON_NULL)`가 적용되어 있어 Java 상에서 `data == null`인 경우(실패 응답 또는 `ApiResponse<Void>`) 실제 HTTP JSON 직렬화 시 `"data"` 필드 자체가 생략된다.
- 응답 예외: 204 성공 응답과 웹훅 수신 성공(200)은 본문이 없고, 공개 이미지 조회는 바이너리를 반환한다.
- 페이지네이션 형식: 공통 래퍼 `PageResponse<T>` (`content`, `page`, `size`, `totalElements`, `totalPages`)를 사용한다. 대상: 공간 목록(`GET /spaces`), 관리자 공간 목록(`GET /admin/spaces`), 내 예약 목록(`GET /reservations`), 관리자 예약 목록(`GET /admin/reservations`), 관리자 회원 목록(`GET /admin/members`), 관리자 감사 로그(`GET /admin/audit-logs`), 문의 목록(`GET /inquiries`), 관리자 문의 목록(`GET /admin/inquiries`), 내 알림 목록(`GET /notifications`).
- 관리자 회원별 크레딧 거래(`GET /admin/members/{memberId}/credit-transactions`), 예약(`GET /admin/members/{memberId}/reservations`), 상태 변경 이력(`GET /admin/members/{memberId}/status-history`) 조회도 공통 `PageResponse<T>`를 사용한다.
- 시간: ISO-8601 문자열. 서버 직렬화는 타임존 오프셋 표기가 없는 `LocalDateTime` 형식(`"yyyy-MM-dd'T'HH:mm:ss"`)이며, 서버 내부 기준시계는 KST(`Asia/Seoul`, `Clock`)이다. 클라이언트는 이 문자열을 한국 표준시(KST)로 해석하는 것이 설계 목표이다. *(주의: 현재 프론트 `HoldCountdown.tsx`는 `new Date(holdExpiresAt)`로 브라우저 로컬 시간대로 직접 파싱하고 있어 비-KST 환경에서 오차 가능성이 있으며, 시간 파싱 코드 정정은 별도 프론트 코드 과제로 추적한다)*. 날짜는 `"yyyy-MM-dd"`, 시각은 `"HH:mm:ss"`. 예약 시간은 **30분의 배수**만 허용.
- `Idempotency-Key` 헤더(클라이언트는 UUID를 생성해 사용):
  - 예약 결제 `POST /reservations/{id}/pay`와 충전 승인 `POST /credits/charge-orders/confirm`에 필수(누락 시 400 `IDEMPOTENCY_KEY_REQUIRED`). 예약 결제는 필수 헤더 바인딩 후 서비스에서 공백을 검사하며, 충전 승인은 서비스에서 누락·공백을 검사한다. `POST /reservations`(HOLD 생성)는 대상이 아니다. 아래 캐시 설명은 예약 결제 기준이며 충전 승인은 §5-0을 따른다.
  - **캐시 동작**: **성공 응답(200 OK)만 캐시 테이블에 저장**되며, 결제 실패 시에는 트랜잭션이 롤백되어 캐시되지 않는다. 동일 키로 재요청 시 성공했던 건은 저장된 응답을 그대로 재생 반환한다.
  - **동시 경합**: 캐시 조회와 저장이 분리되어 있어 극히 좁은 시간차로 동시 진입 시 한쪽은 정상 처리되고 다른 쪽은 `409 RESERVATION_STATE_CONFLICT`를 받을 수 있다. 409를 받은 쪽이 동일 키로 재시도하면 저장된 최초 성공 응답을 돌려받는다. 크레딧 차감은 1회만 발생한다(`ReservationPaymentConcurrencyTest` 검증).
  - *(결정 필요)*: 동일 키에 다른 요청 본문이 들어왔을 때의 충돌 감지(`IDEMPOTENCY_KEY_CONFLICT`) 및 실패 응답 캐싱, TTL 만료 보관 정책은 현재 미구현 상태이다.
- OpenAPI / Swagger 검증 상태 구분:
  - 공간 이미지 관련 OpenAPI 3.0 명세(PUT/GET 파라미터·바이너리·헤더) 및 Swagger UI HTML 접근성은 `SpaceOpenApiDocumentationTest`(2개 테스트)로 자동 검증됨 (`/swagger-ui/index.html` GET 200 검증 포함. 과거 2026-09-23 실행 기록: 공간 이미지 관련 5개 테스트 클래스 XML 합계 34개 통과). API 전체 DTO 스키마 전수 검증이나 이번 문서 최신화 직후의 테스트 재실행 근거는 아님.
  - Swagger UI 진입/리다이렉트 경로(`/swagger-ui.html`) 및 HTML 리소스(`/swagger-ui/index.html`)가 구성되어 있으나, 자동화 테스트로 GET 200이 직접 확인된 대상 경로는 `/swagger-ui/index.html` 1건이다.
  - 브라우저를 통한 `Try it out` 대화형 실사용 테스트는 미검증 상태임.

### 헤더 누락 및 공통 예외 응답

> **공통 예외 검증 유의사항**: 필수 쿼리 파라미터 누락은 `MissingServletRequestParameterException` 대상이다. 기능 동결 기준 `GlobalExceptionHandler`에는 이 예외의 전용 처리기가 없고 마지막 `Exception` 처리기가 있어, 기존 명세의 400을 보장한다고 단정하지 않는다. `upToId`, `period`, `date` 누락은 실제 요청으로 상태 코드·본문 검증이 필요하다. 이번 문서 변경에서는 코드를 수정하거나 이 요청을 실행하지 않았다. 헤더 누락 처리와는 별개다.

- 필수 `Idempotency-Key` 헤더가 빠지면 `MissingRequestHeaderException`을 처리하여 HTTP 400과 아래 공통 응답을 반환한다. 예약 결제 서비스는 호출되지 않는다. 인증이 필요한 API에서는 인증 검증을 먼저 통과해야 한다.

```json
{
  "status": "FAIL",
  "code": "IDEMPOTENCY_KEY_REQUIRED",
  "message": "Idempotency-Key 헤더가 필요합니다."
}
```

- 다른 필수 헤더 누락 및 읽거나 변환할 수 없는 요청 본문은 HTTP 400 / `VALIDATION_FAILED`로 처리한다.
- `GlobalExceptionHandler`의 개별 처리기에 해당하지 않는 예외는 HTTP 500 / `INTERNAL_SERVER_ERROR`, 메시지 `서버 내부 오류가 발생했습니다.`로 반환한다. 내부 예외는 서버 로그에 기록하고 응답에 노출하지 않는다.
- 실패 응답은 `status`, `code`, `message`를 사용하며 `data`가 null이면 JSON에서 생략한다. 이 변경은 오류 응답 형식의 통일이며, 결제 중복 차감 방지 로직을 새로 구현한 것은 아니다.

### HTTP 상태 코드 기준

| 코드 | 의미 | 예 |
| --- | --- | --- |
| 200 | 조회/처리 성공 | 목록·상세, 결제 확정, 취소 성공 |
| 202 | 처리 결과 미확정 | 충전 승인 결과 미확정 또는 처리 중(`CONFIRMING`) |
| 201 | 생성 성공 | 예약(HOLD) 생성, 회원가입, 공간 등록, 도어 토큰 발급, 문의 등록 |
| 204 | 본문 없음 성공 | 로그아웃, 회원탈퇴, 대기 취소 |
| 400 | 요청 형식 오류 | 필수 필드 누락, 형식 위반, 30분 단위 위반, 가격 단위 위반, 멱등키 누락, 이미지 형식/해상도 위반, 문의 입력값 오류, 인증번호 불일치·사용 불가(`VERIFICATION_CODE_MISMATCH`, `VERIFICATION_CHALLENGE_INVALID`), 인증 증표 사용 불가(`VERIFICATION_GRANT_INVALID`), 비밀번호 확인 불일치(`PASSWORD_CONFIRM_MISMATCH`) |
| 401 | 인증 실패 | 토큰 없음(`AUTHENTICATION_REQUIRED`), 토큰 만료/위조(`INVALID_ACCESS_TOKEN`, `INVALID_REFRESH_TOKEN`), 로그인 자격증명 불일치(`INVALID_CREDENTIALS`), 탈퇴 계정의 로그인·재발급(`ACCOUNT_WITHDRAWN`) |
| 403 | 인가 실패 | 역할 불충분(`ACCESS_DENIED`, `FORBIDDEN_ROLE`), 소유권 불일치(`FORBIDDEN_NOT_OWNER`), 정지 계정 로그인/재발급 거절(`ACCOUNT_INACTIVE`), 이메일 인증 최종 잠금(`VERIFICATION_ADMIN_LOCKED`) |
| 404 | 자원 없음 | 존재하지 않는 spaceId(`SPACE_NOT_FOUND`), reservationId(`RESERVATION_NOT_FOUND`), memberId(`MEMBER_NOT_FOUND`), inquiryId(`INQUIRY_NOT_FOUND`), waitlistEntryId(`WAITLIST_NOT_FOUND`), fileName(`IMAGE_NOT_FOUND`) |
| 409 | 상태 충돌 | 슬롯 중복(`RESERVATION_SLOT_CONFLICT`), 이미 취소/만료/확정된 예약에 대한 요청(`RESERVATION_STATE_CONFLICT`), `space.version` 불일치(`SPACE_VERSION_MISMATCH`), 공간 운영시간 축소 충돌(`SPACE_OPERATING_HOURS_CONFLICT`), 이메일 중복(`EMAIL_ALREADY_EXISTS`), 다른 충전 주문의 멱등키 재사용(`IDEMPOTENCY_KEY_REUSED`), 이미 신청한 대기(`WAITLIST_DUPLICATE`), 탈퇴한 회원의 정지·복구·크레딧 지급(`MEMBER_WITHDRAWN`) |
| 413 | 페이로드 초과 | 공간 대표 사진 파일 크기 5MB 초과(`IMAGE_SIZE_EXCEEDED`) |
| 422 | 비즈니스 규칙 위반 | 크레딧 잔액 부족(`INSUFFICIENT_BALANCE`), 연장 불가(`RESERVATION_EXTEND_NOT_ALLOWED`), 비활성 공간(`SPACE_INACTIVE`), 관리자 정지/복구 불가(`TARGET_IS_ADMIN`), 자기 자신 크레딧 지급 불가(`SELF_GRANT_NOT_ALLOWED`), 답변 완료 문의 수정 불가(`INQUIRY_ALREADY_ANSWERED`), 대기 불필요(`WAITLIST_NOT_NEEDED`), 본인 점유와 겹침(`WAITLIST_ALREADY_HOLDING`), 대기 3건 초과(`WAITLIST_LIMIT_EXCEEDED`) |
| 429 | 요청 제한 | 이메일 인증 임시 차단(`VERIFICATION_TEMPORARILY_BLOCKED`), 인증번호 30초 내 재요청(`VERIFICATION_RESEND_TOO_SOON`). 현재 구현: 이메일별 로그인 제한(`LOGIN_RATE_LIMITED`, `Retry-After` 헤더). 그 밖의 범용 API 요청 제한은 미구현 |
| 500 | 서버 오류 | 이미지 디스크 저장 실패(`IMAGE_STORAGE_ERROR`), 공통 예외 처리기의 서버 오류(`INTERNAL_SERVER_ERROR`) |
| 503 | 외부 의존 실패 | 인증 메일 발송 실패(`VERIFICATION_MAIL_FAILED`), 보호 요청의 세션 조회 또는 로그인 제한 저장소 장애(`AUTHENTICATION_SERVICE_UNAVAILABLE`) — §2-5 참조 |

## 2. 인증 (Auth)

> 기준: PR #350(`4051bb3`)의 DB 세션·RT 회전·로그인 제한이 병합된 코드. V1 설명은 과거 동작이며, 현재 계약은 아래 표와 §2-5를 따른다. 정책 배경은 [인증 정책](../decisions/authentication-policy.md), 구현 구조는 [V2 구현 해설](../architecture/auth-v2-implementation.md)을 참고한다.

| API | 메서드/경로 | 인증 | 비고 |
| --- | --- | --- | --- |
| 인증번호 발급 | `POST /auth/verification/issue` | 불필요 | 2-1 참조. 응답 200 `{ challengeId, expiresAt, resendAvailableAt }` |
| 인증번호 확인 | `POST /auth/verification/check` | 불필요 | 2-2 참조. 응답 200 `{ grant, expiresAt }`, `Cache-Control: no-store` |
| 회원가입 | `POST /auth/signup` | 불필요 | 2-3 참조. SIGNUP grant 필수. 응답 201 `{ memberId, email, nickname }`. `role`은 요청으로 받지 않음, 서버가 항상 USER로 생성. 가입 트랜잭션에서 `SIGNUP_GRANT` 크레딧 지급(`app.credit.signup-grant`). 가입 후 로그인은 별도 |
| 비밀번호 재설정 | `POST /auth/password-reset` | 불필요 | 2-4 참조. PASSWORD_RESET grant 필수. 응답 200 `ApiResponse<Void>`(`data` 생략) |
| 로그인 | `POST /auth/login` | 불필요 | 본문 검증 뒤 이메일별 요청 제한을 적용하고 비밀번호·회원 상태를 검증한다. 성공 시 DB 세션 생성, AT 본문 `{ accessToken }`, RT HttpOnly 쿠키(`refreshToken`, Path=`/api/v1/auth`, Max-Age=남은 세션 수명, `Secure`·`SameSite`는 `app.auth.cookie.*`)를 반환한다. 오류: `VALIDATION_FAILED`(400), `INVALID_CREDENTIALS`(401), `ACCOUNT_WITHDRAWN`(401), `ACCOUNT_INACTIVE`(403), `LOGIN_RATE_LIMITED`(429, `Retry-After` 포함), 제한 저장소 장애 시 `AUTHENTICATION_SERVICE_UNAVAILABLE`(503) |
| 토큰 재발급 | `POST /auth/refresh` | 불필요(refreshToken 쿠키) | RT 해시·세션·회원 상태를 검증하고 기존 RT를 소비한 뒤 새 AT 본문과 새 RT 쿠키를 반환한다. 세션 만료는 연장하지 않는다. 소비된 RT 재사용 시 해당 세션과 소속 RT를 폐기한다. 오류: `INVALID_REFRESH_TOKEN`(401), `ACCOUNT_WITHDRAWN`(401), `ACCOUNT_INACTIVE`(403). 세부 조건은 §2-5 참조 |
| 회원탈퇴 | `POST /auth/withdraw` | 필요(USER) | 현재 비밀번호 확인 후 진행 예약이 없으면 논리적 탈퇴. 응답 204, RT 쿠키 만료. 세션·RT·출입 키 폐기 및 잔액 소멸. §2-6 참조 |
| 로그아웃 | `POST /auth/logout` | 불필요(refreshToken 쿠키) | RT가 속한 세션과 해당 세션의 RT를 폐기하고 쿠키를 만료시킨다. 이후 해당 세션의 기존 AT는 인증되지 않는다. 다른 로그인 세션은 유지한다. 쿠키가 없거나 미등록 토큰이어도 204 No Content |

공통 메모 (현재 코드 기준):
- 위 요청 본문의 형식 오류는 `VALIDATION_FAILED`(400)이며 메시지는 필드 오류 메시지를 `, `로 이어 붙인다. 인증번호 발급·확인 DTO는 `code` 외에 사용자 메시지를 따로 지정하지 않아 Bean Validation 기본 메시지가 나간다.
- `purpose`에 정의되지 않은 값을 보내는 등 JSON 역직렬화가 실패하면 `HttpMessageNotReadableException` 처리기가 HTTP 400 / `VALIDATION_FAILED`를 공통 `ApiResponse`로 반환한다.
- `CustomAuthenticationFilter`의 Access Token 검사 생략 목록에는 signup·login·password-reset·refresh·logout만 있고 `verification/issue`·`verification/check`는 없다. 두 API는 `permitAll`이라 헤더 없이 호출하면 정상 처리되지만, 유효하지 않은 `Authorization: Bearer` 헤더를 붙이면 필터에서 `INVALID_ACCESS_TOKEN`(401)이 먼저 반환된다.
- 이메일 인증 실패 차단 및 30초 재발송 간격 외에 로그인 이메일별 요청 제한이 구현돼 있다(§2-5). 이 로그인 제한을 모든 API의 범용 요청 제한으로 해석하지 않는다.

### 2-1. 인증번호 발급 — `POST /auth/verification/issue`

- **요청**: `email`(필수, 이메일 형식, 254자 이하), `purpose`(필수, `SIGNUP` | `PASSWORD_RESET`)
- **응답 200** `VerificationIssueResponse`: `challengeId`(Long), `expiresAt`(발급 후 3분), `resendAvailableAt`(발급 후 30초)
- **처리**: 이메일·목적별 `verification_guard`를 만들거나 비관적 잠금으로 확보 → 최종 잠금·임시 차단·30초 간격 검사 → 발송 시각 기록 → 같은 Guard의 미인증·미무효 challenge 무효화 → 새 8자리 숫자 코드의 HMAC 검증값으로 challenge 저장(`saveAndFlush`) → 메일 발송.
- **발송 대상**: `SIGNUP`은 항상 발송한다(이미 가입된 이메일인지 여기서 검사하지 않으며, 중복은 회원가입 단계의 `EMAIL_ALREADY_EXISTS`로 거절). `PASSWORD_RESET`은 해당 이메일의 회원과 `local_credential`이 있을 때만 발송하고, 없으면 메일 없이 같은 형태의 200을 반환한다.
- **오류**: `VALIDATION_FAILED`(400), `VERIFICATION_ADMIN_LOCKED`(403), `VERIFICATION_TEMPORARILY_BLOCKED`(429), `VERIFICATION_RESEND_TOO_SOON`(429), `VERIFICATION_MAIL_FAILED`(503 — `MailException`을 변환하며 트랜잭션이 롤백되어 challenge와 발송 시각이 저장되지 않는다)
- 메일 설정은 [Gmail SMTP 설정](../operations/gmail-smtp-setup.md)을 따른다.

### 2-2. 인증번호 확인 — `POST /auth/verification/check`

- **요청**: `email`(필수, 이메일 형식, 254자 이하), `purpose`(필수), `challengeId`(필수, 양수), `code`(필수, 정규식 `[0-9]{8}` — 앞자리 0을 보존하는 문자열)
- **응답 200** `VerificationCheckResponse`: `grant`(32바이트 난수의 Base64URL 문자열, 이 응답에서만 원문 제공), `expiresAt`(grant 발급 후 5분). 응답에 `Cache-Control: no-store`를 붙인다.
- **처리 순서와 결과**: Guard 잠금 → 차단 중이면 차단 오류 → 해당 Guard의 **가장 최근** challenge만 대상으로 ID 일치·미인증·미무효·미만료 확인 → 코드 비교 → 성공 시 challenge 인증 완료, Guard 실패 상태 초기화, grant 해시 저장.
- 실패 결과는 서비스가 예외 대신 결과 값으로 반환해 트랜잭션을 커밋한 뒤 Controller가 HTTP 오류로 바꾼다. 따라서 오입력 횟수와 차단 상태는 오류 응답과 함께 저장된다.
- **오류**:
  - `VERIFICATION_CHALLENGE_INVALID`(400): challenge 없음, 최신 challenge가 아님, 만료·인증 완료·무효화됨. 오입력 횟수를 늘리지 않는다.
  - `VERIFICATION_CODE_MISMATCH`(400): 코드 불일치(현재 단계 1~4회째). 오입력 횟수 증가.
  - `VERIFICATION_TEMPORARILY_BLOCKED`(429): 5회째 오입력으로 1·2차 차단이 시작됐거나 이미 임시 차단 중. 차단을 시작한 challenge는 무효화된다.
  - `VERIFICATION_ADMIN_LOCKED`(403): 3차 차단(최종 잠금)에 도달했거나 이미 잠김. 이때 해당 이메일의 ACTIVE 회원은 SUSPENDED로 바뀌고 `LOCK_MEMBER_BY_VERIFICATION` 감사 로그(행위자 NULL)가 남는다.
  - `VALIDATION_FAILED`(400)
- 서비스 결과에는 `blockedUntil`이 있으나 응답 본문에는 포함하지 않는다.

### 2-3. 회원가입 — `POST /auth/signup`

- **요청**: `email`(필수, 이메일 형식, 254자 이하), `password`(필수), `passwordConfirm`(필수), `nickname`(필수, 2~50자), `grant`(필수, 128자 이하). 비밀번호 강도 규칙은 서버에 없다(`@NotBlank`만).
- **응답 201** `SignupResponse`: `memberId`, `email`, `nickname`
- **처리 순서**: `password`·`passwordConfirm` 비교 → SIGNUP grant 검증(같은 이메일·목적 Guard 잠금 후 해시 조회, Guard 귀속·미소비·미무효·미만료 확인) → 이메일 중복 확인 → `member` 저장(`email_verified_at` = 해당 challenge의 인증 시각) → `local_credential` 저장 → grant 소비 → 가입 크레딧 지급. 모두 한 트랜잭션이다.
- 비밀번호 확인 불일치는 grant를 검증·소비하기 전에 거절하므로 같은 grant로 다시 시도할 수 있다.
- **오류**: `VALIDATION_FAILED`(400), `PASSWORD_CONFIRM_MISMATCH`(400), `VERIFICATION_GRANT_INVALID`(400 — 없음·다른 이메일/목적·만료·소비됨), `EMAIL_ALREADY_EXISTS`(409, DB UNIQUE 위반도 같은 코드로 변환)

### 2-4. 비밀번호 재설정 — `POST /auth/password-reset`

- **요청**: `email`(필수, 이메일 형식, 254자 이하), `grant`(필수, 128자 이하), `password`(필수), `passwordConfirm`(필수)
- **응답 200** `ApiResponse<Void>`
- **처리 순서**: 확인값 비교 → PASSWORD_RESET grant 검증 → 회원 비관적 잠금 조회 → `local_credential` 조회 → 비밀번호 해시·`password_changed_at` 변경 → grant 소비. 한 트랜잭션이다. 회원 상태(ACTIVE 여부)는 이 API에서 검사하지 않는다.
- **오류**: `VALIDATION_FAILED`(400), `PASSWORD_CONFIRM_MISMATCH`(400), `VERIFICATION_GRANT_INVALID`(400 — grant 문제뿐 아니라 회원 또는 로컬 자격 증명이 없을 때도 같은 코드)
- 비밀번호 재설정 성공 시 `AuthSessionService.revokeAllForMember`로 해당 회원의 모든 세션과 RT를 폐기한다. 기존 AT는 이후 DB 세션 검증에서 거절된다.
- 이 엔드포인트에는 Swagger `@Operation` 설명이 없다.

### 2-5. V2 1번 변경 — DB 로그인 세션 (dev 병합 전)

> **현재 상태**: PR #350(`4051bb3`)으로 DB 세션·RT 회전·로그인 제한이 dev에 병합됐다. 이 절의 제목은 기존 링크 보존을 위해 유지하며, "dev 병합 전"은 최초 작성 시점이다. 아래 개별 커밋 표기는 구현 이력이며 현재 미병합이라는 뜻이 아니다.

| 대상 | V2 1번 변경 후 계약 |
| --- | --- |
| 로그인 | 로그인마다 서버 세션을 만든다. 응답 형식은 같다. AT payload에 `sid`(세션 ID)가 추가되고, AT 만료는 `min(발급 + 5분, 세션 만료)`다. RT 쿠키의 DB 행은 그 세션에 연결되며 만료는 세션 만료(로그인 + 24시간)와 같다 |
| 로그인 요청 제한 (4번, 구현 이력 `ee39f91`) | 요청 본문 검증을 통과한 로그인 요청을 이메일별로 센다. UTC 정렬 고정 15분 구간마다 성공·실패 합산 10회까지 처리하고, 11번째부터 구간 종료까지 비밀번호 확인 없이 429 `LOGIN_RATE_LIMITED`(공통 `ApiResponse`)와 `Retry-After: <남은 초>`(올림)를 반환한다. 미가입 이메일도 같다. 형식 오류(400)는 세지 않는다. 제한 저장소 장애는 503 `AUTHENTICATION_SERVICE_UNAVAILABLE`. 기존 세션·회원 상태에는 영향이 없다. **오류 계약 추가**(새 코드·헤더)이며 성공 응답 계약은 같다 |
| 보호 요청 | JWT 서명·만료 검증 뒤 `sid`가 없거나 문자열이 아니면, 또는 세션이 없음·폐기·만료이거나 JWT `id`가 세션 소유 회원과 다르거나 회원이 `ACTIVE`가 아니면 `INVALID_ACCESS_TOKEN`(401). 정지·탈퇴 회원의 기존 AT도 이 경로로 401이다(`ACCOUNT_INACTIVE`가 아님). 세션 조회 중 DB 장애(트랜잭션 시작 실패 포함)는 인증 없이 `AUTHENTICATION_SERVICE_UNAVAILABLE`(503, 공통 `ApiResponse`) |
| 토큰 재발급 | 기존 검사에 세션 미폐기·미만료 확인이 더해진다(위반 시 `INVALID_REFRESH_TOKEN` 401). 새 AT는 같은 `sid`이고 세션·RT 만료는 연장되지 않는다. 1·2번 기준 동작에서 3번 구현 커밋 `a29dfc9`의 RT 회전 동작으로 변경됐다(V2 3번 이슈) |
| 토큰 재발급 (3번, 구현 이력 `a29dfc9`) | 성공할 때마다 새 AT(본문 `{ accessToken }`, 형식 동일)와 새 RT 쿠키(`Set-Cookie: refreshToken`, 기존 속성, `Max-Age` = 남은 세션 수명)를 준다. 구 RT는 한 번만 쓸 수 있다. 이미 사용한 RT를 다시 보내면 401 `INVALID_REFRESH_TOKEN`과 함께 **그 로그인 세션과 세션의 모든 RT가 폐기**된다(같은 회원의 다른 세션은 유지). 같은 RT 동시 제출은 하나만 교체되고 나머지가 재사용으로 판정되어 결국 그 세션이 폐기된다. grace 기간은 없다. 로그인 응답의 RT 쿠키 `Max-Age`도 남은 세션 수명(로그인 직후 약 86400)이다 |
| 계정 사건 (V2 2번, 커밋 `02c3557`) | 탈퇴(`POST /auth/withdraw`), 관리자 정지(`PATCH /admin/members/{id}/suspend`), 인증 최종 잠금 자동 정지(`POST /auth/verification/check`의 `VERIFICATION_ADMIN_LOCKED`), 비밀번호 재설정(`POST /auth/password-reset`)이 성공하면 그 회원의 모든 세션과 RT를 폐기한다. 이후 옛 AT는 401, 옛 RT로 refresh도 401이다. 관리자 복구(`PATCH /admin/members/{id}/restore`)는 폐기된 세션을 되살리지 않으며 새 로그인만 성공한다. 응답 형식·상태 코드는 바뀌지 않는다 |
| 로그아웃 | 쿠키 RT가 속한 세션과 그 세션의 RT를 함께 폐기한다. 이후 그 세션의 기존 AT는 401, RT로 refresh도 401. 같은 회원의 다른 로그인 세션은 유지된다. 쿠키가 없거나 미등록이면 기존처럼 204 |
| 전환 (V24) | 기존 RT는 마이그레이션에서 삭제되고, `sid` 없는 기존 AT는 거부되므로 배포 후 모든 사용자가 다시 로그인해야 한다 |

로그인 요청 제한·RT 회전·계정 사건에 따른 세션 폐기는 PR #350의 구현에 포함된다. 로그인 제한 저장소와 보호 요청의 세션 조회 장애는 명시적인 503 처리 경로를 갖는다. 그 외 컨트롤러에서 전파된 미처리 예외는 공통 예외 처리기의 500 / `INTERNAL_SERVER_ERROR` 대상이다.

### 2-6. 회원탈퇴 — `POST /auth/withdraw`

- **요청**: 보호 API이며 USER만 가능. 바디 `{ "currentPassword": "..." }`(공백 불가).
- **처리**: 회원 비관적 잠금 → 역할·상태 및 현재 비밀번호 검증 → `HELD`·`CONFIRMED`·`IN_USE` 예약 존재 시 거절 → 잔여 크레딧 소멸(`WITHDRAWAL_EXPIRATION`) → 회원 `WITHDRAWN`·탈퇴 시각 기록 → 전체 로그인 세션·RT와 활성 출입 키 폐기. 하나의 트랜잭션이며 예약을 자동 취소하지 않는다.
- **응답**: 204 No Content, RT 쿠키 삭제(`Max-Age=0`). 폐기된 세션의 기존 AT는 이후 인증에서 401로 거절된다. `HELD`는 직접 취소하거나 만료 정리 후 다시 탈퇴할 수 있다.
- **오류**: `VALIDATION_FAILED`(400), `CURRENT_PASSWORD_MISMATCH`(400), `AUTHENTICATION_REQUIRED`·`INVALID_ACCESS_TOKEN`(401), `FORBIDDEN_ROLE`(403), `WITHDRAWAL_ACTIVE_RESERVATION`(409). 비활성 회원의 보호 요청은 필터에서 먼저 차단되며, 서비스 내부의 `ACCOUNT_INACTIVE`·`ACCOUNT_WITHDRAWN` 검사와 구분한다.
- 기능 동결 코드의 탈퇴 Swagger 설명에 남아 있는 “기존 액세스 토큰은 만료까지 유효” 문구는 현재 세션 폐기 동작과 다르다. 이번 이슈는 프로덕션 코드 변경을 제외하므로 주석 정정은 별도 과제로 남긴다. 정책 상세는 [회원탈퇴 정책](../decisions/member-withdrawal-policy.md)을 따른다.

## 3. 회원 (Member)

- `GET /members/me` (인증 필요): 본인 정보 조회. 응답 `MemberResponse` (`memberId`, `email`, `nickname`, `role`, `balance`, `createdAt`). 오류: `AUTHENTICATION_REQUIRED`(401), `INVALID_ACCESS_TOKEN`(401)
- `PATCH /members/me` (인증 필요): 본인 닉네임 수정. 요청 `{ nickname }`(필수, 공백 불가, 2~50자). 응답 200 `ApiResponse<MemberResponse>`. 오류: `VALIDATION_FAILED`(400), `AUTHENTICATION_REQUIRED`(401), `INVALID_ACCESS_TOKEN`(401)
- `GET /admin/members` (ADMIN): 관리자 회원 목록 조회. 쿼리 파라미터 `status`, `keyword`, `page`, `size` 지원. 응답 `ApiResponse<PageResponse<AdminMemberResponse>>` (원소: `memberId`, `email`, `nickname`, `role`, `status`, `balance`, `createdAt`, `lastStatusChangedAt`, `lastStatusChangeReason`). 오류: `AUTHENTICATION_REQUIRED`(401), `ACCESS_DENIED`(403), `FORBIDDEN_ROLE`(403)
- `GET /admin/members/{memberId}` (ADMIN): 특정 회원의 기본 정보, 전체 예약 건수, 정지 기록 건수 조회. 응답 `ApiResponse<AdminMemberDetailResponse>` (`member`: `AdminMemberResponse`, `totalReservationCount`, `suspensionCount`). 전체 예약 건수에는 취소·만료된 예약도 포함하며, 정지 기록은 `SUSPEND_MEMBER`, `LOCK_MEMBER_BY_VERIFICATION` 감사 로그만 집계한다(복구 기록 제외). 오류: `AUTHENTICATION_REQUIRED`(401), `INVALID_ACCESS_TOKEN`(401), `ACCESS_DENIED`(403), `MEMBER_NOT_FOUND`(404)
- `GET /admin/members/{memberId}/credit-transactions` (ADMIN): 특정 회원의 크레딧 거래 내역 조회. 쿼리 파라미터 `page`(0부터 시작), `size`(기본 20) 지원. 응답 `ApiResponse<PageResponse<AdminMemberCreditTransactionResponse>>` (원소: `transactionId`, `type`, `amount`, `balanceAfter`, `reason`, `createdAt`). 거래 발생 시각(`createdAt DESC`, 동일 시각은 `id DESC`) 기준 최신순 정렬. 오류: `AUTHENTICATION_REQUIRED`(401), `INVALID_ACCESS_TOKEN`(401), `ACCESS_DENIED`(403), `MEMBER_NOT_FOUND`(404)
- `GET /admin/members/{memberId}/reservations` (ADMIN): 특정 회원의 전체 예약 내역 조회. 쿼리 파라미터 `page`(0부터 시작), `size`(기본 20) 지원. 응답 `ApiResponse<PageResponse<AdminMemberReservationResponse>>` (원소: `reservationId`, `spaceId`, `spaceName`, `startTime`, `endTime`, `status`, `totalAmount`, `createdAt`). 취소·만료된 예약도 포함하며, 예약 ID 내림차순으로 정렬한다. 공간 정보를 찾지 못하면 `spaceName`은 `"삭제된 공간"`으로 반환한다. 오류: `AUTHENTICATION_REQUIRED`(401), `INVALID_ACCESS_TOKEN`(401), `ACCESS_DENIED`(403), `MEMBER_NOT_FOUND`(404)
- `GET /admin/members/{memberId}/status-history` (ADMIN): 특정 회원의 계정 정지·복구 및 인증 실패 잠금 이력 조회. 쿼리 파라미터 `page`(0부터 시작), `size`(기본 20) 지원. 응답 `ApiResponse<PageResponse<AdminMemberStatusHistoryResponse>>` (원소: `historyId`, `action`, `actorMemberId`, `reason`, `beforeValue`, `afterValue`, `changedAt`). `SUSPEND_MEMBER`, `REACTIVATE_MEMBER`, `LOCK_MEMBER_BY_VERIFICATION` 기록만 조회하며, 발생 시각(`createdAt DESC`, 동일 시각은 `id DESC`) 기준 최신순 정렬. 시스템 처리 기록은 `actorMemberId`가 `null`일 수 있다. 오류: `AUTHENTICATION_REQUIRED`(401), `INVALID_ACCESS_TOKEN`(401), `ACCESS_DENIED`(403), `MEMBER_NOT_FOUND`(404)
- `PATCH /admin/members/{memberId}/suspend` (ADMIN): `reason` 1~500자 필수, 대상은 USER만, `audit_logs` 기록. 응답 `MemberStatusChangeResponse`. 오류: `VALIDATION_FAILED`(400), `AUTHENTICATION_REQUIRED`(401), `ACCESS_DENIED`(403), `MEMBER_NOT_FOUND`(404), `MEMBER_WITHDRAWN`(409), `TARGET_IS_ADMIN`(422)
- `PATCH /admin/members/{memberId}/restore` (ADMIN): suspend와 동일 구조, status → ACTIVE. `audit_logs` 기록. 응답 `MemberStatusChangeResponse`. 오류: `VALIDATION_FAILED`(400), `AUTHENTICATION_REQUIRED`(401), `ACCESS_DENIED`(403), `MEMBER_NOT_FOUND`(404), `MEMBER_WITHDRAWN`(409), `TARGET_IS_ADMIN`(422)
- `POST /admin/members/{memberId}/credits` (ADMIN): 크레딧 지급(`ADMIN_GRANT`). `amount`(양수), `reason` 1~500자 필수. **자기 자신에게는 지급 불가**(`actor_id != member_id`). **지급만 가능, 회수 없음.** `credit_transaction`과 `audit_logs` 양쪽에 기록. 응답 `AdminMemberResponse`. 오류: `VALIDATION_FAILED`(400), `AUTHENTICATION_REQUIRED`(401), `ACCESS_DENIED`(403), `MEMBER_NOT_FOUND`(404), `MEMBER_WITHDRAWN`(409), `SELF_GRANT_NOT_ALLOWED`(422)

## 4. 공간 (Space)

- `GET /spaces` (인증 불필요): `page`, `size`, `keyword`. `status=ACTIVE`인 공간만 기본 노출. 응답 `ApiResponse<PageResponse<SpaceResponse>>` (원소: `id`, `name`, `location`, `capacity`, `pricePerSlot`, `imagePath`, `openingTime`, `closingTime`, `status`)
  - **검색 키워드 (`keyword`)**: 오피스 이름(`name`), 위치(`location`), 설명(`description`)을 통합 부분일치 검색하며 서버에서 앞뒤 공백을 자동 제거(`trim()`)한다.
  - **오피스 필터**: `location`(문자열 부분일치, 공백 trim), `minPrice`/`maxPrice`(Long, 0 이상, `minPrice <= maxPrice`, 위반 시 400 `VALIDATION_FAILED`). 0원은 검색 경계값으로 허용됨.
  - **시간대 및 영업시간 필터**: `date`+`startTime`+`endTime`(HH:mm:ss, 30분 단위, 초·나노초 00)을 셋 다 지정해야 함. 1~2개만 부분 입력하거나 30분 단위를 위반하면 400 `VALIDATION_FAILED` 거절, `startTime >= endTime`이면 400 `INVALID_TIME_RANGE` 거절. 조건을 모두 만족할 경우 영업시간(`openingTime <= startTime` AND `closingTime >= endTime`)에 부합하고 해당 시간대에 점유 슬롯이 없는 공간만 반환됨. 조회 시점의 가용성 정보는 참고용 스냅샷이며, 실제 예약 생성 시점에 다시 검증합니다.
- `GET /spaces/{spaceId}` (인증 불필요): 상세. 응답 `SpaceDetailResponse` (`id`, `name`, `location`, `description`, `capacity`, `pricePerSlot`, `imagePath`, `openingTime`, `closingTime`, `status`, `version`, `createdAt`, `updatedAt`). 오류: `SPACE_NOT_FOUND`(404)
- `GET /spaces/{spaceId}/slots?date=YYYY-MM-DD` (인증 불필요): 운영시간을 30분 단위로 쪼갠 예약 가능 여부. 응답 `SpaceSlotAvailabilityResponse` (`spaceId`, `date`, `slots: [{ startTime, endTime, isAvailable }]`). **참고용 스냅샷**(실제 확정 여부는 슬롯 INSERT 시점의 UNIQUE 제약으로만 판정 — 사전 조회는 화면 표시용일 뿐 규칙이 아니다). 오류: `VALIDATION_FAILED`(400), `SPACE_NOT_FOUND`(404)
- `GET /admin/spaces` (ADMIN): 관리자 공간 목록 조회. 쿼리 파라미터 `status`, `keyword`(이름·위치·설명 통합 검색, 공백 trim), `page`, `size`. 응답 `ApiResponse<PageResponse<SpaceDetailResponse>>`. 오류: `AUTHENTICATION_REQUIRED`(401), `ACCESS_DENIED`(403)
- `GET /admin/spaces/{spaceId}` (ADMIN): 관리자 공간 상세 조회. 응답 `SpaceDetailResponse`. 오류: `AUTHENTICATION_REQUIRED`(401), `ACCESS_DENIED`(403), `SPACE_NOT_FOUND`(404)
- `POST /admin/spaces` (ADMIN): `location`은 `판교`·`강남`·`하남` 중 하나여야 한다(그 밖의 값은 `VALIDATION_FAILED`(400)). `pricePerSlot`은 100원 단위 양수, `openingTime`/`closingTime`은 30분 경계이며 시작이 종료보다 빨라야 한다. 등록자 ID·생성 시각은 서버가 설정하고 `audit_logs`에 기록한다(`REGISTER_SPACE`). 본문에 비어 있지 않은 `imagePath`를 전달하면 `VALIDATION_FAILED`(400)로 거절되며, 사진은 등록 후 사진 업로드 API를 통해 설정한다. 응답: 201, `data` = `SpaceDetailResponse`. 오류: `VALIDATION_FAILED`(400), `INVALID_OPERATING_HOURS`(400), `INVALID_PRICE_UNIT`(400), `AUTHENTICATION_REQUIRED`(401), `ACCESS_DENIED`(403)
- `PATCH /admin/spaces/{spaceId}` (ADMIN): 부분 수정 + status(ACTIVE/INACTIVE). `location`을 보내면 `판교`·`강남`·`하남` 중 하나여야 하고(아니면 `VALIDATION_FAILED`(400)), 생략하면 기존 값을 유지한다. 배타 잠금(`PESSIMISTIC_WRITE`) 하에 실행. 운영시간 부분 수정도 기존 반대편 시각과 함께 30분 경계·순서를 검증한다. 운영시간 축소 시 현재 이후의 유효 점유 슬롯(`HELD`, `CONFIRMED`, `IN_USE`, `COMPLETED`) 중 새 운영시간 밖 슬롯이 존재하면 `SPACE_OPERATING_HOURS_CONFLICT`(409)로 거절되며 Space와 Audit 모두 변경되지 않는다. 가격 변경 시 `version` 증가(비즈니스 버전) — 이미 확정된 예약의 스냅샷/총액에는 영향 없음. 본문에 비어 있지 않은 `imagePath`를 전달하면 `VALIDATION_FAILED`(400)로 거절되며, 사진 수정은 별도 업로드 API를 사용한다. INACTIVE로 바꿔도 기존 예약 유지, 신규 예약만 차단. 수정 완료 시 `audit_logs`에 기록한다(`MODIFY_SPACE`). 응답: 200, `data` = `SpaceDetailResponse`. 오류: `SPACE_NOT_FOUND`(404), `VALIDATION_FAILED`(400), `INVALID_OPERATING_HOURS`(400), `INVALID_PRICE_UNIT`(400), `SPACE_OPERATING_HOURS_CONFLICT`(409), `AUTHENTICATION_REQUIRED`(401), `ACCESS_DENIED`(403)
- `PUT /admin/spaces/{spaceId}/image` (ADMIN, `multipart/form-data`): 대표 사진 파일(`file`, 5MB 이하, JPEG/PNG, 가로·세로 최대 4096px) 업로드. 바이너리 서명 및 해상도 사전 검증 후 UUID 파일명으로 디스크에 안전하게 저장하고 `Space.imagePath`(`/api/v1/space-images/{fileName}`)를 갱신하며 `audit_logs`에 기록한다(`MODIFY_SPACE`). 가격 변경이 아니므로 `version`은 증가하지 않는다. 이전 이미지가 서버 관리 이미지인 경우 파일 시스템에서 정리한다(샘플 `/images/...`는 삭제하지 않음). 응답: 200, `data` = `SpaceDetailResponse`. 오류: `IMAGE_FILE_EMPTY`(400), `INVALID_IMAGE_FORMAT`(400), `IMAGE_DIMENSIONS_EXCEEDED`(400), `AUTHENTICATION_REQUIRED`(401), `ACCESS_DENIED`(403), `SPACE_NOT_FOUND`(404), `IMAGE_SIZE_EXCEEDED`(413), `IMAGE_STORAGE_ERROR`(500)
- `GET /space-images/{fileName}` (인증 불필요): 업로드된 공간 대표 사진 정적 스트리밍. 경로 탈출 방어, 올바른 MIME 반환(`image/jpeg`, `image/png`), `Cache-Control: public, max-age=86400`, `X-Content-Type-Options: nosniff` 헤더 제공. 응답: 바이너리 리소스. 오류: `IMAGE_NOT_FOUND`(404, JSON ApiResponse)

관리자 화면의 등록·교체·부분 실패 복구와 Spring MVC 파일 저장 흐름은 [관리자 공간 대표 사진 안내](admin-space-image-upload.md)의 다이어그램을 참고한다. 공간 이미지 OpenAPI 스키마 생성 및 Swagger UI HTML 리소스 서빙(`/swagger-ui/index.html`)은 `SpaceOpenApiDocumentationTest`(2개 테스트)로 검증되었으며(`/swagger-ui.html`은 진입 리다이렉트 경로), 파일 업로드/조회 흐름은 과거(2026-09-23) 실행된 이미지 관련 5개 테스트 클래스(XML 합계 34개)로 통과 기록이 확인되었다. 단, 브라우저를 통한 `Try it out` 수동 실사용은 아직 미검증 상태이다. 사진 PUT 실패 시 공간 저장은 유지되므로 동일 spaceId로 PUT만 재시도한다.

## 5. 예약 · 크레딧 (Reservation & Credit)

### 5-0. PG 크레딧 충전 주문 생성 — `POST /credits/charge-orders`

- 인증 및 회원 `ACTIVE` 상태가 필요하다. `ACTIVE`가 아니면 `ACCOUNT_INACTIVE`(403)로 거절한다. 요청 회원의 충전 시도 하나를 `charge_order`에 `PENDING`으로 저장하며, 이 단계에서는 PG 승인이나 크레딧 증가가 일어나지 않는다.
- 요청 바디: `{ "amount": 1000 }`
- `amount`: 1,000원 이상 100,000원 이하인 100원 단위 정수. 범위 또는 단위가 맞지 않으면 `VALIDATION_FAILED`(400).
- `orderId`: 서버가 `chg_` + 소문자 UUID v4 형식으로 생성한다. DB의 숫자 PK와 별도이며 UNIQUE 제약을 갖는다.
- 성공: 201, `data` = `{ "orderId": "chg_550e8400-e29b-41d4-a716-446655440000", "amount": 1000, "status": "PENDING" }`.

### 5-0-1. PG 크레딧 충전 주문 상태 조회 — `GET /credits/charge-orders/{orderId}`

- 인증이 필요하며 본인 주문만 조회할 수 있다.
- 성공: 200, `data` = `{ "orderId": "chg_550e8400-e29b-41d4-a716-446655440000", "amount": 1000, "status": "PENDING|CONFIRMING|PAID|FAILED" }`.
- 202 승인 응답을 받은 성공 페이지는 이 읽기 전용 API를 3초 간격으로 최대 2분 재조회한다. 그 뒤에도 `CONFIRMING`이면 오류로 바꾸지 않고 처리 중 안내와 수동 재조회 버튼을 유지한다.
- 오류: `AUTHENTICATION_REQUIRED`(401), `FORBIDDEN_NOT_OWNER`(403), `CHARGE_ORDER_NOT_FOUND`(404).

### 5-0-1-1. 내 충전 내역 조회 — `GET /credits/charge-orders?page=0&size=10`

인증 회원 본인의 충전 내역을 최신순으로 반환한다. 결제가 시도되지 않은 `PENDING` 주문은 제외하고 `PAID`·`CONFIRMING`·`FAILED`만 노출한다. 응답: **200**, `ApiResponse<PageResponse<{ orderId, amount, status, paymentMethod, paymentProvider, createdAt, updatedAt }>>` (`updatedAt`은 승인·실패 등 마지막 상태 변경 시각). `paymentMethod`(예: 카드·간편결제·계좌이체)와 `paymentProvider`(간편결제 제공사, 예: 토스페이)는 토스 승인 응답에서 저장한 표시용 값이며, 이 기능(V21) 이전 주문과 PG 조회 복구로 승인된 주문은 `null`이다. 결제수단 저장은 충전 확정과 별도 단계라 실패해도 충전은 유지된다. 오류: `AUTHENTICATION_REQUIRED`(401). 크레딧 사용·환불 내역은 포함하지 않는다.

### 5-0-2. PG 크레딧 충전 승인 — `POST /credits/charge-orders/confirm`

- 인증 및 `Idempotency-Key` 헤더가 필요하다.
- 요청 바디: `{ "paymentKey": "...", "orderId": "chg_...", "amount": 1000 }`.
- 처리 구조: DB 1에서 소유권·회원 `ACTIVE`를 먼저 확인하고 금액·상태·멱등키를 검증한 뒤 `PENDING → CONFIRMING` 조건부 전이 → 트랜잭션 밖 PG 승인 → DB 2에서 `CONFIRMING → PAID`, `TOP_UP` 원장 기록, 잔액 증가를 한 트랜잭션으로 처리한다.
- `Idempotency-Key`는 같은 회원의 충전 주문 하나에만 사용할 수 있다. 다른 주문에 재사용하면 PG 호출 전에 `IDEMPOTENCY_KEY_REUSED`(409)로 거절한다. 키는 시도 1회용이므로 주문이 `FAILED`가 되어도 해제하지 않으며, 새 결제 시도는 새 주문과 새 키를 사용한다.
- 성공 또는 같은 `paymentKey`의 이미 완료된 주문: 200, `data.status = "PAID"`.
- PG 결과를 알 수 없거나 이미 `CONFIRMING`인 주문: 202, `data.status = "CONFIRMING"`. PG를 다시 호출하지 않는다.
- 금액 불일치는 소유권과 회원 `ACTIVE` 검사를 통과한 요청에 한해 `CHARGE_AMOUNT_MISMATCH` 감사 로그를 남긴다. 이후 멱등키 재사용과 겹쳐도 기록하며, 단독이면 `PENDING`을 유지하고 `CHARGE_ORDER_AMOUNT_MISMATCH`(400)를 반환한다. 비소유 요청은 403으로 거절하고 피해자 주문 대상 감사 로그를 만들지 않는다.
- 승인 요청 시 회원이 `ACTIVE`가 아니면 `PENDING` 유지, PG 미호출, `ACCOUNT_INACTIVE`(403).
- PG 승인은 확인됐지만 DB 2가 실패하면 원인을 로그로 남긴다. 최종 주문이 `CONFIRMING`이면 202로 후속 복구를 기다리고, 다른 처리와 경합해 이미 `PAID`면 200을 반환하며, `FAILED` 등 그 밖의 상태면 `CHARGE_ORDER_STATE_CONFLICT`(409)를 반환한다. `200 + FAILED` 응답은 허용하지 않는다.
- 복구 스케줄러는 `CONFIRMING` 전이 후 1분 이상 24시간 미만인 주문을 저장된 `paymentKey`로 조회한다. 전송 실패·타임아웃·5xx·404로 Payment 객체를 받지 못하면 사실 없는 `UNKNOWN`으로 1분 뒤 재시도한다. Payment 객체를 받았으면 `paymentKey`·`orderId`·금액·상태·`currency=KRW`가 모두 맞을 때만 `PAID`/`FAILED`를 반영하고, 필수 필드 누락이나 불일치는 즉시 수동 처리 대상으로 표시한다. 정확히 24시간부터는 자동 조회를 중단한다. 수동 처리 표시와 PG 승인/로컬 `FAILED` 경합은 시스템 작업(`actorMemberId=null`)으로 `CHARGE_RECOVERY_MANUAL_REVIEW` 감사 로그를 남기며 기존 관리자 감사 로그 API에서 조회할 수 있다.
- 오류: `IDEMPOTENCY_KEY_REQUIRED`(400), `VALIDATION_FAILED`(400, 본문 필수 필드 누락, 그리고 충전 후 잔액이 `int` 상한을 넘는 경우), `CHARGE_ORDER_AMOUNT_MISMATCH`(400), `AUTHENTICATION_REQUIRED`(401), `FORBIDDEN_NOT_OWNER`(403), `ACCOUNT_INACTIVE`(403), `CHARGE_ORDER_NOT_FOUND`(404), `CHARGE_ORDER_STATE_CONFLICT`(409), `IDEMPOTENCY_KEY_REUSED`(409), `PAYMENT_DECLINED`(422), `PG_CONFIRMATION_ERROR`(500).

### 5-0-3. 토스페이먼츠 웹훅 수신 — `POST /webhooks/tosspayments`

- **인증 없음.** 토스페이먼츠 서버가 호출하는 주소라 JWT를 붙일 수 없어, `SecurityConfig`에서 이 경로의 `POST` 하나만 연다. 같은 경로의 다른 메서드와 하위 경로는 인증이 필요하다(401). 근거와 기각한 대안은 [`pg-charge.md` 5단계 설계 결정](../decisions/pg-charge.md) 결정 1.
- 대상 이벤트: `PAYMENT_STATUS_CHANGED`. 사용하는 헤더는 `tosspayments-webhook-transmission-id`(필수, 웹훅 고유 식별자)와 `tosspayments-webhook-transmission-retried-count`(선택, 숫자가 아니면 무시). 본문은 `{ eventType, createdAt, data }`이며 `data`는 Payment 객체다(출처: 토스페이먼츠 [웹훅 이벤트](https://docs.tosspayments.com/reference/using-api/webhook-events)). 이 이벤트에는 서명이 없으므로 발신자를 검증하지 않는다.
- **본문을 믿지 않는다.** 수신 경로는 PG를 호출하지 않고 `charge_order` 상태·원장·잔액을 바꾸지 않는다. `data.orderId`·`paymentKey`·`status`는 `pg_webhook_event`에 힌트로 기록만 한다. 본문의 `status=DONE`으로 충전이 확정되지 않는다.
- 응답(본문 없음):
  - **200** — `PAYMENT_STATUS_CHANGED`이고 `data.orderId`에 해당하는 `charge_order`가 있으면 `pg_webhook_event`에 `RECEIVED`로 한 행 기록한다. 같은 `transmission-id`가 다시 오면(순차·동시 모두) 기록하지 않고 200만 준다. 이미 `PAID`인 주문도 기록만 한다.
  - **200(기록 없음)** — 다른 이벤트 타입, `data.orderId` 누락·64자 초과, 없는 주문. 처리할 수 없는 알림이라 재전송을 유도하지 않는다.
  - **400** `VALIDATION_FAILED` — `tosspayments-webhook-transmission-id` 헤더 누락, 본문 없음, JSON 객체가 아닌 본문. 외부 재전송 정책은 토스 문서를 따르며, 서버 장애·타임아웃도 재전송 원인이 될 수 있다(기존 문서에 기록된 정책: 10초 안에 200이 아니면 최대 7회 재전송, 출처: [웹훅 연결하기](https://docs.tosspayments.com/guides/v2/webhook)).
- **처리 분리(응답과 무관):** 별도 스케줄러가 10초 간격으로 `RECEIVED` 행을 id 순서로 최대 50건 읽어 한 건씩 처리한다. 처리 결과는 HTTP 응답에 영향을 주지 않으며, 행을 `status=DONE`, `result`, `processed_at`으로 한 번만 갱신한다(재시도 없음). 근거는 [`pg-charge.md` 5단계 설계 결정](../decisions/pg-charge.md) 결정 4-1~4-4.
  | 주문 상태(처리 시점) | PG 조회 | 결과(`result`) | 주문·원장·잔액 |
  | --- | --- | --- | --- |
  | `PAID` | 호출 안 함 | `ALREADY_PAID` | 변경 없음 |
  | `FAILED` | 호출 안 함 | `ORDER_FAILED` | 변경 없음(재개방 안 함) |
  | `PENDING` | 호출 안 함 | `ORDER_PENDING` | 변경 없음(토스는 `EXPIRED`에도 이 이벤트를 보낸다) |
  | `CONFIRMING`, 저장된 `paymentKey`·클라이언트 멱등키 없음 | 호출 안 함 | `ORDER_INVARIANT_VIOLATION` | 변경 없음(복구 스케줄러가 수동 처리 표시) |
  | `CONFIRMING` | **저장된** `paymentKey`로 조회(트랜잭션 밖) | 승인 + `paymentKey`·`orderId`·금액·`currency=KRW`·상태 일치 → 주문 잠금 아래에서 아직 `PAID`가 아니면 `CONFIRMED`(§5-0-2의 기존 DB 2로 이 처리가 전이), 잠금을 얻었을 때 이미 같은 `paymentKey`로 `PAID`면 `ALREADY_PAID` | `CONFIRMED`일 때만 `PAID`·`TOP_UP` 1행·잔액 증가. `ALREADY_PAID`는 변경 없음 |
  | `CONFIRMING` | 〃 | 승인 아님(실패·모름) → `PG_NOT_APPROVED`, 불일치 → `PG_MISMATCH`, 조회 예외 → `PG_LOOKUP_ERROR` | 변경 없음. `FAILED` 전이·수동 처리 표시는 복구 스케줄러가 한다 |
  | DB 2가 상태 충돌 | — | 다시 읽어 `PAID`면 `ALREADY_PAID`, 아니면 `STATE_CONFLICT`(기존 `CHARGE_RECOVERY_MANUAL_REVIEW` 감사 로그) | 변경 없음 |
- 충전 1회: 같은 주문의 웹훅이 여러 번 오거나, 같은 행이 겹쳐 처리되거나, 승인 API와 동시에 DB 2에 들어와도 기존 DB 2의 주문 잠금·상태 검사·`credit_transaction.charge_order_id` UNIQUE로 `TOP_UP`은 한 번이다. 웹훅이 먼저 확정한 뒤 늦게 온 승인 요청은 200 `PAID`를 받는다.

> **프론트 예약 흐름 요약**: ① `POST /reservations`로 HOLD 생성(201) → 응답의 `reservationId`, `spaceVersion`, `holdExpiresAt` 보관 → ② 10분 안에 `POST /reservations/{id}/pay`(헤더 `Idempotency-Key`, 바디 `spaceVersion`)로 확정. 한 번의 호출로 결제까지 끝나지 않는다. 응답에 `payment` 객체는 없다.

#### 1) 공통 응답 DTO `ReservationResponse` (HOLD 생성 / 결제 확인 / 연장 / 체크아웃 / 취소 단건 응답)

```json
{
  "reservationId": 1,
  "spaceId": 3,
  "startTime": "2026-09-20T14:00:00",
  "endTime": "2026-09-20T15:00:00",
  "status": "HELD",
  "pricePerSlotSnapshot": 5000,
  "totalAmount": 10000,
  "holdExpiresAt": "2026-09-17T13:10:00",
  "checkedInAt": null,
  "checkedOutAt": null,
  "cancelledAt": null,
  "spaceVersion": 2,
  "refundAmount": null,
  "penaltyAmount": null,
  "createdAt": "2026-09-17T13:00:00"
}
```

- `spaceVersion`: **HOLD 생성 응답에서만** 정수값 반환(그 외 null). 클라이언트가 결제 요청(`POST .../pay`) 시 그대로 전달한다.
- `refundAmount`/`penaltyAmount`: **취소 응답에서만** 정수값 반환(그 외 null).
- 시간 필드는 오프셋 없는 `LocalDateTime` 문자열(서버 기준 KST).

#### 2) 내 예약 목록 DTO `ReservationListResponse` (`GET /reservations` 목록 원소)

```json
{
  "reservationId": 1,
  "spaceId": 3,
  "spaceName": "집중 회의실 A",
  "spaceLocation": "판교",
  "spaceImagePath": "/api/v1/space-images/uuid.jpg",
  "date": "2026-09-20",
  "startTime": "2026-09-20T14:00:00",
  "endTime": "2026-09-20T15:00:00",
  "status": "CONFIRMED",
  "pricePerSlotSnapshot": 5000,
  "totalAmount": 10000,
  "holdExpiresAt": null,
  "checkedInAt": null,
  "checkedOutAt": null,
  "cancelledAt": null,
  "createdAt": "2026-09-17T13:00:00"
}
```

- 목록 원소에는 공간 기본 정보(`spaceName`, `spaceLocation`, `spaceImagePath`) 및 `date`가 포함된다.
- `spaceVersion`, `refundAmount`, `penaltyAmount`는 포함되지 않는다.

#### 3) 내 예약 상세 DTO `ReservationDetailResponse` (`GET /reservations/{reservationId}` 응답)

```json
{
  "reservationId": 1,
  "spaceId": 3,
  "startTime": "2026-09-20T14:00:00",
  "endTime": "2026-09-20T15:00:00",
  "status": "CONFIRMED",
  "pricePerSlotSnapshot": 5000,
  "totalAmount": 10000,
  "holdExpiresAt": null,
  "checkedInAt": null,
  "checkedOutAt": null,
  "cancelledAt": null,
  "createdAt": "2026-09-17T13:00:00",
  "statusHistory": [
    {
      "fromStatus": null,
      "toStatus": "HELD",
      "reason": null,
      "changedAt": "2026-09-17T13:00:00"
    },
    {
      "fromStatus": "HELD",
      "toStatus": "CONFIRMED",
      "reason": null,
      "changedAt": "2026-09-17T13:02:00"
    }
  ]
}
```

- `ReservationResponse`의 기본 필드와 `statusHistory` 목록으로 구성된다.
- **주의**: `spaceVersion`, `refundAmount`, `penaltyAmount`는 포함되지 않는다. 또한 공간 정보(`spaceName`, `spaceLocation`, `spaceImagePath`)도 포함되지 않으므로, 프론트엔드는 공간 상세 조회(`GET /spaces/{spaceId}`)를 함께 호출하여 화면 모델을 보강한다.

### 5-1. 예약 생성(HOLD) — `POST /reservations`

요청 바디 (`ReservationCreateRequest`):

```json
{
  "spaceId": 3,
  "date": "2026-09-20",
  "startTime": "14:00:00",
  "endTime": "15:00:00"
}
```

> **프론트 연동 주의 (`termsVersion`)**: 프론트엔드(`SpaceDetailPage.tsx`, `enums.ts`의 `TERMS_VERSION`)는 현재 `{ spaceId, date, startTime, endTime, termsVersion: 'v1.1' }` 5개 필드를 전송하고 있으나, 백엔드 `ReservationCreateRequest` 및 엔티티/DDL에는 `termsVersion`이 정의되어 있지 않아 서버에서 수신·저장되지 않는다(Jackson 기본 설정에 의해 오류 없이 무시됨). 약관 버전의 서버 수신 및 DB 저장은 **결정 필요** 항목으로 관리한다.

응답: **201**, `ApiResponse<ReservationResponse>` (`status: "HELD"`, `spaceVersion: <number>` 포함).

`Idempotency-Key` 불필요(이 단계는 결제가 없음). 처리 순서:

1. 시간 및 요청 형식 검증: 30분 단위, 운영시간 내, 당일 내 종료 여부 검증 (위반 시 `INVALID_RESERVATION_TIME`(400) 또는 `VALIDATION_FAILED`(400))
2. 공간 상태 검증: 공간 존재 여부(`SPACE_NOT_FOUND`(404)) 및 ACTIVE 여부 확인 (`SPACE_INACTIVE`(422)). *(참고: `ReservationHoldService`는 회원을 잠금 조회해 존재하지 않으면 `AUTHENTICATION_REQUIRED`(401), `WITHDRAWN`이면 `ACCOUNT_WITHDRAWN`(401)으로 거절한다. `SUSPENDED`는 이 서비스에서 검사하지 않으며 인증 필터가 먼저 `INVALID_ACCESS_TOKEN`(401)으로 막는다. 탈퇴 회원도 필터가 먼저 막으므로 `ACCOUNT_WITHDRAWN`은 필터 통과 직후 탈퇴가 경합한 경우에만 나온다)*
3. 대상 슬롯의 만료된 HELD를 조건부 UPDATE로 EXPIRED 전이 + 슬롯 삭제(즉시 정리)
4. `price_per_slot × 슬롯 수`로 `totalAmount` 계산
5. 슬롯 유니크 INSERT 시도 → 충돌 시 `RESERVATION_SLOT_CONFLICT`(409)
6. `Reservation` 생성(`HELD`, `holdExpiresAt` = now + 10분) 및 상태 이력 저장. 응답에 `totalAmount`, `spaceVersion`, `holdExpiresAt` 반환

오류: `VALIDATION_FAILED`(400), `INVALID_RESERVATION_TIME`(400), `AUTHENTICATION_REQUIRED`(401), `ACCOUNT_WITHDRAWN`(401), `SPACE_NOT_FOUND`(404), `RESERVATION_SLOT_CONFLICT`(409), `SPACE_INACTIVE`(422)

### 5-2. 결제 확인 및 확정 — `POST /reservations/{id}/pay`

요청:
- 헤더: `Authorization: Bearer <token>`, `Idempotency-Key: <UUID>` (누락 시 400 `IDEMPOTENCY_KEY_REQUIRED`)
- 바디 (`ReservationPayRequest`): `{ "spaceVersion": 2 }`

응답: **200**, `ApiResponse<ReservationResponse>` (`status: "CONFIRMED"`).

헤더 `Idempotency-Key` 필수(돈이 움직이는 지점). 멱등성 캐시 확인 후, 아래 전 과정을 **하나의 트랜잭션**으로 처리하며 어느 단계에서든 실패하면 전체 롤백한다(예약은 `HOLD`로 남아 만료 전까지 재시도 가능):

1. **Idempotency-Key 캐시 확인**: 이전에 동일 키와 사용자 ID, 경로로 처리된 성공 응답(200 OK)이 있으면 즉시 반환.
2. **소유권 선검증**: 잠금 순서(`Space → Reservation`) 준수를 위해 `reservationId`로부터 `spaceId`와 `memberId`를 먼저 투영 조회하여 본인 예약 여부를 검증 (`FORBIDDEN_NOT_OWNER`(403)). 비소유자의 불필요한 락 획득을 차단.
3. **Space 공유 잠금 및 버전 검증**: `spaceId`로 Space 공유 잠금(`findByIdForShare`)을 획득하고, 요청의 `spaceVersion`과 비교. 다르면 즉시 `SPACE_VERSION_MISMATCH`(409)로 거절.
4. **Reservation 조회**: 일반 조회(`findById`)로 예약 정보와 결제 금액 확인 (`RESERVATION_NOT_FOUND`(404)).
5. **크레딧 차감**: `creditService.charge()`로 크레딧 차감(잔액 부족 시 `INSUFFICIENT_BALANCE`(422)).
6. **조건부 상태 전이**: `confirmIfHeldAndNotExpired(reservationId, now, HELD, CONFIRMED)` 조건부 UPDATE로 유효한 HOLD에 한해 `CONFIRMED` 전이. 영향 행 0이면 `RESERVATION_STATE_CONFLICT`(409, HOLD 만료 또는 이미 확정/취소됨)를 던져 크레딧 차감을 포함한 트랜잭션 전체를 롤백.
7. 상태 이력 저장 및 멱등성 성공 응답(200) 캐시 저장.

오류: `IDEMPOTENCY_KEY_REQUIRED`(400), `VALIDATION_FAILED`(400), `AUTHENTICATION_REQUIRED`(401), `FORBIDDEN_NOT_OWNER`(403), `RESERVATION_NOT_FOUND`(404), `SPACE_NOT_FOUND`(404), `SPACE_VERSION_MISMATCH`(409), `RESERVATION_STATE_CONFLICT`(409), `INSUFFICIENT_BALANCE`(422)

> 💡 **오류 코드 참고**: HOLD 만료로 결제가 거절될 때 반환되는 에러 코드는 `RESERVATION_HOLD_EXPIRED`가 아니라 `RESERVATION_STATE_CONFLICT`(409)이다. 프론트엔드는 이 코드를 받아 만료 안내를 표시한다.

### 5-3. 조회

- `GET /reservations?status=&keyword=&page=&size=` (인증 필요, 본인 예약만). 응답 `ApiResponse<PageResponse<ReservationListResponse>>`. 반환 `data`는 `content`, `page`, `size`, `totalElements`, `totalPages`를 포함한다. `keyword`는 공간 이름(`Space.name`) 부분 일치 검색(공백 trim, 빈 문자열은 null 간주)을 지원하며, 비활성(`INACTIVE`) 공간의 기존 예약도 포함한다. 정렬은 `startTime DESC, id DESC`가 기본 적용된다. 잘못된 `status` enum은 400 Bad Request(`VALIDATION_FAILED`)로 거절된다. `status` 생략 시 전체 상태 조회. 원소는 `ReservationListResponse` (공간명, 사진, 위치 포함).
- `GET /reservations/{reservationId}` (인증 필요, 본인 예약만). 응답 `ApiResponse<ReservationDetailResponse>`. 상태 이력(`statusHistory`)이 포함되며, 공간명 등 상세는 프론트가 공간 API를 통해 병합 표시. 오류: `AUTHENTICATION_REQUIRED`(401), `FORBIDDEN_NOT_OWNER`(403), `RESERVATION_NOT_FOUND`(404)

### 5-4. 취소 — `POST /reservations/{reservationId}/cancel`

> ⚠️ `DELETE /reservations/{id}`가 아니다(2026-09-17 정정). 바디 없음. 응답: **200**, `ApiResponse<ReservationResponse>` (`status: "CANCELLED"`, `refundAmount`, `penaltyAmount` 포함).

**결제 대기(`HELD`) 예약도 취소할 수 있다.** 조건부 UPDATE `WHERE id=:id AND status='HELD'` → `CANCELLED`, 슬롯 삭제 + 상태 이력 + `SlotReleased` outbox 기록(`CANCELLED`). 크레딧이 차감되기 전이므로 환불·위약금은 0이다(`refundAmount`=0, `penaltyAmount`=0). 결제와 같은 행을 두고 경합하며 영향 행 0이면 409.

아래는 `CONFIRMED` 예약의 일반 취소 정책이다. 예약자 본인만 가능하며 시작 1시간 전까지 100% 환불, 1시간 전~시작 전 50% 환불, 시작 이후는 취소 불가(체크아웃으로만 종료).

1. 조건부 UPDATE: `WHERE id=:id AND status='CONFIRMED' AND :now < start_time` → `CANCELLED`(`cancelled_at`=now). 영향 행 0이면 409
2. (같은 트랜잭션) 슬롯 삭제 + 활성 토큰 revoke + 크레딧 환급(`REFUND` 전액, 필요시 `PENALTY` 위약금을 **두 줄로 분리 기록**) + 상태 이력 저장

오류: `AUTHENTICATION_REQUIRED`(401), `ACCOUNT_WITHDRAWN`(401, §5-1 참고), `FORBIDDEN_NOT_OWNER`(403), `RESERVATION_NOT_FOUND`(404), `RESERVATION_STATE_CONFLICT`(409)

> 취소와 환불이 같은 DB의 한 트랜잭션이므로 "취소는 됐는데 환불은 실패"하는 중간 상태가 구조적으로 없다. 별도의 환불 재처리 큐는 필요 없다.

### 5-5. 연장 — `POST /reservations/{id}/extend`

요청 바디: `{ "expectedEndTime": "2026-09-20T15:00:00", "newEndTime": "2026-09-20T16:00:00" }` (`expectedEndTime` = 클라이언트가 알고 있는 현재 종료 시각). 응답: **200**, `ApiResponse<ReservationResponse>`.

예약자 본인만. `now < end_time`인 경우만 가능(끝난 예약을 되살리는 것은 연장이 아니라 새 예약). 단일 트랜잭션 처리:
1. **spaceId 투영 조회**: 잠금 순서(`Space → Reservation`) 준수를 위해 `reservationId`로부터 `spaceId`를 먼저 투영 조회.
2. **Space 공유 잠금**: `spaceRepository.findByIdForShare(spaceId)`로 Space 공유 잠금 획득.
3. **Reservation 배타 잠금**: `reservationRepository.findByIdForUpdate(reservationId)`로 비관적 배타 잠금 획득.
4. **검증**: 소유권(`memberId`), 예약 상태(`CONFIRMED` 또는 `IN_USE`), 아직 종료되지 않음(`now < endTime`), `expectedEndTime` 일치 여부 확인. 운영시간 내, 30분 단위, 동일 날짜 등 연장 시간 정책 검증.
5. **슬롯 확보 및 크레딧 차감**: 추가 슬롯 INSERT(`secureSlots`, 충돌 시 409). 원 예약의 `price_per_slot_snapshot` 기준으로 추가 금액 크레딧 차감.
6. **조건부 UPDATE**: `extendIfEndTimeMatches`로 종료 시각과 총액 갱신. 영향 행 0이면 409 롤백.

오류: `VALIDATION_FAILED`(400), `INVALID_RESERVATION_TIME`(400), `AUTHENTICATION_REQUIRED`(401), `ACCOUNT_WITHDRAWN`(401, §5-1 참고), `FORBIDDEN_NOT_OWNER`(403), `RESERVATION_NOT_FOUND`(404), `SPACE_NOT_FOUND`(404), `RESERVATION_SLOT_CONFLICT`(409), `RESERVATION_STATE_CONFLICT`(409), `RESERVATION_EXTEND_NOT_ALLOWED`(422), `INSUFFICIENT_BALANCE`(422)

> 실패해도 원 예약은 무손상(기존 슬롯을 건드리지 않음).

### 5-6. 체크아웃 — `POST /reservations/{id}/check-out`

바디 없음. 응답: **200**, `ApiResponse<ReservationResponse>` (`status: "COMPLETED"`).

예약자 본인만. `WHERE id=:id AND status='IN_USE'` 조건부 UPDATE → `COMPLETED`(`checked_out_at`=now). 같은 트랜잭션에서 활성 토큰 revoke + 상태 이력 저장. 슬롯 반환 없음, 환불 없음. **되돌릴 수 없다**(UI에 확인 다이얼로그).

> 체크인에는 별도 엔드포인트가 없다 — 최초 체크인은 §7 도어 토큰 검증의 부수 효과로 `CONFIRMED → IN_USE` 전이가 일어난다.

오류: `AUTHENTICATION_REQUIRED`(401), `FORBIDDEN_NOT_OWNER`(403), `RESERVATION_NOT_FOUND`(404), `RESERVATION_STATE_CONFLICT`(409)

## 6. [관리자] 예약 관리

- `GET /admin/reservations?page=&size=&date=&spaceId=&status=` (ADMIN): 취소 예약도 목록 포함. 응답 `ApiResponse<PageResponse<AdminReservationResponse>>` (공통 `PageResponse` 형태. 필드: `page`, `size`, `totalElements`, `totalPages`, `content[]` 원소: `{ reservationId, memberId, memberEmail, spaceId, spaceName, startTime, endTime, status, totalAmount, createdAt }`). `date`(YYYY-MM-DD 형식, 해당 날짜 [00:00:00, 다음날 00:00:00) 범위 검색), `spaceId`(양수), `status` 필터 쿼리 파라미터를 지원하며, 잘못된 형식(유효하지 않은 날짜, 음수 spaceId, 알 수 없는 enum 등)은 400 `VALIDATION_FAILED`로 거절된다. 존재하지 않는 양수 spaceId는 빈 목록(200 OK)을 반환한다.
- `GET /admin/reservations/{reservationId}` (ADMIN): 관리자 예약 상세 조회. 응답 `ApiResponse<AdminReservationDetailResponse>` (목록 필드 + `pricePerSlotSnapshot`, `checkedInAt`, `checkedOutAt`, `cancelledAt`, `statusHistory[]`, `accessLogs[]`). 오류: `AUTHENTICATION_REQUIRED`(401), `ACCESS_DENIED`(403), `RESERVATION_NOT_FOUND`(404)
- `POST /admin/reservations/{reservationId}/force-cancel` (ADMIN): 바디 `{ "reason": "..." }` 1~500자 필수, 응답 `ApiResponse<AdminReservationResponse>`. 취소 가능 상태는 `HELD`/`CONFIRMED`/`IN_USE`(그 외 종료 상태는 409). 조회 시점 상태 기준 조건부 UPDATE(`WHERE id=:id AND status=:조회 시점 상태`) → 같은 트랜잭션에서 상태 이력(사유 포함) + 슬롯 삭제 + 활성 토큰 revoke + **크레딧 환불(`CONFIRMED`/`IN_USE`는 `total_amount` 전액 `REFUND`, 위약금 없음 / `HELD`는 환불 없음)** + audit_log 기록. 오류: `VALIDATION_FAILED`(400), `AUTHENTICATION_REQUIRED`(401), `ACCESS_DENIED`(403), `RESERVATION_NOT_FOUND`(404), `SPACE_NOT_FOUND`(404, `HELD` 예약의 공간 잠금 조회 실패), `RESERVATION_STATE_CONFLICT`(409). **관리자도 이 API 외의 경로로 타인 예약을 취소하거나 도어 토큰을 발급받을 수 없다** (핵심 차별점)

## 7. 출입 (Door Access)

> 현재 구현 경로(접두사 `/api/v1`): `POST /reservations/{id}/door-token`, `PATCH /reservations/{id}/access-token/revoke`, `GET /reservations/{id}/access-logs`, `POST /door-access/verify`. **4개 모두 로그인 필요**(verify도 서버가 로그인 회원이 예약자 본인인지 확인).

### 인증 단계의 비활성 회원 처리

- 4개 API 모두 JWT·DB 세션·회원 상태 검증을 먼저 거친다. 비활성 회원은 HTTP 401 / `INVALID_ACCESS_TOKEN`으로 차단되며 출입 서비스까지 도달하지 않는다.
- 이 중 `POST /api/v1/door-access/verify`에 한해서 `door_access_log`에 `DENY / MEMBER_INACTIVE`를 기록한다. `MEMBER_INACTIVE`는 DB의 거절 사유이며 클라이언트 응답 코드가 아니다. 이 경우 일반 출입 검증 결과인 HTTP 200 / `result: DENY`를 반환하지 않는다.
- 기록 조건: JWT 서명·만료 검증 통과, JWT 회원과 DB 세션 소유자 일치, DB 세션 만료 전, 회원 상태가 `ACTIVE`가 아님. 정지 시 세션도 폐기되므로 비활성 상태 조회에서는 `revokedAt`을 제한하지 않는다. 이는 세션 사용을 허용하는 것이 아니다.
- 회원, 시도 시각, 결과 및 거절 사유를 저장한다. 요청 본문을 해석하지 않으므로 예약·공간은 null이며 JWT·출입키 원문은 저장하지 않는다. 예약 연결이 없어 예약별 출입 로그 조회에는 포함되지 않는다.
- 별도 트랜잭션(`REQUIRES_NEW`)으로 저장하며, 저장 실패 시 서버 오류 로그를 남기고 인증 차단은 유지한다. 저장 실패에 대한 재시도·저장 보장은 제공하지 않는다.
- 출입키 발급·일반 API 요청, JWT 검증 실패, 회원을 특정할 수 없거나 만료된 세션은 이 비활성 회원 출입 로그 대상에서 제외한다.

### 인증 통과 후 출입 API

- `POST /reservations/{reservationId}/door-token` (예약자 본인만): **발급에는 시간 제한이 없다** — `CONFIRMED` 또는 `IN_USE`이고 `now < end_time`이면 예약 확정 직후부터 언제든 발급 가능(발급은 입장 권한이 아니라 신분증을 받는 것일 뿐, `start` 전엔 문이 열리지 않는다). 기존 활성 토큰은 먼저 폐기 후 재발급. 응답: 201 `ApiResponse<DoorAccessTokenResponse>` (`reservationId`, `token`, `issuedAt`). 오류: `AUTHENTICATION_REQUIRED`(401), `FORBIDDEN_NOT_OWNER`(403), `RESERVATION_NOT_FOUND`(404), `RESERVATION_STATE_CONFLICT`(409 — 예약이 `CONFIRMED`·`IN_USE`가 아니거나 이미 종료됐거나 동시 발급 경합으로 활성 토큰 UNIQUE가 충돌한 경우)
- `PATCH /reservations/{reservationId}/access-token/revoke` (예약자 본인만): 해당 예약에 발급된 활성 출입 토큰을 즉시 폐기. 응답: 200 `ApiResponse<Void>` (Java 상으로는 `data: null`이나, `@JsonInclude(NON_NULL)`로 인해 실제 HTTP JSON 직렬화 시 `"data"` 필드가 생략되어 `{ "status": "SUCCESS", "code": "OK", "message": "요청이 성공적으로 처리되었습니다." }` 형태로 반환됨). 오류: `AUTHENTICATION_REQUIRED`(401), `FORBIDDEN_NOT_OWNER`(403), `RESERVATION_NOT_FOUND`(404), `ACTIVE_ACCESS_TOKEN_NOT_FOUND`(404)
- `GET /reservations/{reservationId}/access-logs` (예약자 본인만): 해당 예약의 출입 기록 목록 조회. 응답: 200 `ApiResponse<List<DoorAccessLogResponse>>` (`accessLogId`, `attemptedAt`, `requestedSpaceName`, `result`, `reasonCode`). 오류: `AUTHENTICATION_REQUIRED`(401), `FORBIDDEN_NOT_OWNER`(403), `RESERVATION_NOT_FOUND`(404)
- `POST /door-access/verify` (인증 필요): 요청 바디 `{ spaceId, token }`. 응답: 200 `ApiResponse<DoorAccessVerifyResponse>` (`result: "ALLOW" | "DENY"`, `reasonCode`, `spaceName`, `firstCheckIn`, `attemptedAt`). 검증 순서:
  1. 해시로 활성 토큰 조회 (미존재 또는 폐기 토큰은 `TOKEN_NOT_FOUND` / `TOKEN_REVOKED`로 거절)
  2. 예약 상태(`CONFIRMED` 또는 `IN_USE`) 확인 (`RESERVATION_NOT_ACTIVE`), 요청 공간과 예약 공간 일치 확인 (`SPACE_MISMATCH`), 로그인 사용자와 예약자 일치 확인 (`MEMBER_MISMATCH`)
  3. 시간대 확인:
     - **최초 체크인**: `[start_time, start_time + 15분]` (시작 정각 허용, 15분 초과 시 거절)
     - **재입장**: `(checked_in_at, end_time)` (종료 정각은 거절)
     - 허용 시간 외 시 `OUTSIDE_ALLOWED_TIME`으로 거절
  4. 최초 체크인 성공 시 `CONFIRMED → IN_USE` 전이가 부수 효과로 발생
  5. 인증을 통과한 출입 서비스의 성공 및 거절은 `door_access_log`에 기록되며, 거절 시에도 HTTP 200과 함께 `result: "DENY"`로 반환. 인증 단계에서 차단되는 비활성 회원은 위 정책을 따른다.
  - 오류: `VALIDATION_FAILED`(400), `AUTHENTICATION_REQUIRED`(401)

> **2026-09-15 정정** (`core-domain-decisions.md` §8): 기존 "발급 30분 전부터, 최초 체크인 시작 전후 30분" 규칙을 대체한다. 발급 시간 제한을 없애고, 최초 체크인은 앞 여유 0분·뒤 15분(`[start, start+15m]`)으로 확정. 15분 내 미체크인은 배치가 `NO_SHOW`로 전이시키며 슬롯은 반환되지만 환불은 없다.

## 8. 관리자 감사 로그 (Admin Audit Log)

- `GET /api/v1/admin/audit-logs` (ADMIN): 관리자 감사 로그 목록 조회.
  - 쿼리 파라미터(필터):
    - `actorMemberId` (Long): 작업자 ID
    - `action` (AuditAction): `REGISTER_SPACE`, `MODIFY_SPACE`, `SUSPEND_MEMBER`, `REACTIVATE_MEMBER`, `FORCE_CANCEL_RESERVATION`, `GRANT_CREDIT`, `CHARGE_AMOUNT_MISMATCH`, `CHARGE_RECOVERY_MANUAL_REVIEW`
    - `targetType` (AuditTargetType): `SPACE`, `MEMBER`, `RESERVATION`, `CHARGE_ORDER`
    - `targetId` (Long): 대상 엔티티 ID
    - `dateFrom` (LocalDate, ISO-8601): 조회 시작일 (KST 00:00:00 이상)
    - `dateTo` (LocalDate, ISO-8601): 조회 종료일 (KST 다음 날 00:00:00 미만, 반개구간)
    - `page` (int, default 0), `size` (int, default 20)
  - 정렬: 클라이언트 sort는 무시하고 서버 고정 `createdAt DESC, id DESC` 적용.
  - 응답: `ApiResponse<PageResponse<AuditLogResponse>>` (`id`, `actorMemberId`, `action`, `targetType`, `targetId`, `reason`, `beforeValue`, `afterValue`, `createdAt`)
  - 오류: `VALIDATION_FAILED`(400) (날짜 역전 `dateFrom > dateTo` 또는 파라미터 타입 오류), `AUTHENTICATION_REQUIRED`(401), `ACCESS_DENIED`(403)
  - 부수효과: 조회 자체는 감사 로그를 남기지 않음.

## 9. 문의 (Inquiry, Q&A) — MVP 3대 기능 외 추가 기능

> 문의 1건당 답변 1건(1:1)으로 단순화한다. 재질문/스레드형 재답변은 범위 밖.

### 9-1. 회원용

- `POST /inquiries` (인증 필요): 문의 생성. 요청 `{ title, content }` (title 최대 200자, content 최대 2000자, 필수). 응답: 201 `ApiResponse<InquiryResponse>`. 오류: `VALIDATION_FAILED`(400), `AUTHENTICATION_REQUIRED`(401)
- `GET /inquiries` (인증 필요): 본인 문의 목록. `page`, `size`. 응답: 200 `ApiResponse<PageResponse<InquiryResponse>>`. 오류: `AUTHENTICATION_REQUIRED`(401)
- `GET /inquiries/{inquiryId}` (인증 필요): 본인 문의 상세. 응답: 200 `ApiResponse<InquiryResponse>`. 오류: `AUTHENTICATION_REQUIRED`(401), `FORBIDDEN_NOT_OWNER`(403), `INQUIRY_NOT_FOUND`(404)
- `PATCH /inquiries/{inquiryId}` (인증 필요): 본인 문의 수정. `status`가 `WAITING`일 때만 가능. 요청 바디는 생성과 동일. 응답: 200 `ApiResponse<InquiryResponse>`. 오류: `VALIDATION_FAILED`(400), `AUTHENTICATION_REQUIRED`(401), `FORBIDDEN_NOT_OWNER`(403), `INQUIRY_NOT_FOUND`(404), `INQUIRY_ALREADY_ANSWERED`(422)

### 9-2. 관리자용

- `GET /admin/inquiries` (ADMIN): 전체 문의 목록. 쿼리 파라미터 `status` (`WAITING` / `ANSWERED`, 선택), `page`, `size`. 응답: 200 `ApiResponse<PageResponse<InquiryResponse>>`. 오류: `AUTHENTICATION_REQUIRED`(401), `ACCESS_DENIED`(403)
- `GET /admin/inquiries/{inquiryId}` (ADMIN): 문의 상세. 응답: 200 `ApiResponse<InquiryResponse>`. 오류: `AUTHENTICATION_REQUIRED`(401), `ACCESS_DENIED`(403), `INQUIRY_NOT_FOUND`(404)
- `POST /admin/inquiries/{inquiryId}/answer` (ADMIN): 답변 등록. 요청 `{ content }` (최대 2000자, 필수). 성공 시 `status`가 `ANSWERED`로 전이. 응답: 200 `ApiResponse<InquiryResponse>`. 오류: `VALIDATION_FAILED`(400), `AUTHENTICATION_REQUIRED`(401), `ACCESS_DENIED`(403), `INQUIRY_NOT_FOUND`(404)

### 응답 필드 (`InquiryResponse`)

`id`, `memberId`, `title`, `content`, `status`, `answerContent`, `answeredByMemberId`, `answeredAt`, `createdAt`

## 10. Enum 정의표

| 항목 | 값 |
| --- | --- |
| `member.role` | `USER`, `ADMIN` |
| `member.status` | `ACTIVE`, `SUSPENDED`, `WITHDRAWN` |
| `space.status` | `ACTIVE`, `INACTIVE` |
| `reservation.status` | `HELD`, `EXPIRED`, `CONFIRMED`, `IN_USE`, `COMPLETED`, `CANCELLED`, `NO_SHOW` |
| `credit_transaction.type` | `SIGNUP_GRANT`, `ADMIN_GRANT`, `RESERVATION_CHARGE`, `REFUND`, `PENALTY`, `WITHDRAWAL_EXPIRATION`, `TOP_UP` |
| `door_access_log.result` | `ALLOW`, `DENY` |
| `door_access_log.reason_code` | `TOKEN_NOT_FOUND`, `TOKEN_REVOKED`, `RESERVATION_NOT_ACTIVE`, `OUTSIDE_ALLOWED_TIME`, `SPACE_MISMATCH`, `MEMBER_MISMATCH`, `MEMBER_INACTIVE` (인증 단계 전용, §7) |
| `charge_order.status` | `PENDING`, `CONFIRMING`, `PAID`, `FAILED` |
| `pg_webhook_event.status` | `RECEIVED`, `DONE` (문자열 저장) |
| `pg_webhook_event.result` | `CONFIRMED`, `ALREADY_PAID`, `ORDER_PENDING`, `ORDER_FAILED`, `ORDER_NOT_FOUND`, `ORDER_INVARIANT_VIOLATION`, `PG_NOT_APPROVED`, `PG_MISMATCH`, `PG_LOOKUP_ERROR`, `STATE_CONFLICT`, `COMPLETE_ERROR` |
| 랭킹 `period` | `DAILY`, `WEEKLY`, `MONTHLY` |
| 랭킹 `metric` | `NET_REVENUE`, `RESERVATION_COUNT`, `SLOT_COUNT` |
| 랭킹 응답 `status` | `CONFIRMED`, `PENDING`, `UNAVAILABLE` |
| `audit_log.action` | `REGISTER_SPACE`, `MODIFY_SPACE`, `SUSPEND_MEMBER`, `REACTIVATE_MEMBER`, `LOCK_MEMBER_BY_VERIFICATION`(이메일 인증 3차 차단에 따른 시스템 정지), `FORCE_CANCEL_RESERVATION`, `GRANT_CREDIT`, `CHARGE_AMOUNT_MISMATCH`, `CHARGE_RECOVERY_MANUAL_REVIEW` |
| `verification_guard.purpose` (요청 `purpose`) | `SIGNUP`, `PASSWORD_RESET` |
| `audit_log.target_type` | `SPACE`, `MEMBER`, `RESERVATION`, `CHARGE_ORDER` |
| `inquiry.status` | `WAITING`, `ANSWERED` |
| `waitlist_entry.status` | `WAITING`, `CANCELLED`, `EXPIRED` (`EXPIRED` 전이는 미구현 — 구간이 지난 `WAITING` 행은 "활성" 기준(시작 시각이 미래)에서 빠질 뿐 상태는 그대로다) |
| `outbox_event.status` | `PENDING`, `PUBLISHED` |
| `SlotReleased.reason` (Kafka payload) | `CANCELLED`, `FORCE_CANCELLED`, `HOLD_EXPIRED` |

> ~~`payment.status`~~ 는 `payment` 테이블 삭제와 함께 제거됨. 결제 결과는 `credit_transaction`으로 표현한다.

## 11. 결정 필요 사항 (Pending Decisions)

코드 구현과 요구사항/문서 사이에 차이가 있어 제품 및 설계 결정이 필요한 항목들이다. 현재 구현과 목표 계약을 구분하여 관리한다:

1. **약관 동의 버전 (`termsVersion`) 수신 및 저장 여부**
   - **현재 구현**: 프론트엔드(`SpaceDetailPage.tsx`, `enums.ts`의 `TERMS_VERSION`)는 예약 생성 시 `termsVersion: 'v1.1'`(문자열)을 전송하고 있으나, 백엔드 DTO `ReservationCreateRequest` 및 DDL에는 필드가 없어 서버에서 역직렬화 시 버려지며 저장되지 않는다.
   - **결정 과제**: 예약 체결 시점의 이용약관 동의 증적을 위해 백엔드 DTO, Entity, DDL(`reservation.terms_version`)에 반영할 것인지, 아니면 프론트엔드의 전송을 제거하고 단순 클라이언트 UI 동의 체크로 만족할 것인지 결정 필요.
2. **멱등성 키 충돌 감지 및 실패 응답 캐싱/TTL 정책**
   - **현재 구현**: `POST /reservations/{id}/pay`에서 성공 응답(200 OK)만 `idempotency_key` 테이블에 저장되며, 실패 시에는 롤백되어 캐시되지 않는다. 또한 동일 키에 대해 다른 요청 본문이 전달되어도 충돌을 감지하는 로직(`IDEMPOTENCY_KEY_CONFLICT`)이 없다.
   - **결정 과제**: 동일 키에 다른 페이로드가 전달되었을 때 409 충돌을 발생시킬 것인지(요청 바디 해시 저장 필요), 실패 응답도 캐시하여 무조건 동일 결과를 재현할 것인지, 만료 기간(TTL) 및 테이블 정리 스케줄러를 도입할 것인지 결정 필요.
3. **정지·로그아웃·비밀번호 재설정·탈퇴 후 기존 인증 수단 즉시 차단 (구현 반영)**
   - **현재 구현**: PR #350 이후 `CustomAuthenticationFilter`는 JWT 검증 뒤 DB 세션과 회원 상태를 검증한다. 세션 폐기·만료 또는 비활성 회원의 기존 Access Token은 `INVALID_ACCESS_TOKEN`(401)으로 차단된다. 비활성 회원 출입 시도 로그의 추가 조건과 저장 범위는 §7을 따른다.
   - **목표**: 차단 여부는 더 이상 결정 대상이 아니다. [인증 정책](../decisions/authentication-policy.md) §10에 따라 V2에서 Redis 없이 DB 기반 로그인 세션으로 기존 AT·RT를 폐기한다. 세션 식별자·RT 재사용 시 폐기 범위·역할 변경 반영 방식 등 세부는 [V2 계획](../plans/auth/v2-plan.md) §3 결정표에서 정한다. 대상 시나리오는 `V2SecurityRedTest` 11개이며 [인증 보안 테스트](../testing/auth-security-tests.md)를 본다.
   - **이력 주의**: §2-5 및 문서 상단의 병합 전 설명은 당시 진행 기록이다. 현재 동작은 위 구현 설명을 기준으로 하며, 전체 인증 고도화 문서의 이력 정리는 별도 범위다.
4. **관리자 예약 목록 검색 조건 필터 지원 (해결 완료)**
   - **구현 현황**: `AdminReservationSearchCondition`(`date`, `spaceId`, `status`) 파라미터 바인딩 및 `ReservationRepository.searchReservations` 동적 JPQL 필터링 구현 완료. `PageResponse<AdminReservationResponse>` 규격 정합화 완료.
5. **노쇼 환불률**: 현재 확정값은 0%이며 강사 피드백에 따라 재검토 중(`docs/decisions/core-domain-decisions.md` §13). 변경 시 명세서와 크레딧 환급 로직 동기화 필요.
6. **HOLD 카운트다운 시간대 해석 차이 (프론트 별도 코드 과제)**
   - **현재 구현**: 백엔드는 오프셋 없는 ISO-8601 문자열(`yyyy-MM-dd'T'HH:mm:ss`)을 KST 기준으로 반환하고 클라이언트의 KST 해석을 기대하나, 프론트엔드 `HoldCountdown.tsx`는 `new Date(holdExpiresAt)`를 사용하여 **브라우저 로컬(현지) 시간대**로 파싱한다. KST가 아닌 브라우저 환경(예: UTC)에서는 만료 시점 계산에 9시간 등의 시간차가 발생할 수 있다.
   - **결정 과제**: 프론트엔드 `HoldCountdown.tsx`에서 타임존 오프셋(`+09:00`)을 명시적으로 결합하여 KST로 고정 파싱하도록 하는 수정은 문서 최신화 범위 밖의 **별도 프론트엔드 코드 과제**로 분리하여 추적한다.
7. **알림 일괄 읽음 처리 구현**
   - **현재 구현**: `POST /notifications/read-all`로 본인의 안 읽은 알림을 일괄 읽음 처리한다. 개별 읽음은 제공하지 않는다.
   - **재검토 조건**: 개별 읽음이나 SSE·메일 전달 요구가 생기면 별도 설계한다.

## 12. 예약 대기 (Waitlist)

> 점유 중인 구간에 "자리가 비면 알려 달라"고 거는 기록이다. **예약 성사를 보장하지 않는다** — 슬롯이 풀리면 조건에 맞는 대기자 전원에게 알림을 보내고, 예약은 기존 `POST /reservations`의 슬롯 UNIQUE로 선착순 처리된다. 규칙 상세는 [plans/waitlist-outbox/01-waitlist-rules.md](../plans/waitlist-outbox/01-waitlist-rules.md). 모든 API는 인증이 필요하다.

- `POST /waitlist`: 대기 신청. 요청 `{ spaceId, startTime, endTime }`(`startTime`/`endTime`은 오프셋 없는 `yyyy-MM-dd'T'HH:mm:ss`, 기존 예약과 같은 시간 규칙). 응답 201 `ApiResponse<WaitlistEntryResponse>`.
  - 검사 순서: 공간 → 구간 → 회원 상태 → 점유 여부 → 본인 점유 → 활성 건수 → 중복. 구간의 슬롯이 **하나라도 점유 중일 때만** 신청할 수 있다.
  - 오류: `VALIDATION_FAILED`(400), `INVALID_RESERVATION_TIME`(400), `AUTHENTICATION_REQUIRED`(401), `ACCOUNT_WITHDRAWN`(401), `ACCOUNT_INACTIVE`(403), `SPACE_NOT_FOUND`(404), `WAITLIST_DUPLICATE`(409), `SPACE_INACTIVE`(422), `WAITLIST_NOT_NEEDED`(422, 구간이 전부 비어 있음), `WAITLIST_ALREADY_HOLDING`(422, 같은 공간·겹치는 시간에 본인의 만료 전 `HELD`·`CONFIRMED`·`IN_USE` 예약이 있음), `WAITLIST_LIMIT_EXCEEDED`(422, 활성 대기 3건 초과)
- `DELETE /waitlist/{waitlistEntryId}`: 본인 대기 취소. 응답 204(본문 없음). 이미 취소된 건에 다시 요청해도 204(멱등). 관리자도 타인의 대기는 취소할 수 없다. 오류: `AUTHENTICATION_REQUIRED`(401), `FORBIDDEN_NOT_OWNER`(403), `WAITLIST_NOT_FOUND`(404)
- `GET /waitlist/me`: 내 활성 대기 목록(신청 순서대로). 응답 200 `ApiResponse<List<WaitlistEntryResponse>>`(페이지네이션 없음). 오류: `AUTHENTICATION_REQUIRED`(401)

"활성 대기"의 기준은 신청 제한(3건)·신청 순서·내 목록 모두 `status = WAITING AND startTime > now`로 같다. 취소하면 같은 구간을 다시 신청할 수 있다(새 행이므로 순서는 맨 뒤).

### 응답 필드 (`WaitlistEntryResponse`)

`id`, `spaceId`, `startTime`, `endTime`, `status`, `position`, `createdAt`. `position`은 "같은 공간에서 구간이 겹치는 활성 대기 중 나보다 먼저 신청한 건수 + 1"이며 안내용이다. 구간이 부분만 겹치면 사람마다 기준 집합이 달라 전체 순서가 아니고, 자리를 보장하지도 않는다.

## 13. 알림 (Notification)

> 슬롯 해제 이벤트(`SlotReleased`)를 컨슈머가 처리해 만든 "자리가 비었다" 알림이다. 알림 대상은 이벤트 payload가 아니라 **그 시점의 DB**로 다시 고른다: 같은 공간의 활성 대기 중 해제 구간과 겹치고, 신청 구간 **전체**가 지금 비어 있으며, 회원이 `ACTIVE`이고, 본인이 겹치는 만료 전 `HELD`·`CONFIRMED`·`IN_USE` 예약을 갖고 있지 않은 항목이다. 알림 후에도 대기는 `WAITING`을 유지한다. Kafka를 끈 환경(`slotkey.kafka.enabled=false`, 기본값)에서는 알림이 생성되지 않는다.

- `GET /notifications` (인증 필요): 내 알림 목록, 최신순(id 내림차순). `page`, `size`(기본 20). 응답 200 `ApiResponse<PageResponse<NotificationResponse>>`. 오류: `AUTHENTICATION_REQUIRED`(401). 본인 알림만 조회된다.
- `GET /notifications/unread-count` (인증 필요): 안 읽은 알림 개수. 응답 200 `ApiResponse<{ unreadCount }>`. 오류: `AUTHENTICATION_REQUIRED`(401)
- `POST /notifications/read-all` (인증 필요): 필수 쿼리 `upToId`(Long, 렌더링된 목록의 최대 알림 id)를 받는다. 토큰 회원의 `read_at IS NULL`이며 `id <= upToId`인 알림만 한 번의 UPDATE로 현재 시각으로 갱신한다. 요청 본문 없음. 응답 200 `ApiResponse<{ updatedCount }>`. 재요청 시 이미 읽은 시각을 유지하고 변경 건수 0을 반환한다. 오류: `AUTHENTICATION_REQUIRED`(401). 상한보다 큰 id의 알림은 미변경이며 상한 이하의 이전 페이지 알림은 포함한다. 프런트는 빈 목록이면 호출하지 않는다. 예: `POST /api/v1/notifications/read-all?upToId=123`. Long 변환 불가는 400이다. 상한 누락은 공통 예외 검증 유의사항을 참조한다.

### 응답 필드 (`NotificationResponse`)

`id`, `waitlistEntryId`, `message`, `read`(불리언, `read_at`이 있으면 `true`), `createdAt`. `message`는 생성 시점의 공간 이름·구간을 담은 문자열 스냅샷이며 선착순·예약 비보장 안내를 포함한다.

## 14. [관리자] 통계 (Stats)

> 구현됨(2026-10-16). 이유와 trade-off는 [decisions/space-ranking.md](../decisions/space-ranking.md), 범위는 [plans/space-ranking](../plans/space-ranking/00-space-ranking-plan.md). 모든 API는 ADMIN 전용이며 응답은 `ApiResponse<T>`로 감싼다. 오류: `AUTHENTICATION_REQUIRED`(401), `ACCESS_DENIED`(403), `VALIDATION_FAILED`(400).

- `GET /admin/stats/space-ranking?period=&date=&metric=&location=&limit=`: 공간별 베스트 랭킹.
  - `period`: `DAILY` | `WEEKLY` | `MONTHLY` (필수). `date`: `yyyy-MM-dd`, 그 날짜를 포함하는 달력 기간(KST, 주는 월요일 시작)을 조회한다(필수). `metric`: `NET_REVENUE` | `RESERVATION_COUNT` | `SLOT_COUNT` (정렬 기준, 기본 `NET_REVENUE`). `location`: `판교` | `강남` | `하남` 필터(선택). `limit`: 기본 10, 최대 50(1 미만·50 초과는 400).
  - **끝난 기간**만 순위를 내려준다. 오늘·이번 주·이번 달을 포함하는 `date`는 `status = PENDING`과 `confirmAt`을 내려주고 `items`는 비어 있다. 현재 기간보다 미래인 `date`는 400 `VALIDATION_FAILED`다. 기간이 막 끝난 직후(기간 끝 날짜 00:00~00:01)에 확정 행이 아직 없으면 빈 `CONFIRMED`가 아니라 `PENDING`이다. 끝났지만 스냅샷이 없는 기간은 `status = UNAVAILABLE`이다. 판정은 별도 표식 없이 근사한다: 행이 없는 끝난 기간이 그 `period`의 가장 이른 확정 기간보다 앞서거나, 그 `period`의 확정 기록이 전혀 없으면 `UNAVAILABLE`, 확정 기록 범위 안이면 거래가 없던 기간으로 보고 `CONFIRMED`에 `items`가 빈 배열이고 `computedAt`이 `null`이다. 오늘이 속한 기간 안의 날짜는 미래 날짜여도 `PENDING`이다. 필수 파라미터(`period`, `date`) 누락은 공통 예외 검증 유의사항을 참조한다. 없는 `period`·`metric` 값, 날짜 형식 오류, 지원하지 않는 `location`도 400 `VALIDATION_FAILED`다.
  - 응답: `{ period, periodStart, periodEnd, metric, status, confirmAt, computedAt, items[] }`. `periodStart`는 포함, `periodEnd`는 제외(다음 기간 시작)다. `computedAt`은 확정 계산 완료 시각, `confirmAt`은 `PENDING`일 때만 값을 갖는 확정 기준 시각(해당 기간 다음 날 00:00)이다.
  - `items[]` 원소: `{ rank, previousRank, spaceId, spaceName, location, spaceStatus, netRevenue, reservationCount, slotCount }`. `previousRank`는 같은 `period`·`metric`·`location` 필터의 직전 기간 **전체** 순위(`limit`을 적용하지 않은 순위)이며, 직전 기간에 행이 없으면 `null`이다. `rank`는 필터를 적용한 목록 안의 순번이다. 세 지표는 항상 모두 내려가고 `metric`은 정렬만 정한다.
- `GET /admin/stats/location-ranking?period=&date=&metric=&limit=`: 지역별 베스트 랭킹. 공간 행을 스냅샷에 저장된 `location`으로 합산한다. 파라미터와 상태 규칙은 위와 같다. `items[]` 원소: `{ rank, previousRank, location, spaceCount, netRevenue, reservationCount, slotCount }`.
- `GET /admin/stats/status-counts?date=&spaceId=`: 해당 날짜(KST, 기본 오늘)에 `start_time`이 속한 예약의 상태별 건수. `spaceId`(선택, 양수)를 주면 그 공간만 센다(운영 대시보드의 오피스 필터용). 날짜 형식 오류·`spaceId` 오류는 400 `VALIDATION_FAILED`. **실시간 값**이다(랭킹 스냅샷이 아님). 응답 `{ date, counts: { HELD, EXPIRED, CONFIRMED, IN_USE, COMPLETED, CANCELLED, NO_SHOW } }` — 일곱 상태를 모두 0 포함으로 내려준다.


### 집계 정의와 유의사항

- **순매출(`netRevenue`)**: 크레딧 원장의 `RESERVATION_CHARGE`·`REFUND`·`PENALTY`를 합산한 금액이다(회원 잔액 기준 부호의 반대, `−SUM(amount)`). 결제는 결제 시각에, 환불과 위약금은 **발생 시각**에 반영하는 현금주의다. 위약금은 수익으로 포함한다. 확정 후 발생한 환불은 지난 확정값을 바꾸지 않고 환불이 일어난 날의 값에 반영되므로, 특정 날의 값이 음수가 될 수도 있다.
- **예약 건수(`reservationCount`)**: `CONFIRMED`·`IN_USE`·`COMPLETED`·`NO_SHOW` 예약을 `start_time`이 속한 기간으로 센다. 취소된 예약은 건수에 포함되지 않지만 위약금은 순매출에 포함된다.
- **예약 슬롯 수(`slotCount`)**: 위와 같은 예약의 `(end_time − start_time) / 30분` 합이다.
- 매출·건수·슬롯 수가 모두 0인 공간은 저장하지 않으므로 순위에 나타나지 않는다. 비활성(`INACTIVE`) 공간도 과거 실적이 있으면 포함되며 `spaceStatus`로 구분한다.
- **확정 시각**: 일간은 매일, 주간은 월요일, 월간은 매월 1일 00:01(KST)에 방금 끝난 기간을 계산한다. 서버가 꺼져 있었거나 계산이 실패해 확정 행이 없는 끝난 기간은 서버 시작 시와 매일 확정 직후에 최근 31일 범위(`slotkey.scheduler.space-ranking.backfill-days`)에서 다시 채운다. 이미 행이 있는 기간은 다시 계산하지 않는다. 데이터 범위는 `[기간 시작 00:00:00, 다음 기간 시작 00:00:00)`이다. 00:01 이후에 늦게 반영된 거래는 확정값에 들어가지 않는다.
- **동점 처리**: `NET_REVENUE`는 순매출 → 예약 건수 → `spaceId` 오름차순, `RESERVATION_COUNT`는 예약 건수 → 순매출 → `spaceId`, `SLOT_COUNT`는 슬롯 수 → 예약 건수 → `spaceId`다. 값이 같아도 `rank`는 겹치지 않고 이 순서대로 1, 2, 3으로 매긴다. 지역 랭킹의 마지막 키는 `location` 가나다순이다.

### 14-2. 일반 회원용 랭킹 (`/stats`)

> 2026-10-17 추가. 2026-10-18 변경: **비로그인 방문자도 호출할 수 있다**(`GET /api/v1/stats/**` permitAll). 로그인 전 메인 화면의 "인기 오피스" 메뉴에서 바로 보이게 하기 위함이며, 순매출이 빠진 확정 집계라 공개해도 매출 정보가 드러나지 않는다. 같은 확정 스냅샷을 쓰지만 **순매출은 내려주지 않는다.**

- `GET /stats/space-ranking?period=&date=&metric=&location=&limit=`, `GET /stats/location-ranking?period=&date=&metric=&limit=`: 파라미터·기간 상태(`CONFIRMED`/`PENDING`/`UNAVAILABLE`)·동점 규칙·`previousRank`는 §14의 관리자 랭킹과 같다. 차이는 다음과 같다.
  - `metric`은 `RESERVATION_COUNT`(기본) 또는 `SLOT_COUNT`만 허용한다. `NET_REVENUE`는 정렬 순서로 매출 순위가 드러나므로 400 `VALIDATION_FAILED`다.
  - `items[]`에서 `netRevenue`가 빠진다. 공간: `{ rank, previousRank, spaceId, spaceName, location, spaceStatus, reservationCount, slotCount }`, 지역: `{ rank, previousRank, location, spaceCount, reservationCount, slotCount }`.
- 관리자 랭킹(`/admin/stats/*`)은 그대로 ADMIN 전용이며 순매출을 포함한다.
