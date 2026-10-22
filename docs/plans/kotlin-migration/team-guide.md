# 팀원별 상세 담당과 전환 파일 목록

> 문서 정리: 2026-10-22 · 기존 담당 분담을 바탕으로 공유용으로 정리했습니다.
> 파일 수와 1,048건 테스트 기록은 2026-10-20 `d1a15ec` 기준입니다. 새 전환 결과는 별도로 확인합니다.
> 실제 날짜와 작업 순서는 [요일별 계획](daily/00-overview.md)을 따릅니다.

## 1. 전환 전에 확인할 규칙

1. **한 PR에는 하나의 작은 책임만 담고 `.java`와 `.kt` 동명 파일을 동시에 남기지 않는다.**
   - 파일 전환 시 기존 `.java` 삭제와 새 `.kt` 추가를 같은 커밋에 담아야 컴파일 충돌이 나지 않습니다.
2. **Kotlin 파일 안에서는 Lombok 애너테이션을 절대 사용하지 않는다.** ([Lombok 경계 결정](../../decisions/kotlin-lombok-boundary.md))
   - 미전환 Java 파일은 Lombok을 그대로 두되, **Kotlin으로 바꾼 파일이 아직 Java로 남아 있는 Lombok 클래스의 getter/builder를 호출할 때 문제가 생기면 해당 Java 클래스만 먼저 delombok(또는 함께 Kotlin 전환)** 합니다.
3. **Request/Response DTO는 `data class`로 바꾸되 애너테이션 target(`@field:`, `@get:`)을 명시한다.** ([`data class` 범위 결정](../../decisions/kotlin-data-class-scope.md))
   - 생성자 프로퍼티에 붙는 `@NotBlank`, `@NotNull`, `@Size`, `@Positive`, `@Email`, `@Valid`는 반드시 **`@field:NotBlank`** 형태로 작성해야 Spring `MethodArgumentNotValidException` 검증이 기존과 동일하게 동작합니다.
   - 아직 Java로 남아 있는 호출부가 Java `record` 스타일 접근자(`dto.spaceId()`)를 호출한다면, DTO에 **`@JvmRecord data class`**를 붙이거나 같은 PR에서 Java 호출부를 함께 수정합니다.
4. **JPA `@Entity`에는 `data class`를 절대 쓰지 않고 일반 `class`를 사용한다.** ([JPA 엔티티 open 결정](../../decisions/kotlin-jpa-entity-open.md))
   - `kotlin-jpa`(no-arg) + `allopen` 플러그인이 프록시/기본 생성자를 처리합니다.
   - `equals`/`hashCode`는 기존 엔티티의 동등성 규칙을 유지하고, 지연 로딩(`FetchType.LAZY`) 필드를 `toString()`이나 `equals()`에 새로 포함하지 않습니다.
5. **트랜잭션 전파(`REQUIRES_NEW`, `MANDATORY`)와 잠금 순서, 외부 호출 분리 구조를 임의로 합치지 않는다.**
   - Kotlin으로 문법만 옮기며, Spring 프록시가 동작하도록 `@Transactional` 서비스 클래스·메서드 구조와 별도 빈 분리(예: PG 준비/호출/확정, Outbox 기록/발행, 로그인 시도/출입 거절 로그 `REQUIRES_NEW`)를 그대로 유지합니다.

---

## 2. 담당 기능과 파일 수

전체 운영 Java 파일 **293개**(`SlotKeyApplication.java` 포함)를 기존 담당 도메인 기준으로 배분하고, 공통 패키지(`global` 25개 + `SlotKeyApplication` 1개)와 대단위 도메인(`reservation`/`credit`/`auth`)의 작업량 균형을 맞춘 분담안입니다.

