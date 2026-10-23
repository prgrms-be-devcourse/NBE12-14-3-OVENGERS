# 07. 단계별 Kotlin 전환과 정리

> 날짜별 작업은 [요일별 계획](daily/00-overview.md), 파일별 담당은 [팀원별 상세 담당](team-guide.md)을 확인합니다. 전체 문서의 읽는 순서는 [문서 안내](README.md)에 있습니다.

**목적:** P0/P1 검증을 통과한 뒤 코드를 작은 도메인 단위로 옮기고, Java 상호 운용 설정을 마지막에 정리한다. [상위 계획](00-kotlin-migration-plan.md)의 P2 작업이다. 2026-10-20 / `d1a15ec`의 도메인 범위와 기준선(1,048/1,048)을 반영했으며 실제 전환 착수·완료 기록은 아니다.

## 레포지토리 범위

- **3차 새 레포(`NBE12-14-3-OVENGERS`)**: `step-0`(혼용 빌드·`ReservationRefundPolicy` 시범)부터 `step-6`까지 전 단계를 이 레포에서 진행한다. 기준점은 2차 레포 `dev`의 `v1.0.0`(코드는 `d1a15ec`와 동일)이며, 이 문서의 단계 ID와 롤백 경계를 그대로 유지한다.
- **2차 레포(`NBE12-14-2-OVENGERS`)**: 전환 작업을 하지 않는 Java 기준선 보관용이다. 과거 PR·커밋·CI 링크는 이 레포의 기록이다.

## 첫 전환 PR 범위

`step-0`은 혼용 빌드와 `ReservationRefundPolicy` 시범 전환까지 수행한다. 이어 `step-1`에서 나머지 순수 정책을 한 PR에 하나씩 전환한다.

| 순서 | 대상 | 의존·호출부 | 대응 테스트 | 주의점 |
| --- | --- | --- | --- | --- |
| 1 (step-0) | [`ReservationRefundPolicy`](../../../backend/src/main/java/com/ovengers/slotkey/reservation/policy/ReservationRefundPolicy.java) (32줄) | Spring/JPA/Lombok 없음. 정적 `calculatePenaltyAmount` 1개, [`ReservationCancelService`](../../../backend/src/main/java/com/ovengers/slotkey/reservation/service/ReservationCancelService.java) 1곳 호출 | **`ReservationRefundPolicyTest` 신규 추가**, `ReservationCancelServiceTest`, `ReservationCancelConcurrencyTest`, `ReservationCancelConcurrencyIntegrationTest` | 정확히 1시간 전=전액, 그 직후=50%, 시작 정각=50%. `totalAmount * 50 / 100`의 정수 나눗셈(홀수 금액)을 그대로 보존한다. Java 정적 호출은 `object`+`@JvmStatic` 또는 호출부 수정. |
| 2 (step-1) | [`ReservationTimePolicy`](../../../backend/src/main/java/com/ovengers/slotkey/reservation/policy/ReservationTimePolicy.java) (76줄) | Spring/JPA/Lombok 없음. 정적 `validate`·`validateExtension` | `ReservationTimePolicyTest`(14) | 30분 정렬·같은 날짜·운영시간·과거 시각 오류 코드를 유지한다. |
| 3 (step-1) | [`DoorAccessTimePolicy`](../../../backend/src/main/java/com/ovengers/slotkey/access/policy/DoorAccessTimePolicy.java) (90줄) | `@Component` 인스턴스 메서드, `AccessDenyReason` enum 의존 | `DoorAccessTimePolicyTest`(9) | 최초 체크인 `[start, start+15m]`, 재입장 `(checkedInAt, end)` 경계와 `Optional<AccessDenyReason>` 반환을 유지한다. |
| 4 (step-1) | [`SpaceOperatingHoursPolicy`](../../../backend/src/main/kotlin/com/ovengers/slotkey/space/policy/SpaceOperatingHoursPolicy.kt) (82줄) | `@Component` 인스턴스 메서드, 중첩 `record SlotWindow` | `SpaceOperatingHoursPolicyTest`(5) | `SlotWindow.start()`/`end()` Java record 접근자를 유지해야 한다. `@JvmRecord data class`로 바꾸거나 record를 별도 Java 파일로 분리한다. `SpaceSlotAvailabilityService` 호출부를 같은 PR에서 컴파일한다. |

