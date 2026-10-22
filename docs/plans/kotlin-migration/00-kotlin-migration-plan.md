# 백엔드 Kotlin 마이그레이션 준비 계획

> 팀원용 일정은 [목~화 요일별 계획](daily/00-overview.md), 문서별 읽는 순서는 [문서 안내](README.md)를 확인합니다.

> 최신 대조: 2026-10-20, `dev` / `d1a15eca3228c8b1b1f9260fc7a686860d2b1e44`(짧은 해시 `d1a15ec`). 최초 작성은 2026-09-25이며 이후 구현·CI 변경을 반영했다. 대상은 `backend/`의 Java 21·Spring Boot API이고, `frontend/`와 PG·Kafka 메시지는 계약 검증 대상이다. 이번 갱신은 [기능 동결 기준선 실행 기록](../../testing/test-results.md)의 실측치를 반영했으며 Kotlin 전환을 수행한 결과가 아니다.

> 2026-10-22 재대조: 현재 3차 저장소의 `01086cf`에서 `backend/src`, `backend/build.gradle`, Gradle Wrapper 설정은 위 기준선과 동일하다. 아래 코드 집계는 유지하며, 일정·CI 배포 조건·채택된 설계 설명을 갱신했다. 1,048건 통과는 10/20 실행 기록이고 이번 문서 점검에서는 테스트를 재실행하지 않았다.

## 현재 위치와 판단

- `backend/build.gradle`은 Java 단일 모듈이다. Spring Boot 3.3.4, Gradle Wrapper 8.10.2, Java toolchain 21을 사용하며 Kotlin 플러그인과 Kotlin 소스는 아직 없다. 혼용 빌드는 [04](04-mixed-build.md)의 `kotlin-migration/step-0`에서 시작한다.
- 2026-10-20 기능 동결 커밋 실측치는 운영 Java 파일 **293개**, JPA `@Entity` **22개**(`AuthSession` 포함), `@MappedSuperclass` 1개([`BaseTimeEntity`](../../../backend/src/main/java/com/ovengers/slotkey/global/common/entity/BaseTimeEntity.java)), record 선언 파일 **93개**, Lombok import 파일 **88개**다. `src/main/java`에서 집계했으며 중첩 record를 포함한 **파일 수**를 DTO 개수로 해석하지 않는다. **혼용 컴파일, JPA·JDBC 매핑, 프록시·트랜잭션, HTTP·메시지 직렬화**가 전환 위험의 중심이다.
- MySQL 8.4 Testcontainers와 Flyway **V1~V26**, `ddl-auto: none`을 사용한다. V24는 인증 세션·리프레시 토큰 연결, V25는 `refresh_token.consumed_at`, V26은 로그인 시도 버킷 테이블이다. 언어 전환 자체에는 스키마 변경이 필요하지 않으며 기존 SQL/checksum을 유지한다. 착수 시 해당 커밋의 migration 전체 목록을 다시 확인한다.
- 테스트는 `src/test/java` **148개 파일 = 실행 스위트 135 + `@Test`가 없는 빈 클래스 5 + 지원·픽스처 8**이다. 2026-10-20 `clean build`에서 **1,048개 테스트 메서드 전원 통과**(실패 0·오류 0·스킵 0, `@Disabled` 0건)를 확인했고 `V2SecurityRedTest` 15건은 전원 활성이다. 이 수치는 로컬 Linux·JDK 21·Docker MySQL 8.4 결과이며 CI·배포 결과가 아니다. [01의 대응표](01-test-baseline.md)에서 기존 재실행/보강/신규 작성을 구분하고, 과거 gap 목록을 현재 미구현으로 그대로 적용하지 않는다.
- [backend-ci.yml](../../../.github/workflows/backend-ci.yml)의 Ubuntu `build`는 PR 및 `dev` 푸시에서 `./gradlew build`를 실행한다. 배포는 **`DEPLOY_ENABLED == 'true'`이고 `dev` 푸시인 경우에만** 실행한다. 활성화된 self-hosted 배포는 `./gradlew clean build -x test`로 재빌드하고 JAR 설치·systemd 재시작·기동 로그를 확인한다. 전환 중에는 로컬/검증 환경의 JAR 기동을 확인하고, 운영 배포는 `step-6` 통과 후 별도 활성화 시 검증한다. 변수의 실제 값이나 배포 실행 여부를 이번 문서 점검으로 확인한 것은 아니다.
- [frontend-ci.yml](../../../.github/workflows/frontend-ci.yml)은 lint·타입 검사·Node 테스트·static export 빌드를 수행하지만 `frontend/**`와 해당 workflow 변경에만 실행된다. 백엔드만 변경한 전환 PR은 프론트 계약 검증을 별도 실행하거나 트리거를 보완해야 한다.

