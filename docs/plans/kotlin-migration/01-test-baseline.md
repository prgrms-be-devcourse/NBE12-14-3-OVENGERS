# 01. 테스트 기준선과 문의 API 검증

**목적:** Kotlin 전환 전 동작을 재실행 가능한 기준선으로 만들고, 문의의 회원 소유권·상태 전이·DB 검증을 보강한다. [상위 계획](00-kotlin-migration-plan.md)의 첫 번째 P0 작업이다. 소스 대조 기준은 2026-10-20 / `d1a15eca3228c8b1b1f9260fc7a686860d2b1e44`(`d1a15ec`)이며, 2026-10-20 기준선 실행은 **실행 스위트 135개·테스트 1,048개 전원 통과**다.

## 현재 근거와 수정 대상

- [2026-09-24 테스트 기록](../../testing/test-results.md)의 57개 클래스·354개 통과와 2026-10-15 CI 실패는 과거 스냅샷이다. 현재 기준선은 2026-10-20 `d1a15ec`의 **1,048/1,048 통과**(실패 0·오류 0·스킵 0)이며, 상세 환경·명령은 [테스트 결과 §0-0](../../testing/test-results.md)에 있다.
- 테스트 Java 소스는 **148개 파일 = 실행 스위트 135 + `@Test`가 없는 빈 클래스 5 + 지원·픽스처 8**이다. `find`/`grep` 파일 수를 실행 클래스 수로 세지 않고, 실행 수는 `backend/build/test-results/test/TEST-*.xml`로 집계한다.
- [`AdminInquiryControllerTest`](../../../backend/src/test/kotlin/com/ovengers/slotkey/inquiry/controller/AdminInquiryControllerTest.kt)에 `@Test` 11개가 있다. 실제 보안 필터를 포함한 WebMvcTest로 관리자 목록·필터·기본 페이징·상세/404·답변 위임과 인증/권한을 확인한다. 서비스가 MockBean이므로 영속성 검증은 아니다.
- 빈 테스트 5개는 여전히 `SlotKeyApplicationTests`, `AuthServiceTest`, `MemberAuthorizationServiceTest`, `MockPaymentGatewayTest`, `PaymentServiceTest`다. 뒤의 두 파일은 기존 `payment` 패키지의 빈 클래스이며, 현재 `credit/pg`의 PG 구현·`FakePgClient`·충전 테스트와 구분해 정리 여부를 판단한다. PG 미도입으로 설명하지 않는다. *(2026-10-27 갱신: `SlotKeyApplicationTests`, `MockPaymentGatewayTest`, `PaymentServiceTest` 3개는 빈 클래스여서 삭제했다. 남아 있던 `AuthServiceTest`, `MemberAuthorizationServiceTest`도 auth·member 테스트 Kotlin 전환 때 삭제해 빈 테스트는 없다. 위 148개 집계는 2026-10-20 기준 스냅샷이다.)*
- 문의 대상: [`InquiryController`](../../../backend/src/main/kotlin/com/ovengers/slotkey/inquiry/controller/InquiryController.kt), [`AdminInquiryController`](../../../backend/src/main/kotlin/com/ovengers/slotkey/inquiry/controller/AdminInquiryController.kt), [`InquiryService`](../../../backend/src/main/kotlin/com/ovengers/slotkey/inquiry/service/InquiryService.kt), [`Inquiry`](../../../backend/src/main/kotlin/com/ovengers/slotkey/inquiry/entity/Inquiry.kt).

## `16b9ead..d1a15ec` 추가·보강 테스트

새로 추가된 테스트 클래스 10개다. 파일 존재와 실행 통과를 구분하며, 아래는 2026-10-20 XML에서 실제 실행된 스위트다.

