# 06. JPA 엔티티와 Spring 프록시

**목적:** Kotlin 엔티티의 생성·상속·필드 매핑과 Spring AOP가 Java 때와 같은 영속성·트랜잭션 동작을 내도록 검증한다. [상위 계획](00-kotlin-migration-plan.md)의 세 번째 P1 작업이다.

## 대상과 위험 지점

2026-10-20 / `d1a15ec`의 운영 코드에 **`@Entity` 22개**, [`BaseTimeEntity`](../../../backend/src/main/java/com/ovengers/slotkey/global/common/entity/BaseTimeEntity.java) `@MappedSuperclass` 1개가 있다. 기존 Member·Reservation·Space·RefreshToken·Inquiry 외에 `AuthSession`을 포함한 17개 엔티티가 더 있다. 엔티티 전체는 `AuditLog`, `AuthSession`, `ChargeOrder`, `CreditTransaction`, `DoorAccessLog`, `DoorAccessToken`, `IdempotencyKey`, `LocalCredential`, `Notification`, `OutboxEvent`, `ReservationSlot`, `ReservationStatusHistory`, `SpaceRankingSnapshot`, `VerificationChallenge`, `VerificationGrant`, `VerificationGuard`, `WaitlistEntry`다. 아래 표는 매핑 위험이 큰 대표 항목이며, 목록은 착수 시 다시 추출한다. `RefreshToken.member`·`RefreshToken.authSession`은 LAZY, `Space.version`은 DB NOT NULL의 기본값 0이며 BaseTimeEntity/AuditLog의 Auditing도 유지한다.

| 추가 엔티티 | 보존할 매핑·상태·검증 |
| --- | --- |
| [`AuthSession`](../../../backend/src/main/java/com/ovengers/slotkey/auth/entity/AuthSession.java) | 문자열 UUID PK, Member LAZY, `createdAt`/`expiresAt`/`revokedAt`, `isActiveAt`·`revoke`. 세션 폐기와 RT 전체 폐기가 같은 흐름에서 커밋되는지 확인한다. |
| [`RefreshToken`](../../../backend/src/main/java/com/ovengers/slotkey/auth/entity/RefreshToken.java) | `member`·`authSession` LAZY, `token_hash` UNIQUE, `revokedAt`·`consumedAt`. RT 회전·재사용 감지 시 세션 폐기와 `revokeAllBySessionId`가 함께 동작하는지 확인한다. |
| [`ChargeOrder`](../../../backend/src/main/java/com/ovengers/slotkey/credit/entity/ChargeOrder.java) | long 주문액, nullable paymentKey/결제수단/복구 필드, 상태 enum·멱등 키·원장의 주문 UNIQUE 연결. 별도 PG 호출과 DB 확정을 합치지 않는다. |
| [`LocalCredential`](../../../backend/src/main/java/com/ovengers/slotkey/auth/entity/LocalCredential.java) | Member와 `@MapsId` 공유 PK·LAZY 일대일. 비밀번호는 Member가 아닌 이 엔티티에 있으며 저장 후 memberId·연관 참조를 확인한다. |
| [`VerificationGuard`](../../../backend/src/main/java/com/ovengers/slotkey/auth/entity/VerificationGuard.java), [`VerificationChallenge`](../../../backend/src/main/java/com/ovengers/slotkey/auth/entity/VerificationChallenge.java), [`VerificationGrant`](../../../backend/src/main/java/com/ovengers/slotkey/auth/entity/VerificationGrant.java) | 이메일·목적별 guard 잠금, 연관관계/LAZY, 만료·소비·무효화 nullable 시각과 일회성 증표 상태를 보존한다. |
| [`WaitlistEntry`](../../../backend/src/main/java/com/ovengers/slotkey/waitlist/entity/WaitlistEntry.java) | WAITING의 active=1, 취소의 active=null로 활성 항목만 UNIQUE를 적용한다. `@DynamicUpdate`를 유지해 취소 flush가 별도 갱신된 last_notified_at을 덮어쓰지 않게 한다. |
| [`OutboxEvent`](../../../backend/src/main/java/com/ovengers/slotkey/outbox/OutboxEvent.java) | eventId·topic·partitionKey·payload·상태/시각을 보존한다. 기존 저장 JSON은 [03](03-api-contract.md)에 따라 전환 후에도 처리할 수 있어야 한다. |
| [`Notification`](../../../backend/src/main/java/com/ovengers/slotkey/notification/entity/Notification.java) | 회원/대기/이벤트 ID와 읽음 nullable 시각을 유지하고 중복 처리 기록·알림 저장의 동반 롤백을 확인한다. |
| [`SpaceRankingSnapshot`](../../../backend/src/main/java/com/ovengers/slotkey/stats/entity/SpaceRankingSnapshot.java) | 기간/공간과 계산 당시 지역, long 순매출·int 건수/슬롯 수, 계산 시각을 보존한다. 재확정 시 기존 기간 삭제/재삽입은 한 트랜잭션이다. |