| 담당자 | 주요 담당 패키지 (파일 수) | `global` 공통 분담 (제안) | 합계 (운영 / 테스트) | 핵심 난이도·검증 포인트 |
| --- | --- | --- | --- | --- |
| **김재철**<br>(`@Lemnoideae`) | • `space` (29)<br>• `audit` (9)<br>• `stats` (22) | • `global/common` (4: `BaseTimeEntity`, `ApiResponse`, `PageResponse`, `ResponseStatus`)<br>• `global/config` & `error` 일부 (11) | **75개** / 테스트 39개 | `Space.version`의 수동 증가·결제 시 비교와 공간 비관적 잠금, `@AssertTrue`, 멀티파트 5MB 업로드, `AuditLog` `MANDATORY`, `stats` JDBC 집계 쿼리·스케줄러 |
| **박창현**<br>(`@pch112233456-a11y`) | • `access` (19) | • `global/security` (6: `AuthPrincipal`, `CurrentMember*`, `CustomAuthenticationFilter`, `JwtProvider`, `JwtUtil`) | **25개** / 테스트 9개 | 체크인/재입장 시간 경계(`DoorAccessTimePolicy`), `InactiveMemberAccessLogService` (`REQUIRES_NEW`), 보안 필터 체인 |
| **백한비**<br>(`@jkidse14`) | • `credit` 원장·잔액·만료·지급 (22)<br>• `reservation` 상태 이력 (`ReservationStatusHistory*` 4) | • `global/idempotency` (4: `IdempotencyKey*`, `IdempotencyService`) | **30개** / 테스트 14개 | `CreditBalanceRepository` 조건부 차감/증가 쿼리, `CreditTransaction` 원장 불변식, `IdempotencyKey` 해시·응답 캐싱 |
| **이태호**<br>(`@anton061311`) | • `reservation` 본체 (36)<br>• `credit` PG 충전·복구·웹훅 (20)<br>• `waitlist` (9) · `outbox` (7) · `notification` (11) | • `step-0` `build.gradle` + `ReservationRefundPolicy` 파일럿, `SlotKeyApplication` (1) | **84개** / 테스트 59개 | 예약 2단계 HOLD·비관적 락 순서, PG 트랜잭션 빈 분리·웹훅·복구, Outbox 저장/Kafka 발행·`processed_event` 멱등 소비 |
| **천종원**<br>(`@vvipia`) | • `auth` (45)<br>• `member` (22)<br>• `inquiry` (12) | *(박창현과 `global/security` 필터·토큰 경계 상호 리뷰)* | **79개** / 테스트 22개 | `LocalCredential` (`@MapsId`+`LAZY`), `AuthSession`·`RefreshToken` 회전/재사용 감지, `JdbcLoginAttemptStore` (`REQUIRES_NEW`+`FOR UPDATE`), 탈퇴·잔액 소멸 동시성 |

> **지원이 필요할 때**: 이태호(84개)·천종원(79개)의 파일 수가 많으므로, 박창현·백한비가 본인 작업을 마친 뒤 문의·대기열·Outbox·알림의 작은 파일 묶음을 지원할 수 있습니다. PR 전에 담당 파일을 정하고, 화요일(10/27) 완료 가능 여부는 실제 진행량과 테스트 결과로 판단합니다.

---

## 3. 담당자별 전환 파일

---

### 👤 1) 김재철 (`@Lemnoideae`) — `space` · `audit` · `stats` (+ `global/common·config·error`)

#### A. 단계별 전환 순서 및 대상 파일
1. **`step-1` (공통 기반·순수 정책·Enum)**
   - `global/common`: `ResponseStatus`, `ApiResponse`, `PageResponse`
   - `global/error`: `ErrorCode`, `BusinessException`, `GlobalExceptionHandler`
   - `space`: `SpaceRegion`, `SpaceStatus`, `SpaceOperatingHoursPolicy` (중첩 `record SlotWindow`는 `@JvmRecord data class` 적용 또는 `SpaceSlotAvailabilityService` 호출부 동시 확인)
   - `audit`: `AuditAction`, `AuditTargetType`
   - `stats`: `RankingMetric`, `RankingPeriodType`, `RankingStatus`
