# 문서-코드 정합성 검증 step-3: 절대 규칙 1–7과 테스트 매핑

- 작업일: 2026-10-27
- 역할: 검증자(Claude, Sonnet 5.5). 코드와 테스트는 수정하지 않았다. 이 단계는 읽기 전용 조사이며 레포 파일은 이 보고서만 추가한다.
- 대상: `AGENTS.md` "절대 어기면 안 되는 규칙" 1–7
- 질문: 각 규칙을 **테스트가 실제로 검증하는가**, 규칙 문장이 **현재 코드·다른 문서와 맞는가**
- 이전 단계: [step-1](step-1.md), [step-2](step-2.md). 이 브랜치는 step-2 브랜치 위에 쌓았다.

## 한눈에 보는 결과

| 규칙 | 내용 | 테스트 검증 | 규칙 문장 vs 현재 구현 |
| --- | --- | --- | --- |
| 1 | 30분 단위, `price_per_slot` | **충분** | 일치 |
| 2 | HOLD → 결제 2단계 | **충분** (동시성·경계 포함) | 일치 |
| 3 | Access JWT 클레임·재조회 없음 | 인증 테스트는 충분 | **불일치 (규칙 문장이 낡음)** |
| 4 | 관리자 권한 ≠ 소유권 | **부분** (관리자 역할 테스트 없음) | 일치 |
| 5 | 예약당 활성 토큰 1개 (DB 제약) | **간접** (동시성 테스트로 확인) | 일치 |
| 6 | `Idempotency-Key` | **충분** | 규칙 문장이 불완전 |
| 7 | 오케스트레이션 방향 | **없음** (강제하는 테스트 없음) | 제어 흐름은 일치, 의존성은 양방향 |

테스트 검증: 직접 확인 3개(1, 2, 6), 부분·간접 3개(3 문장 불일치 제외 시 충분하나 아래 참조, 4, 5), 없음 1개(7). 규칙 문장 자체의 문제 3건(3, 6, 7).

**이 표는 테스트가 존재하는지와 무엇을 검증하는지를 읽어서 만들었다. 전체 테스트를 실행하지 않았으므로 "그 테스트들이 지금 통과한다"는 보장은 없다.** `AGENTS.md`는 검증자가 전체 테스트를 직접 실행하도록 하는데, 이 단계 범위(문서 대조)에서는 하지 않았다. 아래 "사용자 결정이 필요한 질문" 참조.

## 규칙별 근거

테스트 위치는 이 브랜치(커밋된 `dev` 기준) 기준이다. Java → Kotlin 전환이 진행 중이라 파일 경로와 확장자는 바뀔 수 있어 클래스·메서드 이름을 함께 적는다.

### 규칙 1: 30분 단위, `price_per_slot`

| 검증하는 내용 | 테스트 |
| --- | --- |
| 30분 단위가 아닌 예약 시각 거절 (`INVALID_RESERVATION_TIME`) | `ReservationTimePolicyTest.validate_notSlotAligned_throwsInvalidTime` |
| 연장 시각도 30분 단위, 운영시간 초과·날짜 넘김 거절 | `ReservationTimePolicyTest.validateExtension_*`, `ReservationExtendServiceTest.extend_unalignedEndTime_throwsException` |
| 대기 신청도 같은 시간 정책 | `WaitlistServiceIntegrationTest.apply_notSlotAligned_rejected` |
| 공간 조회의 30분 단위 위반 400 | `SpaceControllerTest.getSpacesPage_nonAlignedTime_returns400ValidationFailed`, `SpaceQueryServiceTest.getSpacesPage_nonAlignedTime_throwsValidationFailed` |
| 운영시간 30분 경계, 슬롯 생성 | `SpaceOperatingHoursPolicyTest`, `AdminSpaceServiceTest.createSpace_notAlignedOperatingHours_throwsException`, `SpaceTest.validateOperatingHours_throwsException` |
| 요금 100원 단위 (`INVALID_PRICE_UNIT`) | `AdminSpaceServiceTest.createSpace_invalidPriceUnit_throwsException`, `updateSpace_invalidPriceUnit_*`, `SpaceTest.updateDetail_invalidPrice_throwsException` |
| 슬롯 수 계산 (2시간 = 4슬롯) | `PricingServiceTest.calculateSlotCount_*` |

