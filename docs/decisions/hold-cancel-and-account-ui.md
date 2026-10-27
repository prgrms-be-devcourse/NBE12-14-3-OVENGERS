---
title: 결제 대기 취소, 대기 목록 개선, 충전 내역, 계정 UI 개편
type: 기능
status: 구현 반영 (테스트 실행 결과는 본문의 당시 기록 참조)
date: 2026-10-16
tags: [reservation, credit, waitlist, frontend, account-ui]
related: [waitlist.md, outbox-kafka.md, notification-ui.md, pg-charge.md, reservation-concurrency.md]
---

# 결제 대기 취소, 대기 목록 개선, 충전 내역, 계정 UI 개편

## 한 줄 요약

결제 전(HELD) 예약을 사용자가 직접 취소할 수 있게 하고(환불 없이 슬롯만 반환 후 빈자리 이벤트 발행), 내 대기 목록에 오피스 사진과 상세 링크를 붙이고, PG 충전 내역 조회 API·화면을 새로 만들고, 헤더 메뉴와 마이페이지를 재구성하고, 토스 약관 체크 버그를 고쳤다.

## 0. 작업 범위 한눈에 보기 (AI·사람 공통 요약)

작성 당시 `feat/notification-ui`에서 진행한 작업 기록이다. 아래 기능은 기능 동결 커밋 `d1a15ec`에 반영되어 있다. 본문의 테스트 수치와 실행 대기 표기는 당시 기록이며 이번 문서 갱신에서 새로 실행한 결과가 아니다. 작업 묶음은 아래 A~F와 같다.

| # | 묶음 | 백엔드 | 프론트 | 문서 |
| --- | --- | --- | --- | --- |
| A | 결제 대기(HELD) 취소 | 있음 | 있음 | api-spec 5-4 |
| B | 내 대기 목록: 오피스 사진 + 상세 링크 | 없음 | 있음 | 없음 |
| C | PG 충전 내역 조회 | 있음 (신규 API) | 있음 (신규 화면) | api-spec 5-0-1-1 |
| D | 헤더 메뉴 재배치 + 마이페이지 개편 | 없음 | 있음 | 없음 |
| E | 토스 약관 위젯 초기 동의 버그 수정 | 없음 | 있음 | 없음 |
| F | 충전 내역에 결제수단(아이콘 포함) 표시 | 있음 (V21 마이그레이션 포함) | 있음 | api-spec 5-0-1-1 |

## 1. 문제와 배경

- A: 결제 화면(`/reservations/{id}/payment`)에 취소 수단이 없었다. 마음이 바뀌어도 HELD 10분이 지나 만료되길 기다려야 했고, 그동안 슬롯이 묶여 대기자에게 알림도 가지 않는다. 기존 `POST /reservations/{id}/cancel`은 CONFIRMED 예약만 받았다(조건부 UPDATE가 `status = CONFIRMED AND now < start_time`).
- B: 내 대기 목록이 텍스트뿐이라 어떤 오피스인지 알아보기 어렵고, 항목에서 예약으로 이동할 수 없었다.
- C: 백엔드에 충전 주문의 "내 목록" 조회가 없었다(단건 조회 `GET /credits/charge-orders/{orderId}`만 존재). 그래서 프론트만으로는 내역 화면을 만들 수 없었다.
- D: 헤더 상단 메뉴가 "오피스 찾기, 모의 출입" 둘뿐이고 계정 드롭다운에 항목이 뒤섞여 있었다. 마이페이지는 정보를 나열만 했다.
- E: 충전 화면(`/credits/charge`)에서 약관이 이미 체크돼 있는데 결제 버튼이 비활성이라, 체크를 풀었다가 다시 눌러야 했다.

## 2. 선택과 이유

### A. 결제 대기(HELD) 취소