**롤백 경계:** 각 PR은 스키마·공개 API·직렬화·메시지 형식을 바꾸지 않으므로 단일 revert로 Java 원본을 복구한다. 한 PR에 두 정책 이상을 섞지 않아 revert 범위를 좁게 유지한다. `ReservationRefundPolicy`에는 기존 전용 단위 테스트가 없으므로, 전환 전에 경계값 테스트를 먼저 추가해 실패/통과를 확인한 뒤 Kotlin으로 옮긴다.

## `step-0`~`step-6` 검증 체크리스트

| 단계 ID | 범위·대상 파일 | 필수 테스트 명령 | 정적/계약 확인 | 롤백 기준 |
| --- | --- | --- | --- | --- |
| `step-0` 혼용 빌드+시범 | `backend/build.gradle`, `src/main/kotlin`, `src/test/kotlin`, `ReservationRefundPolicy.kt`+테스트 | `./gradlew clean compileKotlin compileJava test bootJar` → `./gradlew clean build` | `bootJar`에 Kotlin stdlib·`kotlin-reflect`·Jackson Kotlin 모듈 포함. Java↔Kotlin 양방향 컴파일. Flyway V1~V26. `.java`/`.kt` 동명 클래스 없음. | PR 단일 revert. 배포까지 갔다면 직전 Java JAR로 재배포. |
| `step-1` 순수 정책·유틸 | `ReservationTimePolicy`, `DoorAccessTimePolicy`, `SpaceOperatingHoursPolicy` | `./gradlew test --tests '*PolicyTest'` → `clean build` | `javap`로 정적 메서드·`SlotWindow.start()/end()` 접근자 확인. Java 호출부 컴파일. | 정책별 PR 단일 revert. |
| `step-2` DTO·비영속 서비스 | [03](03-api-contract.md)의 DTO, 비영속 서비스 | 관련 `*ControllerTest`/`*ServiceTest` → `clean build` → `cd frontend && node --test tests/*.cjs && npx tsc --noEmit` | JSON 골든 표본(필드명·null 생략·날짜/enum), Bean Validation 위치, Jackson 직렬화. | PR revert. 공개 계약 변경은 언어 전환과 분리. |
| `step-3` 컨트롤러·설정 | 컨트롤러, 보안 필터, `@ConfigurationProperties` | `*SecurityIntegrationTest`, `*ControllerTest`, 프로필 기동 테스트 → `clean build` | 401/403/429·`Retry-After`·쿠키 속성, `@ModelAttribute`/`Pageable` 기본값, 프로필별 바인딩. | PR revert. 쿠키·오류 코드 변경은 별도 계약 PR. |
| `step-4` 엔티티·JPA/JDBC | 비핵심 엔티티(`WaitlistEntry`, `Notification`, `SpaceRankingSnapshot` 등), JDBC 저장소 | `./gradlew test --tests '*RepositoryTest' --tests '*IntegrationTest'` → `clean build` | `EntityManager` save/flush/clear/재조회, LAZY 프록시, Auditing, MapsId·nullable, `javap`로 엔티티 open 확인. Flyway V1~V26. | 스키마 변경이 없을 때만 revert. 있으면 forward-compatible migration 선행. |
| `step-5` 핵심 도메인 | 인증 세션/토큰·회원, 크레딧·PG 충전, 공간·예약·출입. `step-4`에서 옮긴 이벤트·통계와의 연결도 재검증 | 도메인 동시성·통합 테스트 → `clean build` | 금액·원장·잠금 순서, RT 재사용·세션 폐기, `REQUIRES_NEW` 경계, Outbox payload 호환, proxy open. | 스키마·메시지 호환 유지 시 PR revert. 데이터 변경은 별도 롤백 계획. |
| `step-6` 마무리 | 남은 Java/Lombok, 혼용 플러그인·annotation processor | `./gradlew clean build` + local/검증 환경 JAR 기동 + 프론트 검증. 운영 배포 활성화 시 JAR/systemd/HTTP 별도 확인 | 전체 Flyway V1~V26, 인증→충전→예약 결제→출입→취소→Outbox/알림, 통계 조회/확정. 남은 Java 목록·이유. | 정리 PR revert. Java 폴백은 전체 검증 전까지 유지. |