| 영역 | 신규 클래스 | 확인하는 계약 |
| --- | --- | --- |
| Auth V2·세션 | [`AuthSessionMigrationIntegrationTest`](../../../backend/src/test/kotlin/com/ovengers/slotkey/auth/migration/AuthSessionMigrationIntegrationTest.kt), [`LoginRateLimitIntegrationTest`](../../../backend/src/test/kotlin/com/ovengers/slotkey/auth/ratelimit/LoginRateLimitIntegrationTest.kt) | 기존 RT의 세션 연결·마이그레이션, 로그인 10회/15분 제한과 429 `Retry-After`. |
| 관리자 회원 | [`AdminMemberDetailControllerTest`](../../../backend/src/test/kotlin/com/ovengers/slotkey/member/controller/AdminMemberDetailControllerTest.kt), [`AdminMemberDetailServiceTest`](../../../backend/src/test/kotlin/com/ovengers/slotkey/member/service/AdminMemberDetailServiceTest.kt) | 상세·크레딧·예약·상태 이력 조회, 404/403, 요약 통계. |
| 비활성 회원 출입 | [`InactiveMemberAccessIntegrationTest`](../../../backend/src/test/java/com/ovengers/slotkey/access/service/InactiveMemberAccessIntegrationTest.java), [`InactiveMemberAccessFilterTest`](../../../backend/src/test/java/com/ovengers/slotkey/global/security/InactiveMemberAccessFilterTest.java) | 비활성 회원 출입 거절과 `REQUIRES_NEW` 거절 로그. |
| 기타 계약 | [`GlobalExceptionHandlerTest`](../../../backend/src/test/kotlin/com/ovengers/slotkey/global/error/GlobalExceptionHandlerTest.kt), [`ReservationControllerTest`](../../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/controller/ReservationControllerTest.kt), [`CreditTransactionRepositoryTest`](../../../backend/src/test/kotlin/com/ovengers/slotkey/credit/repository/CreditTransactionRepositoryTest.kt), [`ReservationMemberCountRepositoryTest`](../../../backend/src/test/kotlin/com/ovengers/slotkey/reservation/repository/ReservationMemberCountRepositoryTest.kt) | 오류 응답 매핑, 예약 결제 멱등키·검증, 원장·예약 인원 집계 쿼리. |

같은 구간에서 `AuthControllerTest`(25), `V2SecurityRedTest`(15, 전원 활성), `RefreshTokenServiceTest`, `PasswordResetIntegrationTest`, `AdminMemberControllerTest`, `AdminMemberServiceIntegrationTest`, `AdminInquiryControllerTest`, `NotificationReadIntegrationTest` 등이 보강됐다. `V2SecurityRedTest`에 `@Disabled`는 없으며, 2026-10-15 CI에서 실패했던 11건은 현재 활성 회귀 안전망이다.

## 기존 테스트와 보강 작업 대응표

아래의 “재실행”은 테스트 소스가 존재한다는 뜻이다. 각 클래스의 assertion을 확인하고 전환 커밋에서 실행한 뒤 통과 여부를 기록한다. 표에 없는 경로도 변경 파일의 직접 호출부에서 확인한다.