- 기존 취소 API를 재사용한다. `ReservationCancelService.cancel`이 예약 상태가 HELD면 새 경로 `cancelHeld`로 분기한다. 새 엔드포인트를 만들지 않은 이유는 프론트가 이미 `cancelReservation`을 가지고 있고, 의미(내 예약 취소)가 같기 때문이다.
- 상태 전이는 조건부 UPDATE `cancelIfHeld`(`WHERE id AND status = HELD`)로 한다. 결제(`confirmIfHeldAndNotExpired`)도 같은 행에 조건부 UPDATE를 하므로, 동시에 눌러도 둘 중 하나만 영향 행 1을 얻는다. 0이면 `RESERVATION_STATE_CONFLICT`(409)다. 만료 시각은 조건에 넣지 않았다. 만료됐지만 아직 정리되지 않은 HELD도 취소로 정리해도 안전하고, 이후 만료 배치는 상태가 이미 CANCELLED라 건드리지 않는다.
- HELD는 크레딧이 차감되기 전이므로 환불·위약금·출입 토큰 revoke·회원 잠금이 모두 필요 없다. 슬롯 삭제, 상태 이력(HELD → CANCELLED), 빈자리 이벤트 기록(`SlotReleasedRecorder`, reason `CANCELLED`)만 같은 트랜잭션에서 한다. 응답은 기존과 같은 `ReservationResponse`이며 `refundAmount = 0`, `penaltyAmount = 0`이다.
- 잠금 순서는 Reservation(UPDATE) → Slot(DELETE)이다. 결제 확정 경로는 슬롯을 건드리지 않으므로 교차 대기가 생기지 않는다.
- 프론트는 `PaymentPage`의 결제 버튼 아래에 "결제 대기 취소" 버튼을 두고 `ConfirmDialog`(위험 버튼)로 한 번 확인한다. 성공하면 내 예약 목록으로 이동한다. 결제 진행 중이거나 시간 만료 상태에서는 비활성화한다.
- 이 취소로 슬롯이 풀리면 outbox → Kafka → 대기자 알림의 기존 파이프라인이 그대로 동작한다.

### B. 내 대기 목록 개선

- 목록을 만들 때 이미 오피스를 조회(`getSpace`)하고 있어 응답의 `imagePath`를 함께 보관하기만 하면 된다. 추가 요청이 없다.
- 항목의 사진·이름·시간 영역 전체를 `Link`로 감싸 `/spaces/{spaceId}`로 이동한다. 대기는 아직 예약이 아니라 "이동할 예약 페이지"가 없으므로, 실제로 예약할 수 있는 오피스 상세로 연결했다. "대기 취소" 버튼은 링크 바깥에 있어 이동하지 않는다.
- 사진이 없거나 로딩에 실패하면 기존 `SpacePhoto`의 자리표시자를 작게 보여 준다.

### C. PG 충전 내역 조회

- 새 API `GET /api/v1/credits/charge-orders?page&size`는 인증 회원 본인의 충전 주문을 최신순 페이지로 돌려준다. 같은 경로의 POST(생성)·`/{orderId}`(단건)와 충돌하지 않는다.
- 상태는 PAID, CONFIRMING, FAILED만 보여 준다. PENDING은 주문만 만들고 결제를 시도하지 않은 건이라 사용자에게 의미가 없고 목록을 어지럽힌다. 서비스가 상태 집합을 레포지토리에 직접 넘겨 필터한다(`findByMemberIdAndStatusInOrderByIdDesc`).
- 응답 항목은 `orderId, amount, status, createdAt, updatedAt`이다. `updatedAt`은 승인·실패 등 마지막 상태 변경 시각이라 화면에서 "충전 시각"으로 쓴다. 기존 `ChargeOrderResponse`를 바꾸지 않고 별도 DTO(`ChargeOrderHistoryResponse`)를 추가해 기존 클라이언트 계약을 깨지 않았다.
- 범위는 "PG로 충전한 내역"에 한정한다. 예약 결제·환불·위약금 같은 크레딧 사용 내역(`credit_transaction`)은 포함하지 않는다.
- 화면은 `/credits/history`(`ChargeHistoryPage`)이며 기존 `Pagination`, `EmptyState`, `Badge`를 재사용한다.