각 단계는 **하나의 PR = 하나의 정책 또는 밀접한 파일 묶음**이다. 위 명령은 최소값이며, 변경 파일에 따라 관련 테스트를 추가한다. 운영 `deploy`는 `DEPLOY_ENABLED == 'true'`인 `dev` 푸시에만 실행한다. 전환 중 각 PR의 CI `build`와 로컬/검증 환경 기동, `step-6` 통과 후 실제 운영 배포 결과는 서로 다른 증거로 기록한다.

## 전환 순서

| 순서 | 후보와 수정 방법 | 필수 검증 |
| --- | --- | --- |
| 1. 순수 정책·유틸 | [`ReservationRefundPolicy`](../../../backend/src/main/java/com/ovengers/slotkey/reservation/policy/ReservationRefundPolicy.java), [`ReservationTimePolicy`](../../../backend/src/main/java/com/ovengers/slotkey/reservation/policy/ReservationTimePolicy.java), [`DoorAccessTimePolicy`](../../../backend/src/main/java/com/ovengers/slotkey/access/policy/DoorAccessTimePolicy.java), [`SpaceOperatingHoursPolicy`](../../../backend/src/main/kotlin/com/ovengers/slotkey/space/policy/SpaceOperatingHoursPolicy.kt)처럼 DB 의존이 작은 파일을 선택한다. Java 정적 호출은 `object`/`@JvmStatic` 또는 호출부 변경으로 맞춘다. | 기존 경계값 테스트, Java 호출부 컴파일, `javap` 접근자 확인. |
| 2. DTO와 비영속 서비스 | [03 API 계약](03-api-contract.md)이 고정된 DTO부터 파일별로 바꾼다. Java `record` 접근자·Lombok builder 사용 지점을 같은 PR에서 수정한다. 서비스는 Kotlin 주 생성자 주입과 기존 `@Transactional` 위치를 유지한다. | MockMvc JSON·검증, Java/Kotlin 컴파일, 서비스 테스트. |
| 3. 컨트롤러·설정 | `@CurrentMember`, `@Valid`, `@ModelAttribute`, `Pageable` 기본값, `@Value`/`@ConfigurationProperties`를 요청·기동 테스트로 확인한다. | 권한, 쿠키, 오류 코드, 프로필별 기동. |
| 4. 엔티티·JPA/JDBC | [06](06-jpa-and-proxies.md)의 매핑/프록시 검증을 갖춘 도메인부터 바꾼다. 아래 묶음별로 [02](02-money-and-concurrency.md)의 금전·이벤트 불변식과 JDBC 결과를 확인한다. | 저장/재조회, Auditing, MapsId/LAZY/nullable, JDBC, 조건부 UPDATE/잠금·동시성. |
| 5. 마무리 | 운영 Java 파일 목록과 Lombok 사용을 다시 세어 남길 파일을 결정한다. 더 이상 쓰지 않는 플러그인·annotation processor만 제거한다. | 전체 빌드, 실제 프로필 기동, API·프론트엔드 검증. |

## 도메인별 전환 묶음과 롤백 경계

아래 행은 각각 하나 이상의 작은 PR로 나눈다. 모든 파일을 한 번에 바꾼다는 뜻은 아니다. 기존 Java 호출자를 함께 수정하되 서비스 빈·트랜잭션 경계와 DB/메시지 형식을 유지해 PR 단위로 되돌릴 수 있게 한다. 테스트의 경로·기존/보강 구분은 [01](01-test-baseline.md)에 연결한다.