| 영역 | 우선 재실행할 기존 테스트 | 보강/신규 작성 판단 |
| --- | --- | --- |
| 문의 관리자 HTTP | `AdminInquiryControllerTest` | 기존 11개를 재사용. 회원 생성·목록/상세 소유권·수정, 답변/거절의 DB 상태와 입력 검증을 신규/보강한다. |
| Auth V2·세션 | `AuthControllerTest`(25), `V2SecurityRedTest`(15), `AuthSessionMigrationIntegrationTest`, `LoginRateLimitIntegrationTest`, `RefreshTokenServiceTest` | `sid` 기반 세션 검증, RT 회전·`consumed_at` 재사용 감지·세션 전체 폐기, 로그인 429 `Retry-After`, 기존 RT의 세션 연결 마이그레이션을 재사용한다. `V2SecurityRedTest`는 전원 활성이다. |
| 관리자 회원 상세·이력 | `AdminMemberDetailControllerTest`, `AdminMemberDetailServiceTest`, `AdminMemberControllerTest`, `AdminMemberServiceIntegrationTest` | 상세 요약·크레딧/예약/상태 이력 조회, 404/403, 감사 로그 동반 커밋을 확인한다. |
| 비활성 회원 출입 | `InactiveMemberAccessIntegrationTest`, `InactiveMemberAccessFilterTest` | 비활성 회원 출입 거절과 `REQUIRES_NEW` 거절 로그의 커밋·실패 경계를 확인한다. |
| 예약 결제·취소·잠금 | `ReservationPaymentConcurrencyTest`, `ReservationCancelConcurrencyTest`, `ReservationCancelConcurrencyIntegrationTest`, `ReservationExtendHoldConcurrencyIntegrationTest`, `MemberWithdrawalHoldConcurrencyIntegrationTest`, `ReservationMemberCountRepositoryTest` | 동시 취소의 REFUND 행 수/합계 검증은 이미 있다. 서로 다른 예약의 잔액 초과 동시 차감, 위약금 구간 두 원장 행, 상태·시간 경계의 누락 assertion을 보강한다. |
| PG 충전·복구·웹훅 | `ChargeOrderConfirmIntegrationTest`, `ChargeOrderRecoveryProcessorIntegrationTest`, `TossWebhookIntegrationTest`, `PgWebhookEventProcessorIntegrationTest`, `CreditTransactionRepositoryTest` | 충전 1회·원장 합계·PG 호출 트랜잭션 부재·확정 실패가 기존 대상이다. 테스트별 경합/실패 조합을 [02](02-money-and-concurrency.md)에 매핑한다. |
| 인증 | `VerificationFlowIntegrationTest`, `EmailSignupIntegrationTest`, `PasswordResetIntegrationTest`, `VerificationMemberStatusIntegrationTest`, `RefreshTokenServiceTest` | 인증번호 동시 확인·잠금, 증표 소비/롤백/목적·이메일 불일치·만료 등 변경 경로의 assertion을 대조하고 빠진 조합만 보강한다. |
| Outbox·대기·알림 | `OutboxRecordIntegrationTest`, `OutboxRelayIntegrationTest`, `WaitlistApplyConcurrencyTest`, `WaitlistEntryLostUpdateTest`, `SlotReleasedListenerTest`, `SlotReleasedHandlerTest`, `SlotReleasedHandlerConcurrencyTest`, `NotificationReadIntegrationTest` | 취소/이벤트 동반 롤백, 중복 처리, 알림 읽음, nullable active와 lost update 검증을 재사용한다. 기존 payload→전환 후 코드 호환 사례를 고정한다. |
| 통계 | `SpaceRankingConfirmServiceIntegrationTest`, `SpaceRankingQueryServiceIntegrationTest`, `SpaceRankingBackfillServiceIntegrationTest`, `StatsControllerIntegrationTest`, `AdminStatsControllerIntegrationTest` | 기간/자정·00:01 경계, long 순매출/JDBC 결과, 재확정·지역 스냅샷을 재사용한다. 일반/관리자 응답을 구분한다. |

클래스는 `backend/src/test/java/com/ovengers/slotkey/` 또는 Kotlin으로 옮긴 경우 `backend/src/test/kotlin/com/ovengers/slotkey/` 아래에 있다. *(2026-10-27 확인: 인증·Auth V2·관리자 회원·문의 행의 클래스는 모두 Kotlin으로 옮겨 `src/test/kotlin`에 있다.)* 추가·보강이 필요한지는 [02](02-money-and-concurrency.md)·[03](03-api-contract.md)·[06](06-jpa-and-proxies.md)의 계약별로 판정하고, 이미 존재하는 테스트를 신규 작성 수에 넣지 않는다. *(2026-10-27 확인: 통계 행의 `SpaceRankingConfirmServiceIntegrationTest`, `SpaceRankingBackfillServiceIntegrationTest`는 Kotlin으로 옮겨 `src/test/kotlin`에 있다. 같은 변경에서 `SpaceRankingSchedulerTest`, `SpaceRankingSchemaIntegrationTest`, 공용 `SettableClockConfig`도 옮겼다.)*