### D. 헤더 메뉴와 마이페이지

- 상단 메뉴(자주 쓰는 흐름 순): 오피스 찾기 → 내 예약 → 내 대기 → 모의 출입. 내 예약·내 대기는 로그인한 일반 회원에게만 보인다(관리자·비로그인 제외). 필터는 `Header.tsx`의 `MEMBER_ONLY_NAV`로 한다.
- 계정 드롭다운(계정·돈·지원 성격): 마이페이지 → 크레딧 충전하기 → 충전 내역 → 내 문의 → (관리자) → 로그아웃.
- 마이페이지는 프로필 카드(아바타·이메일·회원 구분), 보유 크레딧 카드(충전하기·충전 내역 버튼), 2×2 바로가기, 계정 정보 패널(닉네임 수정 로직은 기존 그대로), 붉은 톤의 회원탈퇴 카드로 재구성했다. 관리자에게는 크레딧·바로가기·탈퇴를 숨긴다(기존 `role === 'USER'` 조건 유지).

### E. 약관 체크 버그

- 원인: 토스 `agreement` 위젯은 기본으로 "전체 동의" 상태로 그려지지만, `agreementStatusChange` 이벤트는 사용자가 상태를 바꿀 때만 발생한다. SDK에 현재 상태를 읽는 getter도 없다. 그래서 앱의 `agreed`가 `false`로 남았다.
- 수정: `setupChargeWidgets`가 위젯을 그리고 리스너를 등록한 직후 `onAgreementChange(true)`를 한 번 호출한다. 사용자가 체크를 풀면 이벤트로 `false`가 되어 기존처럼 버튼이 꺼진다. 동의 여부는 `requestPayment` 시점에 토스가 다시 검증하므로 보안상 약화는 없다.

### F. 충전 내역의 결제수단 표시

- 기존에는 결제수단을 어디에도 저장하지 않았다. 토스 승인 응답의 `method`(카드·간편결제·계좌이체 등)와 `easyPay.provider`(토스페이 등)를 `charge_order.payment_method`, `payment_provider`(둘 다 NULL 허용)에 저장한다. 마이그레이션은 `V21__add_payment_method_to_charge_order.sql`이다.
- 돈이 오가는 확정 경로(`completeApproved`)의 시그니처와 트랜잭션은 바꾸지 않았다. 승인 성공 뒤 `ChargeOrderConfirmService`가 별도 트랜잭션 `recordPaymentMethod`를 부르고, 실패해도 경고 로그만 남기고 충전 결과는 그대로 반환한다. 결제수단은 표시용이므로 충전의 정합성보다 우선할 수 없기 때문이다.
- `PgConfirmationResult`에 `paymentMethod`, `paymentProvider`를 추가하고 기존 `approved(paymentKey)` 팩토리는 유지해 기존 호출부와 테스트를 건드리지 않았다.
- 한계: 이 기능 이전에 승인된 주문과 PG 조회 복구 경로(`findByPaymentKey`)로 승인된 주문은 값이 NULL이다. 화면은 NULL이면 결제수단 줄을 숨긴다.
- 화면은 `utils/paymentMethod.ts`가 결제수단을 아이콘과 문구로 바꾼다. 카드 💳, 간편결제 📱(제공사가 있으면 "간편결제 · 토스페이"), 계좌이체 🏦, 가상계좌 🧾, 휴대폰 📞, 상품권 🎁, 모르는 값은 💰와 서버 값 그대로.

## 3. 기각한 대안과 이유

