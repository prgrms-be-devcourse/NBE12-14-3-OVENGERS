# 문서-코드 정합성 검증 step-4: 핵심 도메인 엔드포인트별 오류 목록·DTO 필드 대조

- 작업일: 2026-10-27
- 역할: 검증자(Claude, Sonnet 5.5). 애플리케이션 코드는 수정하지 않았고 `api-spec.md`만 고쳤다.
- 범위(사용자 선택): **reservation · credit · access** 도메인만. 다른 도메인(member, space, auth, inquiry, waitlist, stats, audit)은 하지 않았다.
- 이전 단계: [step-1](step-1.md)(엔드포인트·에러 코드 존재와 상태 코드), [step-2](step-2.md), [step-3](step-3.md). 이 브랜치는 step-3 브랜치 위에 쌓았다.
- 기준 우선순위: 코드와 고정된 테스트(골든 JSON) > `api-spec.md`

## 방법

1. 서비스·엔티티·정책 클래스에서 `ErrorCode.*`를 던지는 지점을 도메인별로 추출하고, 각 엔드포인트의 호출 경로(컨트롤러 → 서비스 → 엔티티·정책·크레딧 포트)를 따라 "이 엔드포인트가 던질 수 있는 코드" 집합을 만들었다.
2. 문서의 엔드포인트별 "오류:" 목록과 비교했다(양방향: 코드에만 있는 것, 문서에만 있는 것).
3. 요청·응답 DTO의 생성자 필드 이름을 추출해 문서의 JSON 예시·필드 목록과 비교했다.

이번 단계는 스크립트가 아니라 **코드를 읽은 수동 대조**다. 호출 경로 추적은 자동화하기 어려워 CI 검사로 만들지 않았다(아래 "다음 단계 후보").

## 한눈에 보는 결과

| 항목 | 대조 건수 | 일치 | 불일치 |
| --- | --- | --- | --- |
| 엔드포인트별 오류 목록 | 19개 (reservation 10, credit 5, access 4) | 13 | **6개** (문서 누락·서술 부정확) |
| DTO 필드 (요청·응답) | 19개 클래스 | 18 | **1개** (문서에 없는 필드를 적음) |
| 문서가 틀린 서술 | — | — | 2건 (`holdExpiresAt`, "회원 상태 재검사 안 함") |

## 불일치 목록 (모두 `api-spec.md`를 코드에 맞춰 수정함)

| # | 엔드포인트 | 불일치 | 근거 | 조치 |
| --- | --- | --- | --- | --- |
| 1 | `GET /admin/reservations/{id}` | 문서가 응답에 `holdExpiresAt`이 있다고 했으나 DTO에 없다. | `AdminReservationDetailResponse.kt` 필드 목록, 골든 `golden/reservation/admin-detail-empty-history.json`에도 없다. `git log --all -S holdExpiresAt`로 이 DTO 파일의 이력을 찾아도 필드를 추가한 기록이 없다. | 문서에서 삭제 |
| 2 | `POST /reservations` | 문서 주석이 "회원 ACTIVE 상태를 별도 재검사하지 않음"이라고 했으나 서비스가 회원을 잠금 조회해 없으면 `AUTHENTICATION_REQUIRED`, `WITHDRAWN`이면 `ACCOUNT_WITHDRAWN`(401)으로 거절한다. `SUSPENDED`는 이 서비스에서 검사하지 않는다. | `ReservationHoldService.kt:66-71` | 주석 정정, 오류 목록에 `ACCOUNT_WITHDRAWN` 추가 |
| 3 | `POST /reservations/{id}/cancel` | `ACCOUNT_WITHDRAWN`(401) 누락 | `ReservationCancelService.kt` | 추가 |
| 4 | `POST /reservations/{id}/extend` | `ACCOUNT_WITHDRAWN`(401) 누락 | `ReservationExtendService.kt` | 추가 |
| 5 | `POST /credits/charge-orders/confirm` | `VALIDATION_FAILED`(400) 누락. 본문 필수 필드(`paymentKey`, `orderId`, `amount`)가 비면 400이고, 충전 후 잔액이 `int` 상한을 넘으면 DB 2 단계에서도 `VALIDATION_FAILED`를 던진다. | `ChargeOrderConfirmRequest.kt`(`@NotBlank`, `@NotNull`), `ChargeOrderConfirmTransactionService.kt:113-115` | 추가 |
| 6 | `POST /admin/reservations/{id}/force-cancel` | `SPACE_NOT_FOUND`(404) 누락. `HELD` 예약 취소 시 공간 잠금 조회가 실패하면 던진다. | `AdminReservationService.kt:130-132` | 추가 |
| 7 | `POST /reservations/{id}/door-token` | 409 `RESERVATION_STATE_CONFLICT`를 "동시 발급 경합"이라고만 적었으나 실제로는 예약이 `CONFIRMED`·`IN_USE`가 아니거나 이미 종료된 경우에도 같은 코드다. | `DoorAccessTokenService.java:88-91, 98-104, 134-137` | 서술 확장 |