2. **`step-2` (DTO·인가·이미지 스토리지)**
   - `space/dto`: `SpaceCreateRequest`, `SpaceUpdateRequest`, `SpaceSearchCondition`, `SpaceSlotSearchCondition`, `SpaceResponse`, `SpaceDetailResponse`, `SlotResponse`, `SpaceSlotAvailabilityResponse`
   - `space/authorization & image`: `SpaceAuthorizationService`, `ImageResource`, `StoredImage`, `SpaceImageProperties`, `SpaceImageStorage`
   - `audit/dto`: `AdminAuditLogSearchCondition`, `AuditLogResponse`
   - `stats/dto`: `LocationRankingItem`, `LocationRankingResponse`, `PublicLocationRankingResponse`, `PublicSpaceRankingResponse`, `SpaceRankingItem`, `SpaceRankingResponse`, `StatusCountsResponse`
3. **`step-3` & `step-4` (컨트롤러·단순 엔티티·통계 JDBC)**
   - 컨트롤러: `SpaceController`, `AdminSpaceController`, `SpaceImageController`, `AdminAuditLogController`, `StatsController`, `AdminStatsController`
   - 공통 영속 기반: `BaseTimeEntity` (`@MappedSuperclass`; 기존 필드/getter와 Auditing을 먼저 검증)
   - `audit` 엔티티/레포/서비스: `AuditLog`, `AuditLogRepository`, `AuditLogService`, `AuditLogQueryService`
   - `stats` 엔티티/레포/서비스: `SpaceRankingSnapshot`, `SpaceRankingAggregate`, `SpaceRankingAggregationRepository`, `SpaceRankingSnapshotRepository`, `ReservationStatusCountRepository`, `SpaceRankingQueryService`, `SpaceRankingConfirmService`, `SpaceRankingBackfillService`, `SpaceRankingScheduler`, `ReservationStatusCountService`
4. **`step-5` (`Space` 핵심 엔티티·서비스)**
   - `Space` 엔티티 (`var version: Int = 0`; 기존 수동 증가·비교와 기본값 0 유지), `SpaceRepository`. 현재 Java 코드에 JPA `@Version`은 없으며 언어 전환에서 새로 추가하지 않는다.
   - `SpaceQueryService`, `SpaceSlotAvailabilityService`, `DefaultOccupiedSlotProvider`, `AdminSpaceService`, `AdminSpaceImageUpdateService`, `SpaceImageService`, `ImageUpdateResult`

#### B. 도메인별 주의사항 (체크리스트)
- [ ] **`SpaceCreateRequest` / `SpaceUpdateRequest`**: `@AssertTrue`가 붙은 운영시간/가격 검증 메서드는 Kotlin 전환 시 `@get:AssertTrue` 또는 `fun is...(): Boolean` 시그니처가 Bean Validation에서 정상 호출되는지 MockMvc로 확인한다.
- [ ] **`SpaceSearchCondition` / `AdminAuditLogSearchCondition`**: `@ModelAttribute` 쿼리 파라미터 바인딩이 깨지지 않도록 기본값·생성자 바인딩을 테스트한다.
- [ ] **`AuditLogService`**: `@Transactional(propagation = Propagation.MANDATORY)` 전파 설정을 그대로 유지해 부모 트랜잭션 없이 단독 호출 시 예외가 발생하는 기존 계약을 보존한다 (`AuditLogServiceTransactionTest`).
- [ ] **`stats` JDBC/집계**: `long` 순매출과 `int` 건수 타입, `[from, to)` 반열림 구간, KST `00:01` 스케줄러 확정 및 재확정 시 삭제 후 재삽입 로직을 변경하지 않는다.

#### C. 필수 검증 명령
```bash
cd backend
./gradlew test --tests '*Space*' --tests '*AuditLog*' --tests '*Ranking*' --tests '*Stats*' --tests 'com.ovengers.slotkey.global.error.*'
```