| 대안 | 장점 | 기각한 이유 |
| --- | --- | --- |
| HELD 전용 새 엔드포인트(예: `/reservations/{id}/cancel-hold`) | 의미가 분리됨 | 프론트·문서·테스트가 늘고, 소유자 검사·응답 형식이 기존 취소와 동일 |
| HELD 취소를 만료 서비스(`expireHold`) 재사용 | 슬롯 정리 코드 재사용 | 상태가 EXPIRED로 기록되어 "사용자 취소"와 구분 불가, 이력 의미가 틀어짐 |
| HELD 취소 조건에 만료 시각 포함 | 만료분은 배치가 처리 | 사용자가 만료 직후 눌러도 취소가 막히는 불필요한 409가 생김 |
| 대기 항목에서 예약 페이지로 이동 | 문구에 맞음 | 대기에는 예약 ID가 없음. 오피스 상세가 예약 진입점 |
| 충전 내역에 `credit_transaction` 전체 합치기 | 크레딧 흐름 전체 확인 | 범위가 커지고 사용자 요청은 PG 충전 내역. 별도 화면으로 분리 가능 |
| 충전 내역에 PENDING 포함 | 데이터 완전성 | 결제 시도 안 한 주문이 노출되어 혼란 |
| 약관 상태를 항상 `true`로 고정 | 간단 | 사용자가 해제했을 때 버튼이 꺼져야 하므로 초기값만 설정 |
| 결제수단을 `completeApproved`와 같은 트랜잭션에 저장 | 한 번에 원자적 | 돈이 오가는 검증된 경로의 시그니처가 바뀌고, 표시용 값 실패가 충전 실패로 번질 수 있음 |
| PG 조회(`GET /payments/{key}`)로 결제수단 보강 | 과거 주문도 채울 수 있음 | 이미 받은 승인 응답에 값이 있어 추가 외부 호출이 불필요 |

## 4. Trade-off

| 얻는 것 | 잃는 것(비용·위험) | 완화책 |
| --- | --- | --- |
| 기존 API·응답 재사용으로 변경 최소 | `cancel` 서비스에 분기가 생겨 책임이 조금 늘어남 | HELD 경로를 `cancelHeld`로 분리해 CONFIRMED 경로는 한 줄도 건드리지 않음 |
| 결제와 취소가 조건부 UPDATE로 상호 배타 | 만료 직전 경합에서 사용자가 409를 볼 수 있음 | 프론트가 오류를 표시하고 목록 재조회로 복구 |
| 충전 내역 별도 DTO | 필드 중복 | 기존 응답 계약 보존 |
| 약관 초기값 `true` 가정 | 토스 기본값이 바뀌면 가정이 어긋남 | 결제 요청 시 토스가 재검증하므로 결제는 막힘, 테스트로 초기 호출을 고정 |

## 5. 검증

검증 상태: 프론트는 자동 검증 완료, 백엔드는 이 작업 환경에서 Gradle을 실행할 수 없어 아직 실행 전이다.

### 프론트 (실행 완료)

- `node --test tests/*.test.cjs`: 59개 통과(기존 55 + 신규 4).
- `tsc --noEmit`, ESLint: 오류 없음.
- 신규 `tests/charge-history-and-nav.test.cjs`: 상단 메뉴 순서와 `creditHistory` 경로, `getChargeHistory`의 경로·쿼리(기본 page 0 / size 10), `cancelReservation`의 `POST /reservations/{id}/cancel`.
- `tests/toss-charge-widgets.test.cjs`: 위젯 생성 직후 동의 `true`로 알리고 이후 이벤트로 `false`, `true`가 반영되는지 검증.

### 백엔드 (작성 완료, 로컬 실행 필요)