확인: `price_per_hour` / `pricePerHour` 표현은 `backend/src/main`과 `frontend`에 없다.

참고: `PricingServiceTest.calculateSlotCount_notSlotAligned_flooredDown`은 30분 배수가 아닌 구간을 **내림 계산**한다(거절하지 않음). 거절은 앞단 `ReservationTimePolicy`가 맡으므로 모순은 아니지만, 가격 계산만 단독으로 호출되면 조용히 버린다.

### 규칙 2: HOLD → 결제

| 규칙 내용 | 테스트 |
| --- | --- |
| HOLD 생성, `hold_expires_at` = now + 10분, 가격 스냅샷 | `ReservationHoldServiceTest.createHold_success_savesHeldReservationAndHistory` |
| 슬롯 충돌은 409, 이력·데이터 롤백 | `createHold_slotConflict_propagatesException`, `ReservationConcurrencyTest.createHold_concurrentRequests_onlyOneSucceeds`(20개 동시, 1건 성공) |
| 일부만 겹치는 동시 요청 | `ReservationPartialOverlapConcurrencyTest.createHold_partialOverlap_onlyOneSucceeds` |
| 만료된 HELD를 정리하고 슬롯 재확보 | `ReservationSlotRepositoryTest.secureSlots_exactExpiredHold_cleansUpAndSecuresNewSlotInRealDb` |
| `space.version` 불일치 | `ReservationPaymentConfirmServiceTest.confirm_spaceVersionMismatch_throwsException` |
| 잔액 부족 422, 상태 전이 없음 | `confirm_insufficientBalance_propagatesExceptionWithoutTransition`, `ReservationPaymentConcurrencyTest.pay_differentReservationsConcurrently_preventsOverdraft`(조건부 UPDATE로 초과 차감 방지) |
| 홀드 만료 후 결제 409, 예약은 `HELD`로 남음 | `confirm_holdExpired_throwsException`(단위), `ReservationHttpSupplementGoldenTest`의 `expired-pay` 케이스(HTTP 409, 상태 `HELD`, 잔액 불변, 슬롯 유지, 원장·멱등키 0건), `ReservationPaymentConcurrencyTest.confirmIfHeldAndNotExpired_atExactExpiry_doesNotConfirm`(만료 정각 경계) |

### 규칙 3: Access JWT

| 규칙 내용 | 상태 |
| --- | --- |
| JWT에 `id`, `email`, `role` 저장 | 맞음. `JwtProvider.kt`가 `id`·`email`·`role`·`sid`를 넣는다. `JwtProviderTest`, `JwtUtilTest`가 서명·만료·클레임을 검증한다 |
| "회원 DB를 매 요청 재조회하지 않는다. 정지·탈퇴 및 역할 변경은 기존 AT에 즉시 반영되지 않는다" | **틀림.** `CustomAuthenticationFilter.kt`(62~86줄)가 JWT 검증 뒤 `authSessionService.validate(sessionId, memberId)`로 DB 세션의 소유자·폐기·만료와 회원 `ACTIVE` 상태를 확인하고, 비활성이면 `INVALID_ACCESS_TOKEN`(401)을 반환한다 |

규칙 3의 해당 문장은 "2026-09-30 사용자 확인: 기존 JWT 클레임 기반 인증 유지"를 근거로 하는데, 이후 PR #350(DB 세션)이 이를 바꿨다. 다른 문서는 이미 새 동작을 기술한다.

- `docs/api/api-spec.md` 22줄, §2-5: "인증 필터는 JWT 검증 뒤 `AuthSessionService`로 DB 세션의 소유자·폐기·만료와 회원 `ACTIVE` 상태를 확인한다"
- `docs/decisions/authentication-policy.md` 133줄: "JWT 검증 후 DB 세션의 회원 귀속·폐기·만료 및 회원 ACTIVE 상태 검증"

현재 동작을 검증하는 테스트: `AuthControllerTest.legacyAccessTokenWithoutSessionIsRejected`, `sessionOwnedByAnotherMemberIsRejected`, `expiredSessionRejectsAccessToken`, `DoorAccessSecurityIntegrationTest`(만료 세션·세션 소유자 불일치 차단), `InactiveMemberAccessIntegrationTest`(정지 회원의 기존 JWT 차단). 즉 **테스트는 새 동작을 지키고 있고, 낡은 것은 규칙 문장이다.**