---

### 👤 2) 박창현 (`@pch112233456-a11y`) — `access` (+ `global/security`)

#### A. 단계별 전환 순서 및 대상 파일
1. **`step-1` (순수 정책·Enum·지원 유틸)**
   - `access/entity` (Enum): `AccessDenyReason`, `AccessResult`
   - `access/policy`: `DoorAccessTimePolicy` (최초 체크인 `[startAt, startAt + 15m]`, 재입장 `(checkedInAt, endAt)` 경계 및 `Optional<AccessDenyReason>` 또는 nullable 반환 호환 유지)
   - `access/support`: `AccessTokenGenerator`, `AccessTokenHasher`
2. **`step-2` (DTO·인가·보안 Principal)**
   - `access/dto`: `DoorAccessVerifyRequest` (`@field:NotBlank`), `DoorAccessTokenResponse`, `DoorAccessVerifyResponse`, `DoorAccessLogResponse`
   - `access/authorization`: `DoorAccessAuthorizationService`
   - `global/security`: `AuthPrincipal`, `CurrentMember`, `CurrentMemberArgumentResolver`, `JwtProvider`, `JwtUtil`
3. **`step-3` & `step-4` (컨트롤러·보안 필터)**
   - `access/controller`: `DoorAccessController`
   - `global/security`: `CustomAuthenticationFilter` (비활성 회원 차단 및 에러 응답 JSON 포맷 유지)
4. **`step-5` (`access` 엔티티·서비스)**
   - `access/entity`: `DoorAccessToken`, `DoorAccessLog` (`data class` 금지, 일반 `class`로 기존 동등성 유지)
   - `access/repository`: `DoorAccessTokenRepository`, `DoorAccessLogRepository`
   - `access/service`: `DoorAccessLogService`, `InactiveMemberAccessLogService` (`REQUIRES_NEW` 유지!), `DoorAccessTokenService`, `DoorAccessVerificationService`

#### B. 도메인별 주의사항 (체크리스트)
- [ ] **`InactiveMemberAccessLogService`**: 비활성 회원이 출입을 시도해 `403 ACCOUNT_INACTIVE` 예외로 본 트랜잭션이 롤백되더라도 거절 로그는 DB에 남아야 하므로 `@Transactional(propagation = Propagation.REQUIRES_NEW)` 경계를 절대 합치지 않는다 (`InactiveMemberAccessIntegrationTest`).
- [ ] **`DoorAccessVerificationService`**: 최초 체크인 시 예약 상태가 `CONFIRMED → IN_USE`로 전이되고 `ReservationStatusHistory`가 기록되는 흐름과, 체크아웃 후 토큰이 즉시 폐기(`revoked`)되는 불변식을 보존한다.
- [ ] **`CustomAuthenticationFilter`**: `AuthSession` 검증(`sid` 클레임) 및 비활성 회원 차단 시 `ErrorCode.ACCOUNT_INACTIVE`의 지원 이메일 포함 메시지가 그대로 내려가는지 확인한다.

#### C. 필수 검증 명령
```bash
cd backend
./gradlew test --tests '*DoorAccess*' --tests '*InactiveMemberAccess*' --tests '*Security*'
```

---

### 👤 3) 백한비 (`@jkidse14`) — `credit` (원장·잔액·만료·지급) · `ReservationStatusHistory` (+ `global/idempotency`)

#### A. 단계별 전환 순서 및 대상 파일
1. **`step-1` (Enum)**
   - `credit/entity` (Enum): `CreditTransactionType`, `ChargeOrderStatus`
2. **`step-2` (멱등성·크레딧 기본 DTO/인터페이스)**
   - `global/idempotency`: `IdempotencyKeyHeaderResolver` (DB를 사용하는 나머지는 아래 단계)
   - `credit/service` (인터페이스): `CreditService`
   - `reservation/dto/response`: `ReservationStatusHistoryResponse`