- 결제수단: `TossPaymentsPgClientTest`(method·easyPay.provider 파싱, 값이 없어도 승인 성공), `ChargeOrderConfirmServiceTest`(승인 뒤 기록 호출 / 기록 실패해도 PAID 반환 / 값이 없으면 기록 생략), 충전 내역 서비스·컨트롤러 테스트의 `paymentMethod`·`paymentProvider` 검증, 프론트 `getPaymentMethodView` 테스트. Flyway V21은 통합 테스트 기동 시 적용된다.
- `ReservationCancelServiceTest`: HELD 취소가 슬롯 삭제·이력·`SlotReleased(CANCELLED)` 기록을 하고 환불·위약금이 없음 / 상태가 바뀐 경우 409이고 슬롯·이벤트에 손대지 않음.
- `OutboxRecordIntegrationTest.heldCancellationReleasesSlotsWithoutRefundAndRecordsOneEvent`: 실제 DB에서 상태 CANCELLED, 슬롯 0, `credit_transaction` 0건, outbox 이벤트 1건.
- `ChargeOrderServiceTest.getMyHistory_excludesPendingOrders`, `ChargeOrderControllerTest`(조회 200, 인증 없음 401), `ChargeOrderRepositoryTest.findHistory_returnsOwnVisibleOrdersNewestFirst`(본인의 PAID만 최신순, PENDING·타인 제외).
- 실행 명령: `backend/`에서 `./gradlew test --tests '*ChargeOrder*' --tests '*ReservationCancel*' --tests '*OutboxRecord*'` (통합 테스트는 로컬 MySQL 필요). 전체 회귀는 `./gradlew test`.

### 수동 확인 체크리스트

1. 결제 화면에서 "결제 대기 취소" → 확인 → 예약 목록 이동, 해당 슬롯이 다시 예약 가능, Kafka가 켜져 있으면 대기자에게 알림.
2. 결제와 취소를 거의 동시에 눌렀을 때 하나만 성공.
3. `/credits/charge`에서 처음부터 결제 버튼이 활성이고, 약관을 풀면 비활성.
4. 충전 내역: 충전 후 목록에 최신순으로 나타나고, 충전 안 한 주문은 안 보임.
5. 내 대기: 사진·이름 클릭 시 오피스 상세로 이동, "대기 취소"는 이동하지 않음.
6. 헤더 순서와 마이페이지 레이아웃(모바일 폭 포함), 관리자 계정에서 내 예약·내 대기·충전 항목이 숨겨지는지.

## 6. 한계와 재검토 조건

- 결제 대기 취소와 결제의 동시성 전용 테스트는 없다(조건부 UPDATE 설계에 의존). 필요하면 `ReservationCancelConcurrencyIntegrationTest` 형식으로 추가한다.
- 대기 목록 링크는 날짜를 넘기지 않아 오피스 상세가 "오늘"로 열린다. 오피스 상세가 쿼리 파라미터(`date`)를 읽도록 확장하면 대기한 날짜로 바로 갈 수 있다(`useSearchParams`는 정적 export에서 Suspense 경계가 필요).
- 충전 내역은 PG 충전만 다룬다. 크레딧 사용·환불까지 보여 주려면 `credit_transaction` 기반의 별도 API가 필요하다.
- 약관 초기 동의 가정은 토스 위젯 기본값(전체 동의)에 의존한다. 토스 SDK가 현재 상태 조회 API를 제공하면 그것으로 교체한다.
- 컴포넌트 렌더링 테스트 환경이 없어 화면(마이페이지, 충전 내역, 대기 목록)은 수동 확인에 의존한다.

## 변경 파일 맵 (AI 참조용)

### 백엔드 (`backend/src/main/java/com/ovengers/slotkey/`)

| 경로 | 변경 |
| --- | --- |
| `reservation/repository/ReservationRepository.java` | `cancelIfHeld` 조건부 UPDATE 추가 |
| `reservation/service/ReservationCancelService.java` | HELD 분기와 `cancelHeld` 추가 |
| `credit/repository/ChargeOrderRepository.java` | `findByMemberIdAndStatusInOrderByIdDesc` 추가 |
| `credit/service/ChargeOrderService.java` | `getMyHistory` 추가 |
| `credit/controller/ChargeOrderController.java` | `GET /credits/charge-orders` 추가 |
| `credit/dto/response/ChargeOrderHistoryResponse.java` | 신규 DTO (`paymentMethod`, `paymentProvider` 포함) |
| `credit/entity/ChargeOrder.java`, `resources/db/migration/V21__add_payment_method_to_charge_order.sql` | 결제수단 컬럼 2개 |
| `credit/pg/PgConfirmationResult.java`, `credit/pg/TossPaymentsPgClient.java` | 승인 응답의 method·easyPay.provider 전달 |
| `credit/service/ChargeOrderConfirmService.java`, `ChargeOrderConfirmTransactionService.java` | 승인 뒤 결제수단 기록(`recordPaymentMethod`, 실패 무시) |