역할(`role`)은 필터가 세션 검증에 `memberId`만 넘기므로 여전히 JWT 클레임을 따른다(`validate`에 role을 넘기지 않는 것을 확인, 필터 전체는 읽지 않았다). 역할 변경 API는 없다.

### 규칙 4: 관리자 권한 ≠ 소유권

| 규칙 내용 | 테스트 |
| --- | --- |
| 타인 예약 취소·연장·체크아웃 거절 (`FORBIDDEN_NOT_OWNER`) | `ReservationCancelServiceTest.cancel_notOwner_throwsException`, `ReservationExtendServiceTest.extend_notOwner_throwsException`, `ReservationCheckOutServiceTest.shouldRejectCheckOutWhenMemberIsNotOwner`, `ReservationPaymentConfirmServiceTest.confirm_notOwner_*` |
| 타인의 도어 토큰 발급·폐기·로그 조회 403 | `DoorAccessSecurityIntegrationTest.nonOwnerCannotManageDoorAccess`(HTTP, 기존 토큰 유지까지 확인) |
| 일반 사용자는 강제 취소 불가 | `ReservationDtoContractTest.userCannotForceCancelOthersReservation` |
| 강제 취소 경로 자체 | `AdminReservationServiceTest.forceCancel_*`, `AdminReservationServiceIntegrationTest`, `AdminReservationConcurrencyIntegrationTest` |

**공백:** 위 "타인" 테스트의 호출자는 모두 일반 회원이다. **ADMIN 역할 JWT로** `POST /reservations/{id}/cancel`, `/extend`, `/pay`, `/check-out`, `/door-token`을 호출해 403이 나오는지 확인하는 테스트는 없다. 서비스가 역할이 아니라 회원 ID로 소유권을 비교하므로 관리자도 막힌다는 것은 코드 구조상 맞지만, "관리자도 불가"라는 규칙이 테스트로 고정돼 있지는 않다. `ReservationHttpSupplementGoldenTest`의 관리자는 강제 취소 성공 경로에만 쓰인다.

### 규칙 5: 예약당 활성 도어 토큰 1개

| 규칙 내용 | 테스트 |
| --- | --- |
| 재발급 전 기존 토큰 폐기 | `DoorAccessTokenServiceTest.shouldRevokeActiveTokenWhenReissuing` |
| 동시 최초 발급에도 활성 토큰 1개, 진 요청은 409 | `DoorAccessTokenConcurrencyTest.issue_firstIssueConcurrently_leavesExactlyOneActiveToken` |
| 동시 재발급에도 활성 1개, 나머지는 `REISSUED`로 폐기 | `issue_reissueConcurrently_leavesExactlyOneActiveToken` |
| 스키마 | `uk_door_access_token_active_reservation UNIQUE(active_reservation_id)`(V5), step-2에서 확인 |

**공백(간접 검증):** DB 제약이 직접 발동하는 것을 확인하는 테스트(같은 예약에 활성 토큰 두 행을 직접 INSERT하면 위반)는 없다. 동시성 테스트가 실제 DB로 "정확히 1개"를 확인하고 클래스 주석이 제약을 근거로 들지만, 서비스 로직만으로도 같은 결과가 나올 수 있어 제약이 마지막 방어선으로 작동하는지는 분리해 보지 못한다.

### 규칙 6: `Idempotency-Key`

| 규칙 내용 | 테스트 |
| --- | --- |
| 결제에서 헤더 누락 400 `IDEMPOTENCY_KEY_REQUIRED`, 결제 미수행 | `ReservationControllerTest.shouldRejectPaymentWhenIdempotencyKeyIsMissing`, `GlobalExceptionHandlerTest.shouldReturnIdempotencyKeyRequiredWhenHeaderIsMissing`, `ReservationPaymentConfirmServiceTest.confirm_blankKey_throwsException` |
| 같은 키 재요청은 최초 결과, 재처리 금지 | `ReservationPaymentConfirmServiceTest.confirm_cachedKey_returnsCachedResponseWithoutCharging`, `ReservationPaymentConcurrencyTest.pay_sameKeyRepeated_chargesOnce`, `pay_sameKeyConcurrently_chargesOnceAndReplays` |
| 같은 키라도 다른 예약이면 각각 결제 | `pay_sameKeyForDifferentReservations_chargesBoth` |
| 실패(잔액 부족)는 키를 소진하지 않음 | `pay_insufficientBalance_doesNotConsumeIdempotencyKey` |
| HOLD 생성은 대상 아님 | 대상이 아니므로 별도 테스트 없음(헤더 없이 동작하는 것은 다른 컨트롤러 테스트가 암묵적으로 통과) |

