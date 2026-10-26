# 골든 현황 조사 — 1단계 (읽기 전용)

- 작업: 기존 골든 목록화, 충전 주문·웹훅·대기·알림·예약 응답의 고정 여부 확인
- 범위: 소스·테스트·문서 수정 없음. 테스트는 실행하지 않았다(아래 "미확인" 참고).
- 대상 도메인: 이태호(`@anton061311`) 담당만 — `reservation` 본체, `credit`(PG 충전·복구·웹훅), `waitlist`, `outbox`, `notification` ([team-guide.md](../../plans/kotlin-migration/team-guide.md)). 타 담당 도메인은 "참고" 한 줄로만 적는다.
- 기준: `docs/testing/java-kotlin-golden-guide.md`, 브랜치 `dev` @ `5555e47`(작업 트리 clean)
- 근거 표기: 테스트 클래스/메서드 이름. 확인하지 못한 것은 "미확인".

## 1. 기존 골든 목록 (담당 영역)

| 영역 | 골든 | 비교 테스트 | 언어 | 도입 커밋 |
| --- | --- | --- | --- | --- |
| 예약 DTO | `golden/reservation/` 16개 | `ReservationDtoGoldenTest`, `ReservationDtoContractTest` | **Java** | 61f7cd2 (#42) |
| 예약 상태 이력 | `golden/reservation/status-history-transitions.json` | `ReservationStatusHistoryGoldenTest` | Kotlin | c023b6d (#150) |
| 충전·PG·웹훅·대기·알림 DTO | **JSON 파일 없음**, 테스트 안 인라인 문자열 | `FridayRemainingDtoGoldenTest` (`roundTripKeepsJavaJson`, 파라미터 14건) | **Java** | 61f7cd2 (#42) |
| outbox | 골든 없음(미확인: payload 검증은 `OutboxRecordIntegrationTest.assertEvent`) | — | — | — |

참고(담당 밖, 상세 생략): 공통 응답·공간·감사·통계·출입·크레딧 거래(관리자 지급) 골든이 각 담당자 영역에 있다.

## 2. 항목별 고정 여부

범례: 고정됨 / 부분 / 없음 / 미확인.

### 2-1. 충전 주문

| 사례 | 상태 | 근거 |
| --- | --- | --- | 
| 요청 `ChargeOrderCreateRequest`, `ChargeOrderConfirmRequest` 필드 | 고정됨(인라인) | `FridayRemainingDtoGoldenTest.roundTripKeepsJavaJson[charge-create / charge-confirm]` |
| 응답 `ChargeOrderResponse`, `ChargeOrderConfirmResponse` | 고정됨(인라인) | `…[charge-order / charge-confirm-response]` |
| 이력 응답 `ChargeOrderHistoryResponse`(paymentMethod·paymentProvider null) | 고정됨(인라인, null 경계 포함) | `…[charge-history-null-display]` |
| 이력 응답의 값이 채워진 경우 | 부분 | HTTP 단언만 있음: `ChargeOrderControllerTest.list_returnsMyHistoryPage` (필드 일부) |
| HTTP 상태 201/200/202/400/401, 에러 코드 `IDEMPOTENCY_KEY_REQUIRED` | 고정됨 | `ChargeOrderControllerTest`: `create_returnsCreatedPendingOrder`, `confirm_paid_returnsOk`, `confirm_confirming_returnsAccepted`, `confirm_withoutIdempotencyKey_returnsBadRequest`, `create_*returnsBadRequest`, `create_withoutAuthentication_returnsUnauthorized` |
| PG 요청/결과 DTO (`PgConfirmRequest`, `PgConfirmationResult`, `PgPaymentLookupResult`) | 고정됨(인라인, null 경계 포함) | `…[pg-confirm-request / pg-confirmation-unknown / pg-lookup-not-received]` |
| 요청 누락·null 거절 | 부분 | 컨트롤러 테스트는 상태코드(400)만 확인, 응답 본문 골든 없음. `FridayRemainingDtoNullableContractTest.missingRequiredRequestUsesNullAndKeepsValidation`(내용 세부 미확인) |
| 실패(FAILED)·취소 상태의 응답 | 없음 | 해당 사례 이름 없음 |

### 2-2. 웹훅

| 사례 | 상태 | 근거 |
| --- | --- | --- |
| 입력 DTO `PgWebhookEvent`(null 힌트) | 고정됨(인라인) | `…[webhook-null-hints]` |
| 처리 결과 `PgWebhookEventResult` | 부분 | `…[webhook-result]`가 `"CONFIRMED"` 1개만 직렬화. 나머지 enum 값은 없음 |
| HTTP 계약(200 확인응답, 중복·무시, 400, 인증 없이 수신, 다른 메서드 401) | 고정됨 | `TossWebhookIntegrationTest`: `receive_duplicateTransmission_isIgnored`, `receive_otherEventType_isAcknowledgedWithoutRecord`, `receive_unknownOrder_*`, `receive_missingTransmissionId_returns400`, `receive_malformedJson_returns400`, `webhookPath_otherMethod_stillRequiresAuthentication` |
| 응답 본문 | 없음 | 위 테스트는 `status()`만 검사, 본문 단언 미확인 |
| 처리 정합성(중복 충전 방지) | 고정됨 | `PgWebhookEventProcessorIntegrationTest`: `process_sameEventConcurrently_topUpOnce`, `confirmAndWebhookConcurrently_topUpOnce` 등 |

### 2-3. 대기·알림

| 사례 | 상태 | 근거 |
| --- | --- | --- |
| 대기 요청/응답 필드 | 고정됨(인라인) | `…[waitlist-apply / waitlist-entry]` |
| 알림 응답, 읽음 처리, 안읽은 개수 | 고정됨(인라인) | `…[notification / read-all / unread-count]` |
| 대기 HTTP: 201/204/400/401/403, 에러 코드 | 고정됨 | `WaitlistControllerTest`: `apply_returns201`, `apply_missingSpaceId_returns400`, `apply_noToken_returns401`, `cancel_returns204`, `cancel_notOwner_returns403`, `getMyEntries_returns200` |
| 알림 HTTP: 목록·안읽은 개수·읽음 일괄·401 | 고정됨 | `NotificationControllerTest.list_usesTokenMemberId`, `unreadCount_usesTokenMemberId`, `noToken_returns401`, `NotificationReadIntegrationTest.readsOnlyMyUnreadAndKeepsExistingReadTime` |
| 대기 상태 값 `CANCELLED` 등 | 부분 | `waitlist-entry` 골든은 `WAITING`만. 서비스 테스트(`WaitlistServiceIntegrationTest`)는 엔티티 검증이며 JSON은 아님 |
| 알림 `read=true` 응답 | 없음 | 인라인 골든은 `read=false`만. (정정: `NotificationResponse`에는 `readAt` 필드가 없다. 3단계 검증에서 확인) |
| 대기번호·빈자리 알림 이벤트(Outbox 메시지 JSON) | 미확인 | `OutboxRecordIntegrationTest.assertEvent`가 payload를 보는 것으로 보이나 내용은 읽지 않음. Kafka 메시지는 "HTTP·메시지 유지" 대상이므로 별도 확인 필요 |

### 2-4. 예약 응답

| 사례 | 상태 | 근거 |
| --- | --- | --- |
| HOLD/CONFIRMED/CANCELLED/IN_USE/COMPLETED 응답 | 고정됨 | `ReservationDtoGoldenTest.responseMatchesJavaGolden[hold-response … completed-response]` (정수 타입 단언 포함) |
| 상세·목록·관리자 응답의 빈 이력·관련 데이터 누락 | 고정됨 | `…[detail-empty-history / list-missing-space / admin-missing-related-data / admin-detail-empty-history]` |
| 요청 DTO·검색 조건 | 고정됨 | `ReservationDtoGoldenTest.requestRoundTripKeepsFieldNamesAndTypes` (6건) |
| 요청 거절(누락·null·빈 문자열·길이 경계) | 고정됨 | `ReservationDtoContractTest`: `missingAndExplicitNullKeepValidationResponse`, `forceCancelReasonLengthBoundary`, `allMissingCreateFieldsKeepFourRequiredErrors` 등 |
| 상태 이력 | 고정됨 | `ReservationStatusHistoryGoldenTest.statusHistoryMatchesJavaGolden` |
| 결제 `/pay` 멱등성 키 누락 | 고정됨 | `ReservationControllerTest.shouldRejectPaymentWhenIdempotencyKeyIsMissing` |
| 관리자 조회 HTTP 계약 | 고정됨(jsonPath) | `AdminReservationControllerTest`, `AdminReservationSecurityIntegrationTest` |
| OpenAPI 스키마 | 고정됨 | `ReservationOpenApiContractIntegrationTest` |
| 확장(extend) 응답·`/pay` 성공 응답 | 미확인 | 골든 파일 이름에 없음. `confirmed-response`가 `/pay` 응답인지 확인하지 않음 |
| 강제 취소 응답, HOLD 만료 후 결제 409 응답 본문 | 미확인 | 이름만으로 판단 불가 |

## 3. 전환 전 기준(Java baseline) 여부

가이드의 "전환 전 Java 응답으로 기준 확보"에 비춰, 담당 영역 골든의 출처를 구분한다.

| 골든 | 판단 | 근거 |
| --- | --- | --- |
| 예약 DTO(`golden/reservation/*`), 금요일 인라인 골든 | **전환 전 기준이 아님(확인 불가)** | 둘 다 61f7cd2 "migrate reservation DTO to Kotlin"에서 처음 추가됨. 전환 커밋과 같은 커밋이라 Java에서 통과한 기록을 이 조사로 확인하지 못함. 테스트 이름은 "Java 기준"이라고 하지만 근거는 이름뿐 |
| 예약 상태 이력 | **이미 Kotlin인 코드에서 만든 골든** | c023b6d. 전환 후 출력이므로 "전환 전 기준"이 아니다(회귀 방지 용도로는 유효) |

참고: 충전 주문·웹훅·대기·알림·outbox의 운영 코드는 이미 Kotlin이다(8f47467 #103, 020492a #85). 따라서 이 영역의 Java 기준을 얻으려면 가이드 5절대로 전환 전 Java 커밋(`8f47467^`, `020492a^`, 예약 DTO는 `61f7cd2^` 후보)을 별도 작업 공간에서 재현해야 한다. 담당자 확인: 전환 때 골든 절차를 건너뛰고 바로 Kotlin으로 전환했다(2026-10-26 구두).

## 4. 빠진 곳 (2단계 후보)

1. **인라인 골든의 한계**: 금요일 DTO는 JSON 파일이 없고 테스트 코드에 있어 가이드의 "정적 파일 기준"과 형식이 다르다. 라운드트립(JSON→객체→JSON) 방식이라 서버 응답 생성 경로(`from()` 팩토리 등)를 거치지 않는다.
2. **값이 채워진 충전 이력 응답, FAILED·CANCELLED 상태 응답**: 없음.
3. **웹훅 처리 결과 enum 전 값**, **대기 CANCELLED/상태별 응답**, **알림 `read=true` 응답**: 없음.
4. **Outbox/Kafka 메시지 payload**: 미확인(메시지 형식 보존 대상).
5. **HTTP 응답 본문 전체 골든**(충전 확인 202, 웹훅 200, 대기 201): 상태코드·일부 필드만 단언.
6. **예약 `/pay`·extend·force-cancel 응답과 409 응답**: 미확인.

## 5. 제안 진행 방식 (2단계는 "다음" 지시 후)

- Java 기준이 필요한 항목은 가이드 3·5절대로: 전환 전 커밋을 별도 worktree에서 열어 대표 사례를 실행해 정적 JSON을 확보하고, 현재 Kotlin과 같은 파일로 비교한다.
- Java 기준을 재현할 수 없는 항목은 "전환 후 회귀 방지용 골든"으로 표시해 따로 적는다(전환 전 기준과 혼동 금지).
- 규칙: 운영 코드와 기존 테스트는 수정하지 않는다. 새 파일만 추가한다. 외부 API(토스 등)는 Fake만 사용한다. 테스트는 `cleanTest`를 포함해 실행한다. 커밋은 대상 파일 목록 확인 후, 한 번에 한 단계씩.

## 6. 실행·검증 기록

- 실행한 테스트: 없음(읽기 전용 조사). 위 "고정됨"은 테스트 이름·소스 확인 기준이며 이번 세션에서 통과를 확인한 것이 아니다.
- 변경 파일: 이 보고서 1개(`docs/testing/golden-survey/step-1.md`)만 추가.
- 미확인: 각 테스트의 현재 통과 여부, 골든 JSON 내용과 Java 실제 출력의 일치, 61f7cd2 시점의 Java 실행 근거, `FridayRemainingDtoNullableContractTest` 세부 사례.

## 7. 리뷰 체크리스트

- [ ] "고정됨" 표시가 테스트 이름과 맞는지(표 2-1~2-4)
- [ ] 3절의 전환 전/후 기준 구분이 커밋 이력과 맞는지
- [ ] 소스·기존 테스트 변경이 없는지(`git status`로 이 파일만 신규)
- [ ] 4절 빠진 곳 중 2단계에서 채울 범위를 결정

## 8. 사용자 결정이 필요한 질문

1. 2단계에서 Java 기준 재현을 어느 영역부터 할지(충전·웹훅 / 대기·알림 / 예약).
2. 인라인 골든을 정적 JSON 파일로 옮길지(기존 테스트 수정이 필요하므로 금지 규칙과 충돌, 새 파일로 추가만 할지).