### 백엔드 테스트 (`backend/src/test/kotlin/com/ovengers/slotkey/`)

`reservation/service/ReservationCancelServiceTest.kt`, `reservation/service/OutboxRecordIntegrationTest.kt`, `credit/service/ChargeOrderServiceTest.kt`, `credit/controller/ChargeOrderControllerTest.kt`, `credit/repository/ChargeOrderRepositoryTest.kt`

### 프론트 (`frontend/src/`)

| 경로 | 변경 |
| --- | --- |
| `views/reservation/PaymentPage.tsx` | 결제 대기 취소 버튼과 확인 다이얼로그 |
| `views/waitlist/WaitlistPage.tsx` | 사진 추가, 항목 링크(`/spaces/{id}`) |
| `views/credit/ChargeHistoryPage.tsx`, `app/(user)/credits/history/page.tsx` | 신규 충전 내역 화면(결제수단 아이콘 포함) |
| `utils/paymentMethod.ts` | 결제수단 → 아이콘·문구 변환 |
| `api/creditApi.ts`, `types/api.ts` | `getChargeHistory`, `ChargeOrderHistoryItem` |
| `constants/routePaths.ts` | `creditHistory`, `USER_NAV` 재구성 |
| `components/layout/Header.tsx` | 상단 메뉴 필터(`MEMBER_ONLY_NAV`)와 드롭다운 재배치 |
| `views/member/MyPage.tsx` | 마이페이지 개편(동작 로직 유지) |
| `payments/tossChargeWidgets.ts` | 약관 초기 동의 알림 |
| `styles/global.css` | 대기 행 사진·마이페이지·충전 내역 스타일 |

### 프론트 테스트·문서

`frontend/tests/charge-history-and-nav.test.cjs`(신규), `frontend/tests/toss-charge-widgets.test.cjs`(수정), `docs/api/api-spec.md`(5-4 HELD 취소, 5-0-1-1 충전 내역)

## 면접·발표용 정리

- **30초 설명:** 결제 전 예약을 사용자가 취소할 수 있게 했습니다. 환불할 돈이 없으므로 슬롯 반환과 빈자리 이벤트 기록만 한 트랜잭션에서 하고, 결제와의 경쟁은 둘 다 `status = HELD` 조건부 UPDATE로 걸어 한쪽만 성공하게 했습니다. 취소로 풀린 슬롯은 기존 Outbox → Kafka 파이프라인을 타고 대기자에게 알림이 갑니다.
- **예상 질문과 답:**
  - Q. 결제와 취소가 동시에 오면요? A. 둘 다 같은 예약 행에 조건부 UPDATE를 하므로 행 잠금으로 직렬화되고, 영향 행 1을 얻은 쪽만 성공합니다. 진 쪽은 409입니다.
  - Q. 왜 HELD 취소에 환불 로직이 없나요? A. HELD는 결제 전이라 차감된 크레딧이 없습니다. CONFIRMED 경로(환불·위약금·토큰 revoke)와 분리해 기존 경로를 건드리지 않았습니다.
  - Q. 충전 내역에 PENDING을 뺀 이유는? A. 주문만 만들고 결제하지 않은 시도라 사용자에게 의미가 없어서입니다.
  - Q. 약관 버튼 버그의 원인은? A. 토스 위젯이 기본 동의 상태를 이벤트로 알려 주지 않아 앱 상태와 화면이 어긋났습니다. 초기 상태를 한 번 반영하고, 최종 동의 검증은 토스 결제 요청에 맡겼습니다.
- **검증 근거:** §5의 당시 실행 기록을 참조한다. 해당 기록은 기능 동결 기준의 새 실행 결과가 아니며, 이번 문서 갱신에서 테스트 수치를 재검증하지 않았다.