3. **`step-4` (중복 요청·상태 이력·저장소)**
   - `global/idempotency`: `IdempotencyKey`, `IdempotencyKeyRepository`, `IdempotencyService`
   - `reservation`: `ReservationStatusHistory`, `ReservationStatusHistoryRepository`, `ReservationStatusHistoryService`
   - `credit`: `CreditTransactionRepository`, `CreditBalanceRepository` (`CreditTransaction` 엔티티는 아래 핵심 전환 단계)
4. **`step-5` (크레딧 잔액 차감/증가·만료·지급 서비스)**
   - `credit/entity`: `CreditTransaction`
   - `credit/service`: `CreditServiceImpl`, `DefaultCreditService`, `CreditGrantService`, `CreditExpirationService`

> **여유 일정 시 지원 범위**: `inquiry` 도메인(12개 파일) 또는 `waitlist` 도메인(9개 파일)의 DTO·컨트롤러 전환 지원

#### B. 도메인별 주의사항 (체크리스트)
- [ ] **`CreditBalanceRepository`**: DB 조건부 업데이트(`balance >= :amount` 등)의 반환값(영향 행 수 `int`)과 `clearAutomatically`/`flushAutomatically` 설정을 정확히 유지한다.
- [ ] **금액 오버플로 및 부호 불변식**: `long` 주문 금액에서 `int` 크레딧 잔액으로 변환할 때 상한 검사(`Math.toIntExact` 등)를 유지한다. 초기 잔액/지급 내역을 포함한 원장 합계와 `Member.balance`, 각 행의 `balanceAfter`가 맞아야 한다 (`CreditBalanceConcurrencyIntegrationTest`, `CreditTransactionRepositoryTest`).
- [ ] **`IdempotencyService`**: 동일 `Idempotency-Key` 재요청 시 캐싱된 응답을 반환하고 다른 요청 본문과 키가 재사용될 때 충돌을 감지하는 로직을 유지한다.

#### C. 필수 검증 명령
```bash
cd backend
./gradlew test --tests '*Credit*' --tests '*Idempotency*' --tests '*ReservationStatusHistory*'
```

---

### 👤 4) 이태호 (`@anton061311`) — `reservation` · `credit`(PG 충전·복구·웹훅) · `waitlist` · `outbox` · `notification`

#### A. 단계별 전환 순서 및 대상 파일
1. **`step-0` (공통 혼용 빌드 오픈 + 파일럿 1건)**
   - `backend/build.gradle` (Kotlin `1.9.25`, `kotlin-spring`, `kotlin-jpa`, `allOpen`, `kotlin-reflect`, `jackson-module-kotlin`)
   - `reservation/policy/ReservationRefundPolicy.kt` + `ReservationRefundPolicyTest.kt`
2. **`step-1` (순수 정책·Enum·예외·단순 VO)**
   - `reservation`: `ReservationTimePolicy`, `ReservationStatus`, `ReservationTargetInfo`
   - `credit/pg`: `PgErrorClassifier` (`TossPaymentsProperties`·`TossPaymentsPgConfiguration`은 컨트롤러/설정 단계)
   - `outbox` & `waitlist`: `OutboxPublishException`, `WaitlistStatus`
3. **`step-2` (DTO·외부 연동 모델·어댑터)**
   - `reservation/dto`: `ReservationCreateRequest`, `ReservationPayRequest`, `ReservationExtendRequest`, `ForceCancelRequest`, `ReservationSearchCondition`, `AdminReservationSearchCondition`, `ReservationResponse`, `ReservationDetailResponse`, `ReservationListResponse`, `AdminReservationResponse`, `AdminReservationDetailResponse`, `DoorAccessLogResponse`
   - `credit/dto & pg & webhook`: `ChargeOrderCreateRequest`, `ChargeOrderConfirmRequest`, `ChargeOrderResponse`, `ChargeOrderConfirmResponse`, `ChargeOrderHistoryResponse`, `PgConfirmRequest`, `PgConfirmationResult`, `PgPaymentLookupResult`, `ChargeOrderPreparation`, `ChargeOrderRecoveryTarget`, `PgWebhookEvent`, `PgWebhookEventResult`
   - `waitlist/dto` & `notification/dto`: `WaitlistApplyRequest`, `WaitlistEntryResponse`, `NotificationResponse`, `ReadAllResponse`, `UnreadCountResponse`
   - `reservation/adapter`: `ReservationOccupiedSlotAdapter`, `WaitlistOccupancyAdapter`