| 묶음 | 보존할 경계 | 회귀 검증 |
| --- | --- | --- |
| 인증·회원 | LocalCredential 공유 PK/LAZY, guard 잠금·증표 만료/소비·가입/재설정 동반 롤백, 탈퇴/잔액 소멸·토큰 폐기. `AuthSession`의 `sid` 검증·만료/폐기, `RefreshToken.authSession` LAZY·`consumed_at` 재사용 감지·세션 전체 폐기, 로그인 시도 `REQUIRES_NEW` 경계를 유지한다. | `VerificationFlowIntegrationTest`, `EmailSignupIntegrationTest`, `PasswordResetIntegrationTest`, `VerificationMemberStatusIntegrationTest`, `AuthControllerTest`, `V2SecurityRedTest`, `AuthSessionMigrationIntegrationTest`, `LoginRateLimitIntegrationTest`, `RefreshTokenServiceTest`, `AdminMemberDetailControllerTest`, `AdminMemberDetailServiceTest`, `InactiveMemberAccessIntegrationTest`, `MemberWithdrawalHoldConcurrencyIntegrationTest`와 회원 HTTP 테스트. |
| PG 충전·복구·웹훅 | DB 준비/PG 호출/DB 확정의 빈 분리, 주문→회원 잠금, TOP_UP 1회·멱등 응답, CONFIRMING 복구·웹훅 재조회. | `ChargeOrderConfirmIntegrationTest`, `ChargeOrderRecoveryProcessorIntegrationTest`, `TossWebhookIntegrationTest`, `PgWebhookEventProcessorIntegrationTest`. Fake 결과와 샌드박스 실행을 구분한다. |
| 예약·크레딧·출입 | HELD 취소 무환불, CONFIRMED 취소/연장/HOLD의 잠금 순서·환불/위약금·시간 경계·토큰 처리. | 기존 예약/출입 테스트와 `ReservationCancelConcurrencyIntegrationTest`, `ReservationExtendHoldConcurrencyIntegrationTest`, [02](02-money-and-concurrency.md)의 빠진 원장/상태 검증. |
| Outbox·대기·알림 | 취소/만료와 Outbox 동반 저장, 트랜잭션 밖 Kafka 전송, processed_event·notification 동반 롤백, 기존 payload 호환, active=null·DynamicUpdate. | `OutboxRecordIntegrationTest`, `OutboxRelayIntegrationTest`, `SlotReleasedListenerTest`, `SlotReleasedHandlerConcurrencyTest`, `WaitlistEntryLostUpdateTest`, `NotificationReadIntegrationTest` 등. OFF/ON 및 브로커 실행 여부를 구분한다. |
| 통계·JDBC | 순매출 long/건수 int, [from,to) 기간, 종료일 00:01 확정, 지역 스냅샷과 재확정 삭제/재삽입·보충 처리. | `SpaceRankingConfirmServiceIntegrationTest`, `SpaceRankingQueryServiceIntegrationTest`, `SpaceRankingBackfillServiceIntegrationTest`, 일반/관리자 Stats 컨트롤러 테스트. |

일반 HTTP·외부 웹훅·PG JSON·저장된 메시지를 [03](03-api-contract.md)의 서로 다른 계약으로 확인한다. 메시지/DB 스키마 변경이 필요하면 언어 전환과 분리하고, 되돌린 Java가 새 저장 데이터를 읽지 못하는 변경을 같은 PR에 섞지 않는다.

## 2026-10-22 합의 사항 요약

