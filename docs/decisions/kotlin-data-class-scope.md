---
title: data class는 DTO·이벤트 페이로드·불변 값 객체에만 쓰고 Java record 경계는 @JvmRecord 또는 동시 전환으로 맞춘다
type: 기술 선택
status: 채택
date: 2026-10-20
tags: [kotlin, data-class, dto, java-interop, migration]
related: [kotlin-plugin-version.md, kotlin-lombok-boundary.md, kotlin-jpa-entity-open.md]
---

# data class는 DTO·이벤트 페이로드·불변 값 객체에만 쓰고 Java record 경계는 @JvmRecord 또는 동시 전환으로 맞춘다

> **상태:** 채택 (2026-10-20). 아직 DTO를 Kotlin으로 바꾸기 전이므로 **구현 전 채택**이며, `kotlin-migration/step-0`·`step-1`의 `SlotWindow`와 이후 DTO 전환 PR의 양방향 컴파일·JSON 테스트로 확정한다.
> 기준: `dev` `d1a15eca3228c8b1b1f9260fc7a686860d2b1e44`, record 선언 93개, `@Service` 58개, `@Component` 28개, `@Entity` 22개 + `@MappedSuperclass` 1개.

> 2026-10-22 설명 정정: 현재 3차 저장소의 Java 호출부를 계속 확인하며, 테스트 수는 과거 기준선과 새 실행 결과를 구분한다. 채택일과 data class 적용 범위는 유지한다.

## 한 줄 요약

Kotlin `data class`는 **요청/응답 DTO, 도메인 이벤트 페이로드, 순수 불변 값 객체**에만 쓰고, Java가 `name()` record 접근자로 호출하는 타입은 **`@JvmRecord`를 붙이거나 호출부를 같은 PR에서 전환**하며, Spring Bean·JPA 엔티티·`@ConfigurationProperties`에는 기본적으로 쓰지 않는다.

## 1. 문제와 배경

현재 백엔드에는 record 선언이 **93개 파일** 있다. 요청/응답 DTO, 내부 결과 객체, 이벤트 페이로드, 정책의 값 객체 등이 record로 표현되어 있고, Java 코드가 record 접근자를 직접 호출하는 곳도 많다.

| 예 | Java 호출부 | 호출 방식 |
| --- | --- | --- |
| `LoginRequest` | `AuthController` | `request.email()`, `request.password()` |
| `IssuedRefreshToken` | `AuthService` | `issuedRefreshToken.sessionId()`, `.rawToken()` |
| `SpaceOperatingHoursPolicy.SlotWindow` | `SpaceSlotAvailabilityService` | `window.start()`, `window.end()` |
| `AdminMemberDetailResponse` | `AdminMemberDetailServiceTest` 등 | `response.member().email()` |
| `SpaceResponse` | 같은 파일의 `from` 메서드 | `SpaceResponse.builder()` (Lombok, 외부 호출 없음) |
| `SlotReleasedRecorder.Payload` | `SlotReleasedRecorder` 내부 | private record, Outbox JSON payload |

Kotlin `data class`는 `equals/hashCode/toString/copy/componentN`을 자동 생성하고, Jackson Kotlin 모듈과 잘 맞는다. 그러나 Java에서 보이는 접근자 이름이 record와 다르다.

- Java record: `name()`, `start()`, `email()`
- Kotlin `data class`: `getName()`, `getStart()`, `getEmail()`

따라서 Java 호출부가 record 접근자를 쓰는 타입을 일반 Kotlin `data class`로 바꾸면 **Java 컴파일이 깨진다.** `@JvmRecord`를 붙이면 Kotlin data class가 `java.lang.Record`를 상속하고 record 접근자 이름을 생성해 이 경계를 맞출 수 있지만, 모든 DTO에 무조건 붙일 수는 없다.

또한 `data class`는 값 동등성이 자연스러운 타입에만 맞는다. 다음은 값 객체가 아니다.