**권장 방향:** 먼저 비교 가능한 테스트·API 기준선을 확보하고, Java/Kotlin 혼용 빌드를 작은 변경으로 검증한다. 그다음 의존 관계가 적은 영역부터 옮기고 예약·크레딧·출입 및 JPA 엔티티는 실 DB 회귀 검증이 갖춰진 뒤 진행한다. 첫 전환 단계에서는 Boot 3.3.4, Gradle 8.10.2, Java 21을 고정하고 프레임워크 버전 변경은 별도 작업으로 다룬다.

## 작업별 실행 문서

1. [P0 — 테스트 기준선과 문의 API 검증](01-test-baseline.md)
2. [P0 — 금전·예약 동시성 검증](02-money-and-concurrency.md)
3. [P0 — API 계약 고정](03-api-contract.md)
4. [P1 — Java/Kotlin 혼용 빌드](04-mixed-build.md)
5. [P1 — Lombok 상호 운용 경계](05-lombok-boundary.md)
6. [P1 — JPA 엔티티와 Spring 프록시](06-jpa-and-proxies.md)
7. [P2 — 단계별 Kotlin 전환과 정리](07-code-migration.md)

각 문서는 해당 작업의 수정 대상, 순서, 검증 명령 및 완료 조건을 설명한다. P0 세 작업은 서로 병행할 수 있으며, P1 혼용 빌드의 최종 통과 기준은 P0 기준선에 의존한다.

## 우선순위별 준비 작업

| 우선순위 | 수정할 부분과 근거 | 완료 기준 |
| --- | --- | --- |
| P0 — 기준선 | Docker/JDK 21에서 `./gradlew clean build`를 재실행하고 날짜·커밋·환경·결과를 기록한다. **2026-10-20 `d1a15ec` 기준선은 완료됐다(1,048/1,048 통과).** 남은 일은 [01의 대응표](01-test-baseline.md)로 기존 테스트를 재사용하며 문의 회원 소유권·DB 검증 등 빠진 경로와 빈 클래스 5개를 보강/정리하는 것이다. | 동일 커밋의 로컬·CI 전체 테스트와 미검증 범위가 구분된다. |
| P0 — 금전·경합·이벤트 | [02](02-money-and-concurrency.md)의 예약 차감/환불, 충전 확정/복구/웹훅, HELD 취소를 검증한다. 취소와 Outbox 저장의 동반 롤백, 별도 소비 트랜잭션의 processed_event와 알림 저장 동반 롤백을 각각 확인한다. 인증 증표 소비/만료와 로그인 시도·비활성 출입 로그의 `REQUIRES_NEW` 경계는 [01](01-test-baseline.md)·[06](06-jpa-and-proxies.md)에 연결한다. | DB의 잔액·원장·상태·슬롯·이벤트가 일치하고 외부 PG/Kafka 호출이 DB 잠금을 잡지 않는다. |
| P0 — HTTP·메시지 계약 | [03](03-api-contract.md)의 인증·회원·충전·대기·알림·통계·문의 요청/응답, Auth V2 세션·RT 회전·429, 관리자 회원 상세·이력, 웹훅의 빈 본문 200, 기존 Outbox payload를 고정한다. 누락/null, 날짜·숫자·enum, 오류·쿠키를 포함한다. | 전환 전후 HTTP와 저장된 메시지 호환성이 같고 관련 프론트 검증이 통과한다. |
| P1 — 혼용 빌드 | 기존 `build.gradle`에 Kotlin JVM·Spring·JPA 플러그인과 `kotlin-reflect`, Jackson Kotlin 모듈을 추가하는 작은 준비 PR(`kotlin-migration/step-0`)을 만든다. Kotlin 플러그인 버전은 `1.9.25`로 고정하고 근거는 [결정 문서](../../decisions/kotlin-plugin-version.md)에 둔다. `src/main/kotlin`, `src/test/kotlin`을 열고 Java↔Kotlin 호출을 각각 컴파일 검증한다. | `compileJava`, `compileKotlin`, `test`, `bootJar`가 통과하고 애플리케이션이 현재 프로필로 시작한다. |
| P1 — Lombok 경계 | Lombok 생성 getter·builder·생성자를 Kotlin 소스가 같은 모듈에서 참조하는 지점을 조사한다. 기존 Java 코드를 모두 미리 바꿀 필요는 없다. 첫 전환 단위의 직접 의존을 명시적 Java 메서드/생성자로 정리한다. Kotlin Lombok 컴파일러 플러그인은 도입하지 않고, 미전환 Java 내부의 Lombok은 전환 시점까지 유지한다. | 선택한 경계에서 클린 빌드가 안정적으로 되고, Java 테스트 호출부와 Kotlin 호출부가 함께 컴파일된다. |
| P1 — JPA·JDBC·Spring 프록시 | [06](06-jpa-and-proxies.md)의 엔티티 22개와 JDBC 경계를 목록화한다. LocalCredential의 MapsId/LAZY, AuthSession·RefreshToken의 세션 연관, 인증 증표, WaitlistEntry의 nullable active·DynamicUpdate, PG/Outbox/consumer의 트랜잭션 경계를 확인한다. | 저장·재조회·LAZY·Auditing·JDBC 결과 매핑·롤백·전파가 실 DB 테스트에서 유지된다. |
| P2 — 코드 단순화 | 순수 정책/값 객체부터 Kotlin으로 옮기고, Java `record` 접근자(`name()`)와 Lombok builder 호출부를 파일별로 갱신한다. `data class`는 DTO·이벤트·불변 값 객체에 사용하고, JPA 엔티티·공통 영속 부모는 일반 `class`로 옮긴다. | 각 도메인 묶음이 독립적으로 빌드·테스트되고 공개 API와 Flyway 스키마가 유지된다. |