| 항목 | 합의 | 근거 |
| --- | --- | --- |
| DTO·이벤트 | `@JvmRecord data class`로 통일. 요청 DTO는 null 가능 타입과 `@field:` 검증 유지. | [data class 결정 2-5](../../decisions/kotlin-data-class-scope.md) |
| 엔티티 | 일반 `class`. `builder()`·정적 생성 메서드는 `companion object`의 `@JvmStatic`으로 호출 방식 유지. 전환 전에 22개 delombok. | [JPA 결정](../../decisions/kotlin-jpa-entity-open.md), [Lombok 경계 2-4](../../decisions/kotlin-lombok-boundary.md) |
| 테스트 | 운영 코드와 같은 PR에서 Kotlin으로 옮기되 판정 줄은 바꾸지 않는다. 테스트 수는 기준선 이상, 스킵 0. | [테스트 보호 장치](../../decisions/kotlin-test-guardrails.md) |
| 코드 스타일 | ktlint 기본 규칙, 빌드 실패. | [ktlint 결정](../../decisions/kotlin-ktlint.md) |
| 리뷰 | 기존 `dev` 규칙(승인 2명, 관리자 포함) 유지. | `dev` 브랜치 보호 설정 |
| 배포 | `DEPLOY_ENABLED`는 가장 마지막에 켠다. | [04](04-mixed-build.md) |
| 일정 | 별도 컷 라인은 두지 않고 일정에 맞춘다. 테스트를 줄이거나 생략해 맞추지 않는다. | 사용자 확인 |

## 파일 단위 작업 절차

1. 한 PR의 범위를 **하나의 정책 또는 한 도메인의 밀접한 파일 묶음**으로 적고, 해당 Java 호출부·테스트·DTO를 찾는다. 같은 클래스의 `.java`와 `.kt`를 동시에 남기지 않는다.
2. nullable 선언을 DB 컬럼과 요청 계약에 맞춘다. `Optional` 반환이나 Java 플랫폼 타입은 명시적으로 처리한다. `Int`/`Long` 선택은 기존 JSON 숫자·DB 타입과 맞추고, 금액 계산의 오버플로 검증을 보존한다.
3. `record`의 값 비교·접근자, Lombok builder·getter, Java 정적 메서드가 Java 호출부에서 어떻게 보였는지 확인한다. Kotlin `data class`는 DTO에 우선 사용하고 엔티티에서는 [06 문서](06-jpa-and-proxies.md)의 기준을 적용한다.
4. `Clock`을 통한 시간 계산, 오류 코드, 로깅, `@Transactional` 경계를 유지한다. 자동 변환 도구 결과는 수동으로 읽고 Java의 변경 가능 컬렉션·null 의미가 달라지지 않았는지 확인한다.
5. 관련 테스트 → 필터 없는 `cd backend && ./gradlew clean build` → HTTP/공유 직렬화에 영향이 있으면 `cd frontend && node --test tests/*.cjs && npx tsc --noEmit` 순으로 실행한다. frontend CI는 백엔드만 변경한 PR에는 자동 실행되지 않는다. 공개 타입/화면 변경은 lint·static export 빌드도 수행한다. Docker 없이 실 DB가 필수인 PR을 완료로 판정하지 않는다. CI·로컬/검증 환경 기동·활성화 후 실제 운영 배포는 확인한 범위대로 따로 기록한다.
6. PR에 전환한 파일, Java 호출부 수정, 계약 차이 여부, 명령·결과를 기록한다. API 동작이 바뀌면 `docs/api/api-spec.md`와 `frontend/src/types/api.ts`를 같은 PR에서 갱신한다.

## 완료 판단

- 매 PR은 도메인 테스트·전체 클린 빌드·관련 프론트 검증을 통과한다. [01](01-test-baseline.md)의 기존 테스트 재실행과 필요한 보강을 마치며 위 묶음의 실제 DB 경합·롤백·계약을 확인한다.
- 전체 완료 시 착수 커밋의 Flyway 전체(**V1~V26**)와 [04](04-mixed-build.md)의 프로필 기동을 확인한다. 인증→충전→예약 결제→출입→취소→Outbox/알림, 통계 조회/확정을 재검증하고 [`테스트 결과`](../../testing/test-results.md)에 새 날짜/커밋/실행 명령/실패·스킵/외부 검증 범위를 남긴다.
- 남은 Java 파일과 Lombok/혼용 플러그인은 의도와 근거가 문서화되어 있다. 불필요한 설정은 별도 정리 변경에서 제거한다.