4. **`step-3` & `step-4` (컨트롤러·Outbox/알림/대기열 엔티티 및 소비 파이프라인)**
   - 설정: `TossPaymentsProperties`, `TossPaymentsPgConfiguration`
   - 컨트롤러: `ReservationController`, `AdminReservationController`, `ChargeOrderController`, `TossWebhookController`, `WaitlistController`, `NotificationController`
   - `outbox`: `OutboxEvent`, `OutboxEventRepository`, `SlotReleasedRecorder`, `OutboxPublisher`, `KafkaOutboxPublisher`, `OutboxRelay`
   - `notification`: `Notification`, `NotificationRepository`, `NotificationQueryService`, `ProcessedEventStore`, `SlotReleasedHandler`, `SlotReleasedListener`, `KafkaConsumerConfig`
   - `waitlist`: `WaitlistEntry` (`active` nullable + `@DynamicUpdate` 보존!), `WaitlistEntryRepository`, `ReservationOccupancyProvider`, `ReservationRangeProvider`, `WaitlistService`
5. **`step-5` (`reservation`·`credit(PG)` 핵심 엔티티·트랜잭션 서비스·스케줄러)**
   - `reservation`: `Reservation`, `ReservationSlot`, `ReservationRepository`, `ReservationSlotRepository`, `PricingService`, `ReservationSlotService`, `ReservationHoldService`, `ReservationPaymentConfirmService`, `ReservationCancelService`, `ReservationExtendService`, `ReservationCheckOutService`, `ReservationQueryService`, `AdminReservationService`, `ReservationHoldExpirationService`, `ReservationBatchProcessor`, `ReservationCompletionScheduler`
   - `credit (PG)`: `ChargeOrder`, `ChargeOrderRepository`, `PgClient`, `TossPaymentsPgClient`, `ChargeOrderService`, `ChargeOrderConfirmTransactionService`, `ChargeOrderConfirmService`, `ChargeOrderRecoveryTransactionService`, `ChargeOrderRecoveryProcessor`, `ChargeOrderRecoveryScheduler`, `PgWebhookEventStore`, `PgWebhookConfirmTransactionService`, `PgWebhookEventProcessor`, `PgWebhookEventScheduler`, `TossWebhookReceiver`

#### B. 도메인별 주의사항 (체크리스트)
- [ ] **`ReservationRefundPolicy`**: `totalAmount * 50 / 100` 정수 나눗셈과 취소 시각 경계(정확히 시작 1시간 전 = 전액 환불, 그 직후~시작 시각 = 50% 위약금)를 1원 오차 없이 유지한다.
- [ ] **예약 잠금 순서 및 2단계 트랜잭션**: 공간 공유 락(`PESSIMISTIC_READ`) + 슬롯 유니크 제약(`uk_space_slot_start`), 예약→회원 잠금 순서, `HOLD` 생성과 결제 확정(`pay`)의 트랜잭션 분리를 그대로 보존한다.
- [ ] **PG 충전 외부 호출 분리**: `ChargeOrderConfirmService`(외부 PG HTTP 호출, 비트랜잭션)와 `ChargeOrderConfirmTransactionService`(DB 잠금·원장 기록·상태 전이) 빈을 절대 한 클래스로 합치지 않는다(외부 네트워크 대기 중 DB 커넥션/락 점유 방지).
- [ ] **Outbox & Kafka 알림**: 예약 취소/만료 시 `SlotReleasedRecorder`가 본 트랜잭션(`MANDATORY`) 안에서 `OutboxEvent`를 함께 저장하고, `OutboxRelay`는 트랜잭션 밖에서 Kafka로 전송하며, 소비 측 `SlotReleasedHandler`는 `processed_event` 기록과 `Notification` 저장을 한 트랜잭션으로 묶어 동반 롤백되게 유지한다.
- [ ] **`WaitlistEntry`**: `active` 컬럼이 활성 시 `1`, 비활성 시 `NULL`을 사용해 유니크 제약을 구현하므로 nullable 타입(`Int?` 또는 `Boolean?`)과 `@DynamicUpdate`를 반드시 유지한다 (`WaitlistEntryLostUpdateTest`).