## 2026-10-22 합의: 새 기준선과 테스트 수 자동 검사

- 10/20 `d1a15ec`의 1,048건은 `adca3ae`에서 Kotlin 플러그인이 들어오기 전 값이다. 현재 `dev` 커밋에서 **CI의 `clean build`로 새 기준선을 측정**해 [테스트 결과](../../testing/test-results.md)에 커밋·날짜·테스트 수·스킵을 기록한다. 로컬 실행은 디버깅 보조이며 기준선은 CI 값을 쓴다. *(2026-10-27 참고: 로컬 `clean test` 200개 클래스·1,444개 통과 기록이 [§0-LATEST](../../testing/test-results.md)에 있으나 CI 값이 아니다.)*
- [`backend-ci.yml`](../../../.github/workflows/backend-ci.yml)에 `backend/build/test-results/test/*.xml`의 `tests` 합계가 기준선 이상이고 `skipped`가 0인지 검사하는 단계를 추가한다. 기준선 측정 PR에 함께 넣는다. 근거: [테스트 보호 장치](../../decisions/kotlin-test-guardrails.md).
- 테스트도 운영 코드와 같은 PR에서 Kotlin으로 옮기되 판정(assert) 줄은 바꾸지 않는다.

## P0 보강 시점

- **순수 정책·유틸 시범 전환은 P0 추가 보강 없이 시작할 수 있다.** `ReservationRefundPolicy`는 `ReservationCancelServiceTest`·`ReservationCancelConcurrencyTest`가 행위를 간접 고정하고 있으며, 첫 전환 PR에서 경계값을 직접 고정하는 `ReservationRefundPolicyTest`를 추가한다. `ReservationTimePolicyTest`(14), `DoorAccessTimePolicyTest`(9), `SpaceOperatingHoursPolicyTest`(5)는 이미 있다.
- **현재 3차 저장소에서 엔티티·DB를 사용하는 서비스 전환 전까지 아래 P0를 완료한다.** 문의 회원 소유권·DB 상태, 서로 다른 예약의 잔액 초과 동시 차감, 위약금 구간의 REFUND/PENALTY 두 원장 행, Auth V2 세션·RT 재사용, 관리자 상세·이력 계약, 비활성 출입 로그의 독립 트랜잭션을 대상으로 한다. 날짜별 배치는 [요일별 계획](daily/00-overview.md)을 따른다.
- P0 보강도 기능 동결 커밋의 언어·트랜잭션 경계를 바꾸지 않는다. 보강 테스트가 현재 Java 구현의 결함을 드러내면 전환과 분리해 수정 여부를 결정한다.

## 실행 순서