## 이번 프로젝트의 실행 순서

| 시점 | 작업 묶음 | 산출물과 다음 단계 조건 |
| --- | --- | --- |
| 10/22(목) | `step-0`에서 혼용 빌드·`ReservationRefundPolicy`를 시범 전환하고, 통과 후 `step-1`의 순수 정책·공통 응답을 옮긴다. 다른 담당자는 P0의 빠진 테스트를 보강한다. | 양방향 컴파일·전체 빌드·로컬/검증 환경 JAR 기동. 실패하면 의존 전환 보류. |
| 10/23(금) | `step-2`에서 DTO·인가·DB를 직접 쓰지 않는 코드를 옮기고 P0 보강을 마친다. | JSON·입력 오류·권한·Java 호출부·프론트 검사 통과. 영속 코드 전환 전 P0 근거 확보. |
| 10/24(토)~10/26(월) | `step-3`의 컨트롤러/설정, `step-4`의 엔티티/저장소·이벤트/통계를 순서대로 옮긴다. | DB 저장/재조회·프록시·롤백·메시지 호환 확인. 실제 날짜별 작업은 [요일별 계획](daily/00-overview.md) 참조. |
| 10/27(화), 10/28(수) 예비 | `step-5`에서 핵심 금액·예약·인증·출입을 옮기고, 통과하면 `step-6`의 정리·전체 점검을 마친다. | 화요일 완료 목표. 필수 검증이 남으면 수요일 이월. 운영 배포는 `step-6` 통과 후 별도 활성화할 때 확인. |

## 전환 중 지켜야 할 구현 기준

1. **엔티티:** `@Entity`와 상속된 `BaseTimeEntity`의 필드 매핑을 유지한다. 고정한 Kotlin `1.9.25`에서는 `kotlin-jpa`(no-arg)와 `kotlin-spring`/`allopen`을 조합하고 `@Entity`·`@MappedSuperclass`·`@Embeddable`을 open 대상에 둔다. Kotlin 2.3.20 이상의 JPA `all-open` preset은 업그레이드 시 재검토할 항목이며, 현 버전의 동작으로 단정하지 않는다. `Space.version`의 기본값 0, nullable 시각 필드, `RefreshToken.member`·`RefreshToken.authSession`의 지연 로딩과 `AuthSession`의 세션 만료·폐기 필드를 별도 확인한다.
2. **DTO와 검증:** Java `record`를 Kotlin으로 바꿀 때 JSON 필드명과 생성자 기본값, 누락과 `null`의 차이를 명시한다. Bean Validation·Jackson·OpenAPI 애너테이션은 `@field:`, `@get:`, `@param:` 대상 위치를 의도대로 지정하고 MockMvc로 검증한다. `SpaceCreateRequest`의 `@AssertTrue`와 `AdminAuditLogSearchCondition`·`ReservationSearchCondition`의 `@ModelAttribute` 바인딩을 우선 확인한다.
3. **트랜잭션과 동시성:** 읽기/쓰기 경계, 감사·인증 증표·Outbox·중복 처리의 `MANDATORY` 전파, 조건부 UPDATE 영향 행과 잠금 순서를 보존한다. `JdbcLoginAttemptStore`의 `REQUIRES_NEW`+`SELECT ... FOR UPDATE`, `InactiveMemberAccessLogService`의 `REQUIRES_NEW`처럼 본 흐름과 분리된 로그·시도 기록 경계를 합치지 않는다. PG 준비/외부 호출/확정과 Outbox 전송의 분리된 Spring 빈 경계도 유지한다. nullable/Optional과 주입 `Clock`의 시간 경계, long 주문액→int 잔액의 상한 검사를 유지한다.
4. **설정과 실행:** 이미지·JWT·CORS 외에 인증 HMAC/SMTP, Toss URI/Duration/secret, Kafka OFF/ON, 복구·웹훅 스케줄러를 [04](04-mixed-build.md)의 프로필별 기동 검증에 포함한다. 테스트 캐시 제한 `spring.test.context.cache.maxSize=2`를 유지한다. Gradle Kotlin DSL 전환은 별도 작업이다.
5. **프론트엔드 계약:** 공개 URL, 응답 래퍼, 오류 코드, 날짜·시간 문자열과 쿠키는 기존 `docs/api/api-spec.md` 및 `frontend/src/types/api.ts`와 일치시킨다. 계약 변경이 실제로 필요한 경우 백엔드·프론트엔드·문서를 같은 PR에서 갱신한다.