JPA 목록만으로 전환 범위를 끝내지 않는다. [`ProcessedEventStore`](../../../backend/src/main/java/com/ovengers/slotkey/notification/consumer/ProcessedEventStore.java)와 [`PgWebhookEventStore`](../../../backend/src/main/java/com/ovengers/slotkey/credit/webhook/PgWebhookEventStore.java)는 JDBC로 별도 테이블을 사용하고, 통계 집계도 NamedParameterJdbcTemplate을 사용한다. [`JdbcLoginAttemptStore`](../../../backend/src/main/java/com/ovengers/slotkey/auth/ratelimit/JdbcLoginAttemptStore.java)는 `REQUIRES_NEW`+`SELECT ... FOR UPDATE`+`ON DUPLICATE KEY UPDATE`로 로그인 시도 버킷을 다루고, [`InactiveMemberAccessLogService`](../../../backend/src/main/java/com/ovengers/slotkey/access/service/InactiveMemberAccessLogService.java)는 `REQUIRES_NEW`로 거절 로그를 기록한다. 영향 행 수·중복 처리·NULL·숫자 결과 매핑과 JPA/JDBC의 트랜잭션 참여, self-invocation에 의한 전파 무시를 함께 확인한다.

## 설정과 엔티티 변환 방법

1. [04 혼용 빌드](04-mixed-build.md)에서 고정한 Kotlin `1.9.25`의 JPA 플러그인이 무인자 생성자를 제공하는지 확인하고, 엔티티 `open`은 `kotlin-spring`/`allopen` preset으로 명시한다. Kotlin 2.3.20 이상의 JPA `all-open` preset은 업그레이드 시 재검토한다. Spring `@Service`·`@Configuration`은 `kotlin-spring` 프록시 설정을 확인한다.
2. 엔티티별로 `@Table`, `@Id`, `@GeneratedValue`, `@Column`, `@Enumerated`, 관계·지연 로딩, 상속 필드를 표로 기록한다. Kotlin에서 애너테이션이 필드에 놓이도록 `@field:` 지정 여부를 확인한다. 실제 매핑은 `EntityManager` 저장·flush·clear·재조회로 검증한다.
3. JPA 엔티티와 `@MappedSuperclass`에는 [채택한 결정](../../decisions/kotlin-jpa-entity-open.md)에 따라 `data class`를 사용하지 않는다. 일반 클래스로 기존 동등성·연관관계·생성 팩터리를 유지하고 JPA의 무인자 인스턴스화 경로도 테스트한다.
4. `Space.version = 0`, nullable 시각 필드와 `Member.balance`의 기본값이 신규 생성·DB 재조회에서 유지되는지 확인한다. `RefreshToken.member`·`RefreshToken.authSession`은 `entityManager.flush(); entityManager.clear()` 후 프록시 초기화 전/후를 확인해 예기치 않은 즉시 로딩을 찾는다.
5. [`JpaAuditingConfig`](../../../backend/src/main/java/com/ovengers/slotkey/global/config/JpaAuditingConfig.java)의 주입 `Clock`과 감사 로그의 MANDATORY를 확인한다. 인증 grant/guard·SlotReleasedRecorder·ProcessedEventStore도 MANDATORY이며 호출자 없는 호출의 거절과 비즈니스 변경의 동반 롤백을 보존한다. PG 외부 호출·relay는 DB 트랜잭션 밖에 남기고 준비/확정/상태 갱신의 빈 경계를 유지한다. Kotlin final/self-invocation으로 AOP를 우회하지 않는지 실제 Spring 빈과 DB로 검증한다.
6. `SpaceImageProperties`와 `TossPaymentsProperties`의 경로/URI/Duration·기본값/nullable·환경변수 우선순위를 기동 테스트로 확인한다. 인증 HMAC·SMTP 발신/계정 일치, Fake PG/test 프로필, Kafka와 스케줄러 활성 조건은 [04](04-mixed-build.md)의 기동 표를 따른다.

## 테스트와 완료 조건

- 각 전환 PR에서 Docker/MySQL 8.4로 관련 테스트와 필터 없는 `cd backend && ./gradlew clean build`를 실행한다. [01](01-test-baseline.md)의 인증·PG·Outbox/알림·대기·통계 테스트를 해당 변경에 맞게 재사용하고 저장/재조회·LAZY·Auditing·nullable/공유 PK·동적 UPDATE·롤백을 확인한다.
- `bootJar`와 테스트 프로필의 **Flyway V1~V26 전체 적용**을 확인한다. V24~V26은 인증 세션·RT `consumed_at`·로그인 시도 버킷이며, 착수 커밋의 이후 migration도 포함한다. 언어 전환만으로 기존 SQL/checksum을 수정하지 않는다. 실제 스키마 변경은 별도 결정/다음 버전 SQL로 다룬다.
- 프록시·트랜잭션의 최종 판정은 단위 Mockito 테스트가 아니라 Spring 빈과 실 DB를 거친 통합 테스트에서 한다. JPA 엔티티 `open`/기본 생성자/`data class` 금지 기준은 [결정 문서](../../decisions/kotlin-jpa-entity-open.md)를 따른다.

공식 근거: [Spring Boot 3.3 Kotlin 지원](https://docs.spring.io/spring-boot/3.3/reference/features/kotlin.html), [Kotlin JPA 무인자 생성자](https://kotlinlang.org/docs/no-arg-plugin.html), [Kotlin 2.3.20 JPA 변경](https://kotlinlang.org/docs/whatsnew2320.html#improved-jpa-support-in-the-kotlin-plugin-jpa-plugin).