#### C. 필수 검증 명령
```bash
cd backend
./gradlew test --tests '*Reservation*' --tests '*ChargeOrder*' --tests '*Webhook*' --tests '*Outbox*' --tests '*SlotReleased*' --tests '*Waitlist*' --tests '*Notification*'
```

---

### 👤 5) 천종원 (`@vvipia`) — `auth` · `member` · `inquiry`

#### A. 단계별 전환 순서 및 대상 파일
1. **`step-1` (Enum·순수 검증기/생성기)**
   - `member/entity` (Enum): `MemberRole`, `MemberStatus`
   - `auth/entity` (Enum): `VerificationPurpose`
   - `auth/mail & service`: `SmtpMailCredentialsValidator`, `VerificationSecretGenerator`, `VerificationSecretHasher`
   - `inquiry/entity` (Enum): `InquiryStatus`
2. **`step-2` (DTO·인가 서비스)**
   - `auth/dto/internal`: `ChallengeIssueResult`, `IssuedRefreshToken`, `LoginResult`, `RefreshResult`, `VerificationCheckResult`
   - `auth/dto/request & response`: `LoginRequest`, `SignupRequest`, `PasswordResetRequest`, `VerificationIssueRequest`, `VerificationCheckRequest`, `LoginResponse`, `SignupResponse`, `VerificationIssueResponse`, `VerificationCheckResponse`
   - `member/dto`: `AdminMemberSearchCondition`, `MemberCreditGrantRequest`, `MemberStatusChangeRequest`, `MemberUpdateRequest`, `MemberWithdrawalRequest`, `MemberResponse`, `MemberStatusChangeResponse`, `AdminMemberResponse`, `AdminMemberDetailResponse`, `AdminMemberReservationResponse`, `AdminMemberCreditTransactionResponse`, `AdminMemberStatusHistoryResponse`
   - `inquiry/dto`: `InquiryCreateRequest`, `InquiryUpdateRequest`, `InquiryAnswerRequest`, `InquiryResponse`
   - `member/authorization` & `inquiry/authorization`: `MemberAuthorizationService`, `InquiryAuthorizationService`
3. **`step-3` & `step-4` (`inquiry` 전체 완료 + `auth`/`member` 컨트롤러·메일·로그인 제한)**
   - `inquiry`: `Inquiry`, `InquiryRepository`, `InquiryService`, `AdminInquiryService`, `InquiryController`, `AdminInquiryController` *(※ [01-test-baseline](01-test-baseline.md)에 따라 회원 문의 소유권/수정 제한 테스트 보강 병행)*
   - `auth/controller` & `member/controller`: `AuthController`, `VerificationController`, `MemberController`, `AdminMemberController`
   - `auth/mail` & `auth/ratelimit`: `VerificationMailSender`, `SmtpVerificationMailSender`, `LoginAttemptStore`, `JdbcLoginAttemptStore` (`REQUIRES_NEW` + `SELECT ... FOR UPDATE` 유지!), `LoginRateLimitService`