규칙 문장이 불완전하다. 규칙 6은 `POST /reservations/{id}/pay`만 필수라고 하지만 `docs/api/api-spec.md` 30줄은 **충전 승인 `POST /credits/charge-orders/confirm`도 필수**(누락 시 400 `IDEMPOTENCY_KEY_REQUIRED`)라고 하며 테스트도 있다(`ChargeOrderControllerTest.confirm_withoutIdempotencyKey_returnsBadRequest`, `ChargeOrderConfirmIntegrationTest.confirm_sameIdempotencyKey_returnsPaidAndCreditsOnce`).

### 규칙 7: 오케스트레이션 방향 `Reservation → Payment`

| 확인 | 결과 |
| --- | --- |
| 이 방향을 강제하는 테스트(아키텍처 테스트) | **없음.** ArchUnit 등 의존성 검사가 빌드에 없다 |
| 제어 흐름 | 맞음. `ReservationPaymentConfirmService`가 `CreditService` 인터페이스(포트)를 호출한다. `reservation` 패키지가 `credit`에서 가져오는 것은 `CreditService`뿐이다 |
| 패키지 의존성 | 반대 방향 import가 있다. `credit`의 `CreditServiceImpl.kt:11-12`가 `Reservation`·`ReservationRepository`를, `CreditTransaction.kt:4`가 `Reservation`을 가져온다(원장이 예약을 참조하는 데이터 의존) |
| 규칙 문장 | "Payment Service"는 현재 코드에 없다. `payment` 테이블은 V4에서 비어 있고 결제는 `credit` 도메인(원장)이 맡는다 |

제어 흐름의 방향은 지켜지고 있으나 이를 지키는 장치는 코드 리뷰뿐이다. 패키지 수준에서는 양방향 의존이 있어 "반대 방향으로 설계하지 않는다"를 import 기준으로 읽으면 이미 어긋나 있다. 규칙이 막으려는 것이 호출(제어) 방향인지 패키지 의존인지 정해야 한다.

## 불일치 및 공백 목록

| # | 종류 | 내용 | 어느 쪽 문제 | 제안 |
| --- | --- | --- | --- | --- |
| 1 | 문서 | `AGENTS.md` 규칙 3 문장이 현재 인증(DB 세션 검증, 정지·탈퇴 즉시 반영)과 다르다 | 문서가 낡음 | 규칙 3 문장 개정 (사용자 결정 필요, 아래) |
| 2 | 문서 | 규칙 6이 충전 승인 `Idempotency-Key`를 빠뜨렸다 | 문서가 불완전 | 규칙 6에 `POST /credits/charge-orders/confirm` 추가 |
| 3 | 테스트 | 규칙 4: 관리자 역할 JWT로 사용자 전용 엔드포인트를 호출하는 테스트 없음 | 테스트 공백 | 관리자 JWT로 cancel/extend/pay/check-out/door-token이 403 `FORBIDDEN_NOT_OWNER`인지 확인하는 테스트 추가 |
| 4 | 테스트 | 규칙 5: DB UNIQUE 제약 직접 발동 테스트 없음 | 테스트 공백(간접 검증은 있음) | 활성 토큰 직접 INSERT 두 번 시 위반을 확인하는 테스트 추가 |
| 5 | 테스트·문서 | 규칙 7: 의존 방향을 강제하는 장치 없음, 패키지 의존은 양방향 | 규칙 정의가 모호 | 규칙을 제어 흐름 기준으로 명확히 하거나 아키텍처 테스트 도입 여부 결정 |

## 변경 파일

- `docs/testing/doc-verification/step-3.md` (이 보고서, 신규). 나머지 파일은 변경하지 않았다.

## 실행한 명령과 결과

- 테스트 인덱스 작성: 테스트 소스(`backend/src/test`, Java 121개·Kotlin 84개 파일)에서 `@Test`/`@ParameterizedTest` 메서드 1,144개를 이름·`@DisplayName`과 함께 추출해 규칙별 키워드로 검색했다.
- 확인한 코드: `CustomAuthenticationFilter.kt`, `JwtProvider.kt`, `CreditService.kt`, `CreditServiceImpl.kt`의 import, `ReservationHttpSupplementGoldenTest.kt`·`DoorAccessSecurityIntegrationTest.kt`·`ReservationPaymentConcurrencyTest.kt`의 본문 일부.
- **테스트는 실행하지 않았다.**