- JPA `@Entity`/`@MappedSuperclass`: DB 행의 정체성·생명주기·프록시를 가진다. [JPA 엔티티 open 결정](kotlin-jpa-entity-open.md)에서 금지한다.
- Spring `@Service`/`@Component`: 컨테이너가 관리하는 싱글턴 빈이며, `copy`로 만든 인스턴스는 빈이 아니다. `equals/hashCode`도 비즈니스 의미가 없다.
- `@ConfigurationProperties`: 바인딩 방식(생성자 바인딩, 기본값, 중첩, 검증, 메타데이터)을 확인해야 한다. `TossPaymentsProperties`는 Java record + 생성자 바인딩으로 이미 동작하지만, `SpaceImageProperties`는 mutable 기본값을 가진 일반 클래스다.

## 2. 선택과 이유

### 2-1. 적용 범위

| 허용 | 예 | 조건 |
| --- | --- | --- |
| 요청/응답 DTO | `LoginRequest`, `SignupRequest`, `SpaceResponse`, `DoorAccessLogResponse`, `AuditLogResponse` | 필드가 불변이고, 상속·추가 상태가 없고, Java 호출 경계를 확인한다. |
| 내부 결과 객체 | `IssuedRefreshToken`, `LoginResult`, `RefreshResult`, `ChallengeIssueResult`, `VerificationCheckResult` | 서비스 반환값을 값으로 다룬다. Java 호출부가 record 접근자를 쓰면 `@JvmRecord` 또는 동시 전환. |
| 도메인 이벤트 페이로드 | `SlotReleasedRecorder.Payload`, Outbox payload record | JSON 직렬화 필드 이름·타입을 고정하고 왕복 테스트를 둔다. |
| 순수 불변 값 객체 | `SpaceOperatingHoursPolicy.SlotWindow` | 시간 범위처럼 값 동등성이 의미 있고, 연관관계·프록시·생명주기가 없다. Java 호출부가 `.start()`/`.end()`를 쓰면 `@JvmRecord` 또는 동시 전환. |
| `@ConfigurationProperties` | `TossPaymentsProperties` | **예외적 허용**. 생성자 바인딩·기본값·중첩·검증·메타데이터를 파일럿으로 확인한 뒤에만 data class를 쓴다. 기본은 일반 Kotlin class + `val` 생성자 프로퍼티다. |

| 금지/기본 제외 | 이유 |
| --- | --- |
| JPA `@Entity`, `@MappedSuperclass` | DB 행의 정체성, LAZY 프록시, `@MapsId`/`@GeneratedValue`, mutable 상태와 충돌한다. [JPA 결정](kotlin-jpa-entity-open.md)을 따른다. |
| Spring `@Service`, `@Component`, `@Repository`, `@Controller`, `@Configuration` | 컨테이너가 관리하는 빈은 값이 아니라 정체성·생명주기·프록시를 가진다. `copy()`는 관리되지 않는 새 인스턴스를 만든다. 일반 Kotlin class + `val` 생성자 프로퍼티를 쓴다. |
| `@ConfigurationProperties` | 기본은 일반 class. data class는 바인딩 방식 검증 후 예외 허용. mutable 기본값(`SpaceImageProperties.storageRoot`)은 일반 class 유지. |
| 상속·추가 backing field·mutable 프로퍼티가 필요한 타입 | `data class`/`@JvmRecord` 제약과 충돌한다. 일반 class로 작성한다. |
| 값 동등성이 위험한 보안·자격 증명 타입 | `copy()`로 비밀번호·토큰 상태를 복제하면 불변식이 깨질 수 있다. 명시적 class + 팩토리 + 검증을 쓴다. |

### 2-2. Java record 경계 규칙

Java가 Kotlin 타입을 호출하는 방식을 먼저 확인하고, 다음 중 하나를 선택한다.

| Java 호출 방식 | Kotlin 전환 방법 |
| --- | --- |
| `request.email()`, `issued.sessionId()`, `window.start()`처럼 **record 접근자**를 호출 | `@JvmRecord data class`를 붙이거나, 같은 PR에서 Java 호출부를 `getEmail()`/`getSessionId()`/`getStart()`로 바꾼다. 둘 중 하나를 반드시 선택하고, `compileJava`로 확인한다. |
| `SpaceResponse.from(...)`처럼 **정적 팩토리만** 호출 | 일반 `data class`로 바꾸고 정적 팩토리를 유지한다. record 접근자를 쓰는 Java 호출부가 없는지 grep으로 확인한다. |
| Jackson/JSON으로만 사용 | 일반 `data class`로 바꾸고 `ObjectMapper` 왕복 테스트를 둔다. Java 소스 호출부가 없으면 접근자 이름은 문제되지 않는다. |
| `getName()`처럼 **bean getter**를 호출 (원본이 record가 아닌 class) | Kotlin `val name`은 `getName()`을 생성하므로 접근자 호환은 유지된다. 생성자·`copy`·기본값 의미는 별도로 확인한다. |
| Lombok `builder()`를 호출 | 명시적 builder를 만들거나, 생성자/이름 있는 인자로 바꾸고 Java 호출부를 같은 PR에서 전환한다. [Lombok 경계 결정](kotlin-lombok-boundary.md)을 따른다. |