4. **`step-5` (`auth`·`member` 핵심 엔티티·세션/토큰/인증 증표 서비스)**
   - `member`: `Member`, `MemberRepository`, `MemberService`, `AdminMemberService`, `MemberWithdrawalService`
   - `auth/entity & repository`: `LocalCredential` (`@MapsId` + `FetchType.LAZY` 필수 확인!), `AuthSession`, `RefreshToken` (`member`·`authSession` `LAZY`, `consumedAt` 재사용 감지), `VerificationChallenge`, `VerificationGrant`, `VerificationGuard` 및 각 Repository 6개
   - `auth/service`: `VerificationGuardService`, `VerificationChallengeService`, `VerificationCheckService`, `VerificationGrantService` (`MANDATORY`), `AuthSessionService`, `RefreshTokenService`, `AuthService`, `PasswordResetService`

#### B. 도메인별 주의사항 (체크리스트)
- [ ] **`LocalCredential` (`@MapsId`)**: `Member`와 PK를 공유하는 `@OneToOne(fetch = FetchType.LAZY) @MapsId` 매핑이므로, Kotlin 전환 시 생성자/필드 초기화 순서와 지연 로딩 프록시가 깨지지 않는지 `EntityManager.flush()/clear()` 후 재조회로 확인한다.
- [ ] **`AuthSession` & `RefreshToken` (Auth V2)**: RT 회전 시 `consumed_at` 기록, 이미 소비된 RT 재사용 시 해당 `AuthSession` 전체 폐기, 쿠키 속성(`HttpOnly`, `Path`, `Max-Age`)과 `429 Too Many Requests`(`Retry-After` 헤더) 응답 계약을 그대로 유지한다 (`V2SecurityRedTest`, `AuthSessionMigrationIntegrationTest`, `LoginRateLimitIntegrationTest`).
- [ ] **`JdbcLoginAttemptStore`**: 로그인 실패 횟수 차감/증가는 로그인 실패 예외와 무관하게 커밋되어야 하므로 `REQUIRES_NEW` 전파와 `SELECT ... FOR UPDATE` 잠금을 유지한다.
- [ ] **`VerificationGrantService`**: 회원가입/비밀번호 재설정 본 트랜잭션과 함께 롤백되어야 하므로 `MANDATORY` 전파를 유지한다.

#### C. 필수 검증 명령
```bash
cd backend
./gradlew test --tests '*Auth*' --tests '*Verification*' --tests '*LoginRateLimit*' --tests '*RefreshToken*' --tests '*Member*' --tests '*Inquiry*'
```

---

## 4. PR과 빌드 확인

1. **PR 크기 기준**:
   - 한 PR은 **1개 정책** 또는 **같은 도메인의 밀접한 파일 묶음(5~15개 파일 내외)** 으로 제한합니다.
   - DTO 전환 PR과 엔티티/트랜잭션 전환 PR을 분리하면 리뷰와 롤백이 훨씬 빨라집니다.
2. **PR 올리기 전 로컬 필수 3단계 체크**:
   ```bash
   # 1) 담당 도메인 테스트 빠른 실행
   cd backend && ./gradlew test --tests '*담당도메인*'

   # 2) 전체 클린 빌드 (이번 커밋의 실제 테스트 수와 결과 기록)
   # JDK 21을 선택하고 실행합니다.
   env -u SPRING_MAIL_USERNAME -u SPRING_MAIL_PASSWORD -u VERIFICATION_MAIL_FROM -u SLOTKEY_KAFKA_ENABLED ./gradlew --no-daemon clean build

   # 3) DTO·컨트롤러 변경 시 프론트엔드 계약 검증 (백엔드만 바뀐 PR은 frontend CI가 자동 실행되지 않음)
   cd ../frontend && node --test tests/*.cjs && npx tsc --noEmit
   ```
3. **문제 발생 시 롤백 원칙**:
   - 각 PR은 DB 스키마(Flyway V1~V26)나 외부 JSON/Kafka 메시지 계약을 바꾸지 않으므로, 회귀가 발견되면 해당 PR만 단일 `git revert`하여 즉시 직전 상태로 복구합니다.