3·4·2는 같은 원인이다. 탈퇴·정지 회원은 인증 필터가 먼저 `INVALID_ACCESS_TOKEN`(401)으로 막기 때문에(step-3 참조) 서비스의 `ACCOUNT_WITHDRAWN`은 **필터를 통과한 직후 탈퇴가 경합한 경우에만** 나온다. 문서에는 §5-1에 이 설명을 한 번 적고 다른 엔드포인트에서는 참조로 연결했다. 대기 신청(§12)의 `ACCOUNT_WITHDRAWN`(401)은 이미 문서에 있었으므로 이번 수정으로 서술이 일관돼졌다.

### 문서에 추가하지 않은 항목 (도달 가능성이 사실상 없음)

- `POST /reservations/{id}/pay`, `POST /credits/charge-orders/confirm`: `MEMBER_NOT_FOUND`(404). 크레딧 포트와 DB 2가 회원을 조회하다 없을 때 던진다. 인증된 회원이므로 정상 흐름에서는 나오지 않는다.
- `ACCESS_DENY_REASON_REQUIRED`, `ACCESS_LOG_NOT_FOUND`: step-1에서 내부 전용으로 판단함.

## 일치한 것

- **reservation:** `GET /reservations`, `GET /reservations/{id}`, `POST /reservations/{id}/pay`(오류 9개 모두), `POST /reservations/{id}/check-out`, `GET /admin/reservations`, `GET /admin/reservations/{id}`의 오류 목록. 응답 DTO `ReservationResponse`(15개 필드, `spaceVersion`·`refundAmount`·`penaltyAmount` 포함), `ReservationListResponse`(16), `ReservationDetailResponse`(13), `ReservationStatusHistoryResponse`(4), `AdminReservationResponse`(10), 요청 DTO 4개.
- **credit:** `POST /credits/charge-orders`, `GET /credits/charge-orders/{orderId}`, `GET /credits/charge-orders`, `POST /webhooks/tosspayments`. DTO 6개(`ChargeOrderHistoryResponse`의 `paymentMethod`·`paymentProvider`·`updatedAt` 포함).
- **access:** `PATCH .../access-token/revoke`, `GET .../access-logs`, `POST /door-access/verify`. DTO 4개(`DoorAccessVerifyResponse.firstCheckIn` 포함).

## 변경 파일

- `docs/api/api-spec.md`: 위 불일치 7건 반영 (8줄 변경)
- `docs/testing/doc-verification/step-4.md`: 이 보고서 (신규)

## 실행한 명령과 결과

- 서비스 3개 도메인의 `ErrorCode.*` 사용 지점 추출(`grep`), DTO 필드 추출(Python, `-I`), 서비스 코드와 골든 JSON 직접 읽기.
- 수정 후 `check_api_spec.py`(step-1 검사)와 `check_erd.py`(step-2 검사) 재실행: 실패 0건(경고 2건은 step-1과 같은 부정 문장·묶음 표기).
- 애플리케이션 테스트는 실행하지 않았다(코드 변경 없음).