## 가정한 것과 확인하지 못한 것

- 테스트 이름과 `@DisplayName`, 일부 본문을 읽어 판단했다. 이름이 그럴듯해도 본문이 다른 것을 검증할 수 있고, 전부의 본문을 읽지는 않았다(특히 단위 테스트는 목 기반이라 실제 동작을 보장하지 않는다).
- 이 브랜치는 `origin/dev` 기준 커밋 상태다. 같은 시기에 다른 세션이 공유 체크아웃에서 Java 테스트를 Kotlin으로 옮기는 작업을 하고 있어, 그 변경(미커밋)은 반영하지 않았다.
- 규칙 3의 "역할 변경은 기존 AT에 반영되지 않는다"는 필터 일부(62~86줄)만 읽고 판단했다.
- 규칙 1~2의 프론트엔드 측 검증, 규칙 7의 런타임 의존(Spring 빈 그래프)은 보지 않았다.

## 관련 결정 문서

- `docs/decisions/authentication-policy.md` (규칙 3과 비교). 변경 없음.
- 규칙 2·4·5·6의 근거인 `docs/core-domain-decisions.md`는 이 단계에서 읽지 않았다(AGENTS.md 문장을 기준으로 삼았다).

## 리뷰 체크리스트

- [ ] 규칙 3: `CustomAuthenticationFilter.kt` 전체를 읽고 "역할은 클레임 기준" 서술이 맞는지 확인
- [ ] 규칙 3 개정 문장 합의 (아래 질문 1)
- [ ] 규칙 4 공백: 관리자 JWT로 사용자 전용 엔드포인트를 호출했을 때 실제로 403인지 먼저 수동 확인 (테스트 추가 전에 현재 동작이 규칙과 맞는지 확인)
- [ ] 규칙 5 공백: 직접 INSERT 테스트를 추가할지 결정
- [ ] 규칙 7: 제어 흐름 기준으로 개정할지, 아키텍처 테스트를 도입할지 결정 (도입 시 `docs/decisions/`에 결정 문서 선행)
- [ ] 전체 테스트를 실행해 이 매핑이 가리키는 테스트가 통과하는지 확인

## 사용자 결정이 필요한 질문

1. 규칙 3 문장을 어떻게 고칠까? 이 문장은 "2026-09-30 사용자 확인"이 붙은 결정이라 제가 임의로 바꾸지 않았다. 제안: "Access JWT에는 `id`, `email`, `role`, `sid`를 저장한다. 인증 필터는 서명·만료 검증 뒤 DB 세션과 회원 `ACTIVE` 상태를 확인한다. 정지·탈퇴·로그아웃·비밀번호 재설정은 기존 AT에 즉시 반영되고 역할은 JWT 클레임을 따른다." 참고로 `AGENTS.md`는 `.gitignore` 대상이라 개정해도 PR에 들어가지 않는다.
2. 규칙 4·5의 테스트 공백은 구현자 세션에서 메우는 것이 맞다(이 세션은 검증자). 인계 항목으로 남길까?
3. 전체 테스트 실행(검증자의 확인 항목 1번)을 다음 단계로 할까? Docker/DB가 필요한지, 소요 시간은 확인하지 않았다.

## 발표용 숫자 (step-1~3 누적)

- 대조: 엔드포인트 65, 에러 코드 65, 테이블 25, FK 23, 절대 규칙 7.
- 문서 쪽 오류: api-spec 누락 2건, erd 누락 3건, AGENTS.md 에러 코드 3건·규칙 문장 2건(규칙 3 낡음, 규칙 6 불완전).
- 테스트 쪽 공백: 규칙 4(관리자 역할), 5(DB 제약 직접), 7(방향 강제) 3건. 규칙 1·2·6은 단위·통합·동시성·경계 테스트로 확인.
- 가장 큰 발견: **규칙 3 문장이 구현과 반대인데도 테스트는 새 동작을 지키고 있었다.** 문서가 코드보다 늦게 갱신된 사례로, 문서를 기준으로 구현하면 인증을 되돌리는 방향으로 오해할 수 있었다.