## 착수·완료 판단

- **현재 상태:** 2026-10-20 `d1a15ec`에서 전체 테스트 기준선을 확보했다(1,048/1,048, `@Disabled` 0). 다만 P0의 시나리오 보강·HTTP/메시지 계약 차이 정리와 혼용 빌드 시범 PR(`step-0`)은 아직 완료되지 않았으므로 엔티티·서비스 본 전환 착수는 **부분 보류**다. 순수 정책·유틸 시범 전환은 P0 추가 없이 시작할 수 있다.
- **착수 가능:** 동일 커밋의 최신 전체 테스트와 P0 시나리오·HTTP/메시지 계약이 확인되고 혼용 빌드 시범 PR이 CI에서 통과한다. 테스트 소스 존재를 실행 성공으로 세지 않는다.
- **각 전환 PR 완료:** 필터 없는 `./gradlew clean build`와 관련 실 MySQL 테스트 통과, 직렬화·검증·권한·트랜잭션 차이 없음, 변경된 공개 계약은 프론트엔드 테스트와 문서에 반영됨. 로컬/검증 환경의 필요한 기동 결과를 남기고, 운영 배포는 `step-6` 통과 후 활성화되어 실제 실행한 경우에만 별도로 기록한다.
- **전체 전환 완료:** 남길 Java·Lombok/혼용 설정의 근거를 정리한다. 전체 Flyway(V1~V26)와 프로필 기동, 인증→충전→예약 결제→출입→취소→Outbox/알림, 통계 조회/확정을 확인한다. 실제 PG·SMTP·Kafka 검증과 Fake 기반 결과를 구분해 `docs/testing/test-results.md`에 새 실행 날짜로 기록한다.

## 관련 결정 문서

2026-10-20에 채택한 Kotlin 전환 결정 문서다. 채택은 구현 완료를 뜻하지 않는다. 각 문서의 상태·대안·trade-off는 해당 문서를 따르고, 이 계획서는 링크와 적용 지점만 둔다.

- [Kotlin 플러그인 버전](../../decisions/kotlin-plugin-version.md) — Spring Boot 3.3.4 BOM·Java 21 toolchain에 맞는 버전 고정([04](04-mixed-build.md)).
- [Lombok 경계](../../decisions/kotlin-lombok-boundary.md) — Kotlin Lombok 플러그인 미도입, 전환 경계 delombok([05](05-lombok-boundary.md)).
- [JPA 엔티티 open](../../decisions/kotlin-jpa-entity-open.md) — `kotlin-jpa` no-arg + `allopen`, 엔티티 `data class` 금지([06](06-jpa-and-proxies.md)).
- [data class 범위](../../decisions/kotlin-data-class-scope.md) — DTO·이벤트·값 객체 한정, `@JvmRecord`/동시 전환([07](07-code-migration.md)).

## 참고 근거

- 저장소: [`backend/build.gradle`](../../../backend/build.gradle), [`backend-ci.yml`](../../../.github/workflows/backend-ci.yml), [테스트 전략](../../testing/test-strategy.md), [테스트 결과](../../testing/test-results.md), [핵심 도메인 결정](../../core-domain-decisions.md).
- 공식 문서: [Spring Boot 3.3 Kotlin 지원](https://docs.spring.io/spring-boot/3.3/reference/features/kotlin.html), [Kotlin JPA 무인자 생성자](https://kotlinlang.org/docs/no-arg-plugin.html), [Kotlin 2.3.20 JPA `all-open` 변경](https://kotlinlang.org/docs/whatsnew2320.html#improved-jpa-support-in-the-kotlin-plugin-jpa-plugin), [Kotlin Lombok 혼용 플러그인](https://kotlinlang.org/docs/lombok.html).