## 가정한 것과 확인하지 못한 것

- 호출 경로를 사람이 따라갔다. 서비스가 다른 빈을 호출하는 깊은 경로(예: 크레딧 포트 안쪽)는 이름이 같은 `ErrorCode`가 어디서 나오는지 전수 추적하지 않았다. 따라서 "문서에 없는데 던질 수 있는 코드"를 놓쳤을 수 있다.
- **HTTP 성공 상태(200/201/202/204)는 비교하지 않았다.** 오류 코드와 DTO 필드만 봤다.
- 요청 검증 제약(길이·범위·정규식)은 비교하지 않았다.
- 컨트롤러의 파라미터 바인딩 오류(잘못된 쿼리 enum, 경로 타입 불일치)가 만드는 400은 서비스 코드에서 오지 않아 추적 대상에서 빠졌다. 문서에는 `VALIDATION_FAILED`로 이미 적혀 있다.
- DTO는 필드 **이름**만 비교했다. 타입·nullable·직렬화 형식(날짜 포맷)은 비교하지 않았다. 일부는 기존 골든 테스트가 고정하고 있다(예약 DTO).
- `reservation` 패키지의 Java 파일 `reservation/dto/response/DoorAccessLogResponse.java`가 `access` 패키지의 같은 이름 Kotlin 클래스와 별개로 남아 있다. 사용 여부는 확인하지 않았다(발견만 기록).
- 다른 도메인(member, space, auth, inquiry, waitlist, stats, audit)은 대상 밖이다.

## 관련 결정 문서

- `docs/decisions/*` 변경 없음.

## 리뷰 체크리스트

- [ ] 불일치 1(미결): `AdminReservationDetailResponse`에 `holdExpiresAt`을 **추가해야 하는지** 제품 판단 (문서를 코드에 맞췄으나, 관리자 상세에서 `HELD`의 만료 시각이 필요하면 코드를 고치는 쪽이 맞다)
- [ ] 불일치 2~4: 정지 회원에 대한 `ReservationHoldService`의 서비스 레벨 방어 부재가 필터에 전적으로 의존하는 점을 수용할지
- [ ] 문서 §5-1의 `ACCOUNT_WITHDRAWN` 설명(필터 경합 때만 나옴)이 정확한지
- [ ] 수정 후 CI의 `check_api_spec.py`가 통과하는지(로컬 재실행은 통과, PR의 CI로 최종 확인)

## 사용자 결정 (2026-10-27)

1. `holdExpiresAt`: 관리자 상세에 만료 시각이 필요한 요구가 있었는지 사용자가 기억하지 못했다. 그래서 문서를 코드에 맞춘 상태로 두고, 요구가 확인되면 코드를 고치는 쪽으로 다시 판단한다(미결 항목).
2. 호출 경로 대조 자동화: 위양성이 많아 하지 않기로 했다. 다음에 필요하면 "문서의 오류 코드마다 그 코드를 확인하는 테스트가 있는가"를 매핑하는 방식을 검토한다.

## 발표용 숫자 (step-1~4 누적)

- 대조: 엔드포인트 65, 에러 코드 65, 테이블 25, FK 23, 절대 규칙 7, 핵심 도메인 엔드포인트 19개와 DTO 19개.
- 문서 쪽 오류(누적): api-spec 누락·부정확 9건 이상(step-1: 2, step-4: 7), erd 누락 3건, AGENTS.md 에러 코드 3건·규칙 문장 2건.
- 가장 인상 깊은 사례: 문서가 응답에 있다고 한 `holdExpiresAt`이 코드와 골든 JSON 어디에도 없었다(불일치 1). 프론트가 이 문서를 기준으로 한다는 점(`api-spec.md` 9줄)을 고려하면 실제 연동 오류로 이어질 수 있다.
- 자동 검사로 지켜지는 것: 엔드포인트·에러 코드 존재와 상태(step-1), 테이블·컬럼·UNIQUE·FK(step-2). 오류 목록·DTO는 수동 대조라 다시 어긋날 수 있다.