### 2-3. `@JvmRecord`를 쓰는 이유와 제약

`@JvmRecord`는 Kotlin data class를 JVM record로 만들어 Java record 접근자(`name()`, `start()`)와 `java.lang.Record` 상속을 제공한다. Java 호출부를 대규모로 바꾸지 않고 record 호환을 유지할 수 있는 유일한 직접 수단이다.

공식 [Java records 문서](https://kotlinlang.org/docs/jvm-records.html)의 제약은 다음과 같다.

- JVM 16 이상 바이트코드 타깃이 필요하다. 이 프로젝트는 Java 21 toolchain이므로 충족한다.
- `data class`에만 붙일 수 있다.
- 다른 클래스를 명시적으로 상속할 수 없다(인터페이스 구현은 가능). 모든 JVM record가 `java.lang.Record`를 상속하기 때문이다.
- 주 생성자 파라미터에서 초기화되는 프로퍼티 외에 **backing field를 가진 프로퍼티를 추가할 수 없다.**
- mutable backing field 프로퍼티를 가질 수 없다.
- local class가 될 수 없고, 주 생성자 가시성이 클래스 가시성과 같아야 한다.
- **기존 클래스에 `@JvmRecord`를 붙이는 것은 binary-compatible하지 않다.** 접근자 이름 규칙이 `getName()`에서 `name()`으로 바뀐다. 재컴파일하면 컴파일 오류, 이미 컴파일된 Java 바이너리에는 `NoSuchMethodError` 위험이 있다.
- **`@JvmRecord` 클래스는 `final`이어야 한다.** `@Component`/`@Entity`처럼 all-open 대상 애노테이션을 함께 붙이면 all-open이 `final`을 깨뜨려 컴파일 오류가 날 수 있다. record 값 객체에는 Spring/JPA 애노테이션을 붙이지 않는다.

따라서 `@JvmRecord`는 **Java record 접근자 경계를 유지하는 호환 브리지**다. *(2026-10-22 변경: 기존에는 "경계가 있는 타입에만"이었으나, 병렬 작업 일정을 위해 DTO·이벤트 전체로 넓혔다. [2-5](#2-5-2026-10-22-추가-합의)를 따른다.)*

### 2-4. 왜 이 범위인가

1. **값 의미가 있는 타입에만 `data class`를 쓴다.** DTO·이벤트·VO는 필드 값으로 동등성을 판단해도 된다. 엔티티·빈은 정체성과 생명주기가 우선이다.
2. **Java 호출부를 깨뜨리지 않는다.** record 접근자를 쓰는 Java 코드가 많은데, 일반 data class로 바꾸면 `getEmail()`만 생겨 컴파일이 깨진다. `@JvmRecord` 또는 동시 전환으로 경계를 명시적으로 맞춘다.
3. **`@JvmRecord`를 남용하지 않는다.** 추가 상태·상속·mutable 프로퍼티가 필요한 타입에는 쓸 수 없고, binary-compatible하지 않다. JSON/Kotlin 호출만 있는 DTO에는 일반 data class로 충분하다.
4. **Spring Bean과 설정은 값 객체가 아니다.** `@Service` 58개, `@Component` 28개는 컨테이너가 생명주기를 관리한다. `data class`의 `copy`는 새 인스턴스를 만들 뿐 빈 등록·프록시·트랜잭션 경계를 복제하지 않는다. `kotlin-spring`으로 open할 수는 있지만, 값 동등성은 여전히 부적절하다.
5. **`@ConfigurationProperties`는 별도 검증 대상이다.** `TossPaymentsProperties`처럼 Java record + 생성자 바인딩이 이미 동작하는 사례가 있지만, Kotlin data class로 바꿀 때 기본값·중첩·검증·메타데이터가 그대로 동작하는지는 별개다. `SpaceImageProperties`처럼 mutable 기본값이 있는 설정은 일반 class가 자연스럽다.

### 2-5. 2026-10-22 추가 합의

5명이 같은 기간(10/22~10/27)에 병렬로 옮기는 일정이어서, 다른 담당자의 Java 호출부를 건드리지 않는 방식으로 아래 두 가지를 정했다. 사용자 확인: 2026-10-22 grill 세션.

1. **요청/응답 DTO와 이벤트 페이로드는 모두 `@JvmRecord data class`로 옮긴다.**
   - 이유: 일반 `data class`는 `name()`이 `getName()`으로 바뀌어 Java 호출부가 깨진다. 호출부를 같은 PR에서 고치는 방식은 DTO가 여러 담당자의 파일에 흩어져 있어 충돌이 잦다.
   - 예외: 상속·추가 backing field·mutable 프로퍼티가 필요한 타입은 일반 class(2-3의 제약).
   - Java 호출부가 모두 사라진 뒤 `@JvmRecord`를 유지할지 떼어낼지는 전환 완료 후에 정한다(6번 재검토 조건).
   - 기존 2-2 표의 "`@JvmRecord` 또는 호출부 동시 전환" 중 **`@JvmRecord`를 기본으로 고정**한다.
2. **요청 DTO는 Java가 `null`을 받던 필드를 null 가능 타입(`String?`)으로 유지하고 `@field:NotBlank` 등 검증을 그대로 둔다.**
   - 이유: 필드를 `String`(null 불가)으로 바꾸면 Jackson이 Bean Validation보다 먼저 다른 예외를 던져 오류 코드·메시지가 바뀐다. 이번 전환은 HTTP 계약을 바꾸지 않는 것이 목표다.
   - null 불가 타입으로의 개선은 전환이 끝난 뒤 별도 작업으로 한다.
   - 검증: 필드 누락·`null`·빈 문자열 요청의 응답이 전환 전후 같은지 골든 파일 테스트로 확인한다([03 API 계약](../plans/kotlin-migration/03-api-contract.md)).

| 기각한 대안 | 기각한 이유 |
| --- | --- |
| 호출부 동시 전환(일반 `data class`) | DTO가 여러 담당자 파일에 흩어져 있어 병렬 작업 중 충돌이 잦다. |
| 요청 DTO를 null 불가 타입으로 | 누락·null 요청의 오류 응답이 바뀌어 프론트 계약이 깨질 수 있다. |

| 얻는 것 | 잃는 것 | 완화책 |
| --- | --- | --- |
| 담당자 간 충돌 없는 병렬 전환 | Kotlin다운 null 안전성 일부 포기, `@JvmRecord` 제약(상속·추가 상태 불가) | 전환 완료 후 정리 작업으로 재검토 |

### 2-6. 2026-10-22 PR #26 후속 요청: 내부 예약 프로젝션의 non-null 식별자

사용자가 이번 PR의 nullable 선언을 모두 제거하도록 요청했다. `ReservationTargetInfo`는 `Long?` 대신 `Long`을 사용하며 생성자·접근자는 primitive long으로 바뀐다. 실제 JPQL 조회와 Java 소유권 비교 호출부를 함께 검증한다. 이 변경은 내부 프로젝션에 한정하며, 2-5의 HTTP 요청 DTO null/검증 규칙은 유지한다. 선택 이유·대안·검증은 [목요일 non-null 계약](kotlin-thursday-non-null-contract.md)에 기록한다. 아래 최초 전환의 boxed Long 검증은 변경 전 이력이다.

## 3. 기각한 대안과 이유

| 대안 | 장점 | 기각한 이유 |
| --- | --- | --- |
| 모든 Kotlin class를 `data class`로 전환 | 코드가 짧고 `equals/hashCode/copy`가 자동이다. | 엔티티·Spring Bean·`@ConfigurationProperties`·mutable 상태 타입에는 값 동등성이 부적절하다. `copy`는 관리되지 않는 빈/엔티티를 만들고, 프록시·생명주기·불변식을 깨뜨린다. [JPA 결정](kotlin-jpa-entity-open.md)과도 충돌한다. |
| 모든 DTO에 `@JvmRecord`를 일괄 적용 | Java record 접근자 호환이 기계적으로 맞는다. | `@JvmRecord`는 상속·추가 backing field·mutable 프로퍼티를 금지하고, 기존 클래스에 붙이면 binary-compatible하지 않다. Java record 접근자를 쓰지 않는 DTO에는 불필요한 제약이다. 모든 DTO가 record일 필요도 없다. |
| 모든 record를 Java record로 유지하고 DTO는 전환하지 않는다 | Java 호출부를 전혀 건드리지 않는다. | Kotlin 전환 범위를 줄이는 선택이지만, DTO까지 Kotlin으로 옮기려는 목표와 맞지 않는다. 다만 경계 비용이 큰 DTO는 전환을 뒤로 미룰 수 있다. |
| 모든 record를 일반 Kotlin class로 바꾼다 | `@JvmRecord` 제약과 접근자 변경을 피할 수 있다. | `equals/hashCode/copy`를 직접 작성해야 하고, DTO·VO의 간결함을 잃는다. 값 의미가 분명한 타입에는 `data class`가 더 적합하다. 상속·추가 상태가 필요한 타입에만 일반 class를 쓴다. |
| `@ConfigurationProperties`를 전부 data class로 바꾼다 | 설정도 불변 값 객체처럼 표현할 수 있다. | 바인딩 방식(생성자 바인딩, `@DefaultValue`, 중첩, 검증, 메타데이터)이 클래스마다 다르다. `SpaceImageProperties`의 mutable 기본값과 `TossPaymentsProperties`의 생성자 바인딩을 같은 규칙으로 다룰 수 없다. 파일럿 후 예외 허용이 안전하다. |
| 보안·자격 증명 타입도 `data class` + `copy` | 토큰·비밀번호 상태를 쉽게 복제할 수 있다. | `copy`로 만든 인스턴스가 검증·만료·폐기 불변식을 우회할 수 있다. 자격 증명은 명시적 class와 팩토리로 제한한다. |

## 4. Trade-off

| 얻는 것 | 잃는 것(비용·위험) | 완화책 |
| --- | --- | --- |
| DTO·이벤트·VO의 간결한 불변 표현과 값 동등성 | 모든 주 생성자 프로퍼티가 `equals/hashCode/toString`에 들어간다. 큰 DTO나 민감 필드가 있으면 로그·비교가 위험해진다. | DTO를 작게 유지하고, 민감 필드는 `toString`에서 제외하거나 로그에 직접 노출하지 않는다. |
| `copy`·구조 분해·Jackson Kotlin 지원 | `copy`가 불변식을 우회할 수 있다. 검증이 생성자에만 있으면 `copy`로 만든 인스턴스가 잘못된 상태일 수 있다. | `init` 검증, 팩토리, `copy` 사용 제한, 보안 민감 타입은 일반 class로. |
| Java record 접근자 호환(`@JvmRecord`) | `@JvmRecord`는 상속·추가 상태·mutable 프로퍼티를 금지하고, 기존 클래스에 붙이면 binary-compatible하지 않다. | Java 호출부를 먼저 grep하고, 필요한 타입에만 `@JvmRecord`를 붙인다. 호출부 동시 전환도 같은 PR에서 검토한다. |
| Spring Bean과 설정을 값 객체로 오용하지 않음 | DTO 외 타입은 일반 class로 작성해야 해서 보일러플레이트가 늘어난다. | `val` 생성자 프로퍼티와 `kotlin-spring` allOpen으로 일반 class를 간결하게 유지한다. |
| `@ConfigurationProperties`를 파일럿 후 결정 | 설정마다 바인딩 검증이 필요해 전환 속도가 느려진다. | `TossPaymentsProperties`처럼 이미 record인 불변 설정부터 파일럿하고, mutable 설정은 일반 class로 둔다. |

## 5. 검증

아래 1~9는 전체 전환의 **검증 계획**이며, 실제 실행한 타입의 결과를 이어서 기록한다.

1. **`SlotWindow` 경계(`step-1`)**: `SpaceOperatingHoursPolicy`를 Kotlin으로 바꾸고 nested `SlotWindow`를 `@JvmRecord data class`로 선언한다. Java `SpaceSlotAvailabilityService`의 `window.start()`/`window.end()`가 그대로 컴파일되는지 확인한다. `SpaceOperatingHoursPolicyTest` 5개와 `SpaceSlotAvailabilityServiceTest`의 관련 케이스가 통과해야 한다.
2. **Java record 접근자**: `LoginRequest`를 전환할 때 `AuthController`의 `request.email()`/`request.password()`, `IssuedRefreshToken`을 전환할 때 `AuthService`의 `.sessionId()`/`.rawToken()`이 컴파일되는지 확인한다. `@JvmRecord`를 붙이거나 호출부를 같은 PR에서 바꾼다.
3. **중첩 record**: `AdminMemberDetailResponse`처럼 중첩 record를 쓰는 DTO는 `response.member().email()` 호출부를 확인한다. 중첩 타입 각각에 `@JvmRecord`가 필요한지, outer만 필요한지 구분한다.
4. **바이트코드**: `javap -p`로 `@JvmRecord` 타입이 `java.lang.Record`를 상속하고 `start()`, `end()`, `email()` 같은 record 접근자를 생성하는지 확인한다. 일반 data class는 `getStart()` 등이 생성되는지 비교한다.
5. **JSON**: `ObjectMapper`로 DTO를 직렬화·역직렬화하고, 필드 이름·`@JsonFormat`·`@Schema`·기본값이 유지되는지 확인한다. Outbox payload는 저장된 JSON과 새 JSON의 필드 이름·타입이 같은지 golden test로 확인한다.
6. **검증 애노테이션**: `@NotBlank`, `@Email`, `@Size` 등이 생성자 파라미터·필드·getter 중 어디에 적용되는지 확인한다. 필요하면 `@field:`/`@get:`/`@param:` use-site target을 명시한다.
7. **Spring Bean 금지 확인**: Kotlin 소스에서 `@Service`/`@Component`가 붙은 `data class`가 0개인지 grep 또는 ArchUnit으로 확인한다.
8. **`@ConfigurationProperties` 파일럿**: `TossPaymentsProperties`를 Kotlin으로 옮길 때 `ApplicationContextRunner` 또는 `@SpringBootTest`로 바인딩·기본값·검증을 확인한다. `SpaceImageProperties`는 mutable 기본값을 유지하며 일반 class로 둔다.
9. **전체 회귀**: `./gradlew --no-daemon clean build`로 기존 테스트와 새 DTO 경계 테스트가 모두 통과해야 한다. 135개 스위트·1,048건은 10/20 기준선이며, 전환 커밋의 실제 실행 수와 실패/스킵을 기록한다.

### 2026-10-22 `ReservationTargetInfo` 검증 결과

- `feat/kotlin-thursday-policies` 작업 트리에서 내부 JPQL 프로젝션을 `@JvmRecord data class`로 전환했다. `Long?`로 Java 원본의 boxed `Long` 생성자와 null 허용을 보존했다.
- `javap`으로 `java.lang.Record` 상속, `(java.lang.Long, java.lang.Long)` 생성자와 `spaceId()`/`memberId()` 접근자를 확인했다. 기존 Java 서비스·테스트 호출부가 컴파일됐다.
- 실제 MySQL의 `ReservationRepository.findTargetInfoById()` 생성자 프로젝션 테스트가 저장한 공간·회원 식별자를 그대로 반환했다. 선택 실행 70개 중 해당 테스트 1개가 통과했다.
- 진단 문자열은 Java record의 `ReservationTargetInfo[spaceId=..., memberId=...]`에서 Kotlin data class의 `ReservationTargetInfo(spaceId=..., memberId=...)`로 달라진다. 이에 의존하는 호출부는 찾지 못했으며, HTTP 응답·DB 값·이벤트 payload로 쓰지 않는다. 이 형식에 의존하는 관측 도구가 발견되면 재검토한다.
- 전환 후 전체 컴파일·테스트·bootJar 명령은 1,057개 통과(실패·오류·스킵 0), 종료 0이다. 필터 없는 clean build는 기존 ktlint 26건으로 실패했다. 요청/응답 DTO·이벤트·설정 바인딩 전체의 검증을 완료했다는 뜻은 아니다. 전체 회귀와 검증 한계는 [단계 보고](../lab/notes/kotlin-thursday-policies/step-1.md)에 있다.

- **PR 준비 최종 검증:** dev `2186ca1` 반영 후 코드 `1190d04`의 필터 없는 `clean build`가 종료 0(8분 54초)으로 통과했다. ktlint 위반 0, 139 스위트·1,082개 통과(실패·오류·스킵 0), 테스트 수 검사 통과. 초기 ktlint 26건은 dev #21에서 해소됐다. 자기검증이며 독립 검증·CI는 별도다.

## 6. 한계와 재검토 조건

- **Java 호출부가 모두 Kotlin으로 전환될 때**: `@JvmRecord` 호환 브리지가 필요 없어질 수 있다. 그때는 일반 `data class`로 바꾸고 record 접근자 제약을 제거한다.
- **DTO가 상속·추가 backing field·mutable 프로퍼티를 필요로 할 때**: `data class`/`@JvmRecord` 제약을 충족하지 못하므로 일반 class로 전환한다.
- **Kotlin 2.x에서 `@JvmRecord` 동작·안정성이 바뀔 때**: 1.9.25 기준 결정을 그대로 적용하지 않고, 바이트코드·Java 호출부를 다시 검증한다.
- **Jackson/직렬화 프레임워크가 바뀔 때**: data class의 기본값·null 처리·필드 이름 전략을 다시 확인한다.
- **`@ConfigurationProperties` 파일럿이 실패할 때**: data class 예외를 허용하지 않고 일반 class + 생성자 바인딩으로 유지한다.
- **`copy`로 인한 불변식 위반이 발견될 때**: 해당 타입을 명시적 생성자·팩터리가 있는 일반 class로 바꿔 복제 경로에서도 불변식을 지키게 한다.
- **해당 타입의 Java record 호출부를 모두 전환했을 때**: 필요한 바이트코드/JSON 호환성을 확인한 뒤 일반 data class로 단순화할 수 있다. 현재 3차 저장소에도 Java 호출부가 남아 있으므로 저장소 이름을 이유로 `@JvmRecord` 검토를 생략하지 않는다.

## 면접·발표용 정리 (구현 후)

- **30초 설명:** Kotlin `data class`는 값 동등성이 자연스러운 DTO·이벤트·VO에만 쓰고, JPA 엔티티·Spring Bean·설정 빈에는 쓰지 않았습니다. Java가 `email()` 같은 record 접근자로 호출하는 타입은 `@JvmRecord`를 붙이거나 호출부를 같은 PR에서 바꿔, 양방향 컴파일을 유지했습니다.
- **예상 질문과 답:**
  - Q. 모든 DTO에 `@JvmRecord`를 붙이면 안 되나요?
    - A. `@JvmRecord`는 상속·추가 backing field·mutable 프로퍼티를 금지하고, 기존 클래스에 붙이면 접근자 이름이 바뀌어 binary-compatible하지 않습니다. Java record 접근자를 쓰는 타입에만 붙이고, JSON/Kotlin 호출만 있으면 일반 data class로 충분합니다.
  - Q. 왜 Spring Bean에 data class를 쓰지 않나요?
    - A. 빈은 값이 아니라 컨테이너가 관리하는 정체성·생명주기·프록시를 가집니다. `copy()`는 빈 등록·트랜잭션 프록시를 복제하지 않는 새 인스턴스를 만듭니다. 일반 Kotlin class + `val` 생성자 프로퍼티가 맞습니다.
  - Q. `@ConfigurationProperties`는 왜 예외인가요?
    - A. `TossPaymentsProperties`처럼 Java record + 생성자 바인딩이 이미 동작하는 사례가 있지만, Kotlin data class로 바꿀 때 기본값·중첩·검증·메타데이터가 그대로 동작하는지는 별개입니다. 파일럿 후에만 예외 허용하고, mutable 설정은 일반 class로 둡니다.
- **숫자·근거 한 줄:** record 93개, `@Service` 58개·`@Component` 28개, `@Entity` 22개 + `@MappedSuperclass` 1개, `SlotWindow`/`LoginRequest`/`IssuedRefreshToken`의 Java record 접근자 호출을 기준으로 범위를 정했다.