1. **환경과 결과 고정.** 2026-10-20 `d1a15ec` 기준선은 완료됐다(1,048/1,048). 이후 전환 PR마다 Docker가 실행 중인 JDK 21 환경에서 `cd backend && ./gradlew clean build`를 다시 실행하고 날짜·커밋·테스트 건수·실패 원인을 새 기록으로 남긴다. 로컬 셸에 `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, `VERIFICATION_MAIL_FROM`, `SLOTKEY_KAFKA_ENABLED`가 있으면 테스트 프로필 기본값을 덮어쓰므로 해제하고 실행한다(§0-0 재현 조건). [`backend-ci.yml`](../../../.github/workflows/backend-ci.yml)의 `build` 작업 결과는 로컬과 나란히 기록하고, `deploy`의 `-x test`를 테스트 통과 증거로 세지 않는다.
2. **기존 테스트와 빈 클래스 판정.** 위 대응표에서 재실행/보강/신규 작성 목록을 확정한다. 빈 클래스는 기존 검증과 중복되는지 확인해 목적을 보강하거나 제거한다. 현재 PG 테스트를 빈 payment 클래스와 함께 지우지 않는다. 테스트 수만 늘리기 위한 형식적 메서드는 추가하지 않는다.
3. **문의 검증 보강.** *(2026-10-27 확인: 아직 미완료. 문의 테스트는 관리자 WebMvcTest인 `AdminInquiryControllerTest` 1개뿐이며, 회원용 HTTP·서비스/DB 테스트는 없다.)* 기존 관리자 WebMvcTest를 재사용하고 `backend/src/test/kotlin/com/ovengers/slotkey/inquiry/`에 빠진 회원 HTTP/보안·서비스/DB 검증을 추가한다. 실 DB 테스트는 기존 `IntegrationTestSupport` 또는 `@ActiveProfiles("test")`와 `MySqlTestContainerConfig`를 사용한다. 답변 시각에는 고정 `Clock`을 사용한다.
4. **응답과 DB 상태를 함께 확인.** `MockMvc`에서 인증·권한·`ApiResponse`를 확인하고, 커밋 후 DB를 재조회한다. 거절된 요청은 저장 부작용이 없어야 한다.
5. **기록 갱신.** 새 테스트의 시나리오를 `docs/testing/test-strategy.md`에 연결하고 전체 실행 결과를 `docs/testing/test-results.md`에 추가한다. 로컬과 CI 결과를 구분한다.

## 문의 기능의 최소 시나리오

| 경로 | 확인할 계약 | 작업 구분 |
| --- | --- | --- |
| `POST /api/v1/inquiries` | 로그인 사용자의 문의가 `WAITING`으로 생성된다. 빈 제목/내용 및 길이 초과는 400이며 저장되지 않는다. | 회원 HTTP·DB 검증 신규 작성 |
| `GET /api/v1/inquiries`, `GET /api/v1/inquiries/{id}` | 본인 항목만 조회된다. 타인 상세는 `FORBIDDEN_NOT_OWNER`, 없는 ID는 `INQUIRY_NOT_FOUND`다. | 회원 소유권·페이징 검증 신규 작성 |
| `PATCH /api/v1/inquiries/{id}` | 본인 `WAITING`만 수정된다. 타인 또는 `ANSWERED`는 거절되고 원문이 유지된다. | 성공/거절 HTTP·DB 검증 신규 작성 |
| `GET /api/v1/admin/inquiries`, `GET /api/v1/admin/inquiries/{id}` | 관리자 목록·필터·기본 page 0/size 20·상세/404, 목록의 일반 회원 403·미인증 401을 유지한다. | 기존 HTTP 테스트 재실행, 실제 DB 필터/권한 누락 보강 |
| `POST /api/v1/admin/inquiries/{id}/answer` | 답변 후 `ANSWERED`·답변자 ID/시각이 저장된다. 일반 회원은 403, 빈 답변은 400이다. | 기존 성공 JSON/위임 재실행, 거절·상태/DB 검증 보강 |

## 완료 조건

- 2026-10-20 `d1a15ec` 기준선은 `cd backend && ./gradlew clean build` 종료 코드 0과 1,048/1,048 통과로 확보됐다. 이후 각 전환 PR도 Docker 사용 환경에서 같은 명령이 통과하고 새 실행 기록이 남아야 한다.
- 문의의 권한·검증·상태 전이가 HTTP 응답과 DB 재조회로 확인된다.
- 빈 클래스 5개 각각의 테스트 목적 또는 제거 근거가 정리된다.
- PR/`dev` 푸시의 `build` 결과를 확인한다. self-hosted `deploy`는 `DEPLOY_ENABLED == 'true'`인 `dev` 푸시에만 실행된다. `step-6` 통과 후 배포를 활성화해 실행한 경우 clean 재빌드·systemd 재시작·기동 로그를 별도 확인한다. 배포 미실행과 테스트 실패를 구분하고, `-x test`를 테스트 통과로 세지 않는다.
- 현재 `spring.test.context.cache.maxSize=2`를 유지하고, Fake PG/메일·Kafka OFF 기반 검증을 실제 외부 서비스/브로커 E2E와 구분한다. **기준선 실행만으로 P0 보강 완료를 주장하지 않는다.**

다음 작업: [금전·경합](02-money-and-concurrency.md), [API 계약](03-api-contract.md).
