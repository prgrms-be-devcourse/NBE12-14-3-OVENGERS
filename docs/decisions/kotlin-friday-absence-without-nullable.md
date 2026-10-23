---
title: 금요일 전환 DTO의 값 부재를 nullable 대신 Optional로 표현한다
type: 기술 선택
status: 채택
date: 2026-10-23
tags: [kotlin, dto, optional, java-interop, validation, jackson]
related: [kotlin-data-class-scope.md, kotlin-thursday-non-null-contract.md, kotlin-lombok-boundary.md, kotlin-test-guardrails.md]
---

# 금요일 전환 DTO의 값 부재를 nullable 대신 Optional로 표현한다

> 사용자 요청: 금요일 담당 마이그레이션에서 물음표 없이 값의 부재를 표현하는 별도 설계부터 진행한다.
> 2026-10-23 사용자의 “테스트 고정하고 바로 전환해” 요청으로 첫 예약 묶음의 구현을 시작한다. 아래 최초 설계의 실행 미검증 기록은 당시 상태이며, 실제 결과는 검증 결과에 이어 기록한다.
> 소스 확인 기준: `dev` `4daa7da`. 구현 순서·파일·명령은 [실행 계획](../plans/kotlin-migration/08-friday-optional-dtos.md)에 둔다.

## 한 줄 요약

이번 담당 Kotlin DTO의 필수값은 일반 타입, 실제로 없을 수 있는 값과 검증 전 입력은 non-null `Optional<T>`로 표현하고, JSON과 기존 Java 처리 경계에서만 원래의 값·null로 변환한다.

## 1. 문제와 배경

- 사용자는 nullable 선언과 물음표를 없애고, HTTP·메시지·DB 계약은 유지하도록 요청했다.
- 선택 검색 조건의 생략, 체크인 전 시각, 취소 전 환불액, PG 응답의 누락 정보는 실제로 존재하는 값 부재다. 임의 기본값으로 대체하면 뜻이 바뀐다.
- 필수 HTTP 입력도 검증 전에는 빠지거나 명시적 null일 수 있다. Kotlin 생성자에서 먼저 거절하면 현재 Bean Validation 오류 응답과 달라질 수 있다.
- [기존 data class 결정 §2-5](kotlin-data-class-scope.md)는 HTTP 요청을 nullable로 유지하도록 정했다. 이 제안이 채택되면 **이번 이태호 담당 27개 타입과 직접 호출 경계에만** 그 규칙의 예외를 적용한다. 다른 담당자·엔티티·기존 결정의 이력은 변경하지 않는다.
- [목요일 non-null 결정](kotlin-thursday-non-null-contract.md)은 내부 타입에 대한 결정이므로 이 HTTP 예외의 근거로 확대 해석하지 않는다.
- 언어만 바꾸는 작업보다 Java 호출부 수정이 늘어난다. `@JvmRecord`는 접근자 이름을 보존하지만 `T`가 `Optional<T>`로 바뀌는 반환형까지 보존하지는 않는다.

## 2. 선택과 이유

### 2-1. 표현 규칙

| 의미 | Kotlin 표현 | 예 |
| --- | --- | --- |
| 유효한 상태에서 항상 존재 | `Long`, `Int`, `String`, 시각·enum 타입 | 저장된 예약 ID, 상태, 총액 |
| 실제로 없어도 정상 | `Optional<T>` | 체크인 전 시각, 검색 필터, PG 오류/표시 정보 |
| HTTP 필수값이지만 아직 검증 전 | `Optional<T>` + 기존 제약의 값 추출 검증 | 예약 요청의 공간 ID·날짜, 강제 취소 사유 |
| 목록이 비어 있음 | 기존 `List<T>`의 빈 목록 | 상태 이력·출입 이력 |

- DTO와 이벤트 값 객체는 `@JvmRecord data class`, `PgClient`는 일반 `interface`, `PgWebhookEventResult`는 기존 이름·순서의 `enum class`다.
- `Optional` 객체 자체는 항상 존재해야 한다. `Optional.empty()`가 값 부재이며 `Optional.of(value)`가 존재다.
- 대상 Kotlin 운영·신규 테스트 코드에 nullable 선언, 안전 호출·엘비스 연산, 강제 non-null 단언을 추가하지 않는다. Java 플랫폼 타입을 그대로 저장하거나 unchecked cast로 null을 숨기지 않는다.
- JDK의 `Optional`을 사용한다. 별도 Option 라이브러리·자체 범용 컨테이너·전역 ValueExtractor를 추가하지 않는다.
- 필수값 여부는 DB 제약·실제 생성 경로·서비스 계약으로 판단한다. Java 참조 타입이라는 이유만으로 모든 값을 Optional로 바꾸지 않는다.

```kotlin
@JvmRecord
data class ReservationSearchCondition(
    val status: Optional<ReservationStatus>,
    val keyword: Optional<String>,
)
```

### 2-2. JSON과 입력 검증

**JSON의 형태는 바꾸지 않는다.** `Optional.of(2)`는 `2`, `Optional.empty()`는 기존에 해당 필드가 포함되던 자리에서 JSON `null`이다. `present`·`empty` 같은 객체 필드를 노출하지 않는다.

- 기존 Spring Boot Jackson 구성과 JDK 8 모듈을 사용한다. 캐시에 `jackson-datatype-jdk8:2.17.2`가 있으며 `backend/build.gradle`은 Spring Boot 3.3.4다. 실제 애플리케이션 ObjectMapper에 모듈이 등록되는지는 구현 첫 검증에서 확인한다. 캐시 존재를 런타임 등록 증거로 쓰지 않는다.
- 대상 응답의 Optional 프로퍼티에는 필요 위치를 명시한 `@get:JsonInclude(ALWAYS)`로 기존 null 필드의 포함을 고정한다. 전역 `NON_ABSENT`·ObjectMapper null 정책은 변경하지 않는다. 최상위 `ApiResponse.data` 생략 규칙도 유지한다.
- 입력 누락과 명시적 null은 `Optional.empty()`로 받아 Bean Validation까지 진행한다. 기본 `Optional.empty()` 생성자 인자를 사용하지 않고, Jackson의 누락 값 처리와 Spring 생성자 바인딩으로 빈 Optional을 공급한다. 정상 값의 타입 오류·범위 초과·빈 문자열 coercion은 현재 ObjectMapper 동작을 기준으로 비교한다.
- `Optional` wrapper에 단순 `@NotNull`만 붙이면 내부 값 누락을 검증하지 못한다. 기존 제약에 `payload = [Unwrapping.Unwrap::class]`를 명시한다. 검증 대상은 `@field:`로 둔다. 기존 메시지·groups·숫자 범위·문자열 길이를 유지한다.
- 기본 메시지를 새 문구로 바꾸지 않는다. 다중 오류의 메시지는 기존 handler가 field error들을 합치는 방식까지 확인한다. Kotlin 전환을 이유로 전역 예외 handler를 수정하거나 오류 순서를 임의 정렬하지 않는다.
- 이번 요청 DTO에는 `@AssertTrue`가 없다. 새 조건을 만들어 추가하지 않는다.

```kotlin
@JvmRecord
data class ReservationPayRequest(
    @field:NotNull(payload = [Unwrapping.Unwrap::class])
    val spaceVersion: Optional<Int>,
)
```

`ForceCancelRequest.reason`에는 기존 `NotBlank`, `Size(max = 500)` 둘 다 값 추출 payload를 지정한다. 관리자 검색 공간 ID는 `Positive`만 유지하여 미지정은 허용하고 0·음수만 거절한다. Optional을 사용한다는 이유로 HTTP 필수 여부를 바꾸지 않는다.

### 2-3. 검색 조건과 Java 경계

- `@ModelAttribute`를 유지한다. Spring의 기본 생성자 바인딩과 Optional 변환을 먼저 사용한다. 날짜 포맷은 Kotlin 1.9.25에서 지원되는 명시적 `@field:`·`@param:` 위치로 보존한다. 최신 Kotlin 문서의 `@all:` 문법을 도입하지 않는다.
- 미지정 상태·날짜·공간은 빈 Optional이다. 빈 enum/날짜/숫자와 잘못된 형식은 각각 전환 전 MockMvc 결과와 비교한다. 문자열 keyword는 빈 값·공백·trim 처리 시점을 기존과 같게 둔다.
- 새 argument resolver, 전역 converter, 전역 advice를 선제적으로 추가하지 않는다. 기본 바인딩이 계약 테스트를 만족하지 못하면 차이를 기록하고 이 결정의 변환 경계부터 수정한다.
- 기존 Java 컨트롤러의 `@Valid` 이후 필수값은 `.orElseThrow()`로 꺼낸다. 검증 실패 요청이 이 코드에 도달하지 않는 HTTP 테스트가 필수다. 검증 전 Kotlin 생성자에서 업무 예외를 던지지 않는다.
- 기존 Java 검색 서비스·PG 어댑터·웹훅 Store가 null을 쓰는 경계에는 `.orElse(null)`을 사용해 기존 인자로 넘길 수 있다. 이 처리는 **기존 Java 파일에서만** 허용한다. 나중에 해당 서비스를 Kotlin으로 전환할 때는 Optional을 소비하도록 별도 검토한다.
- Java의 nullable getter/Map 조회/외부 응답 값은 기존 Java 호출부에서 `Optional.ofNullable(value)`로 감싼다. Kotlin 팩토리에서 Java getter를 읽는 경우에도 즉시 `Optional.ofNullable(...)`로 감싸고 non-null 지역변수에 먼저 저장하지 않는다.
- `ReservationListResponse.from`의 공간 인자는 `Optional<Space>`로 바꾼다. 공간 부재 시 기존 이름 `오피스 정보 없음`, 위치·사진 null을 유지한다. 다른 서비스가 전달하는 null을 그대로 Kotlin non-null 인자로 통과시키지 않는다.
- 정적 팩토리는 `companion object` + `@JvmStatic`을 유지하되, null 가능 인자의 반환형·입력형이 Optional로 바뀐 곳은 모든 직접 Java 호출부를 같은 묶음에서 수정한다. 기존 Java 생성자의 null fixture도 빈 Optional로만 치환하며 기대 JSON·업무 결과는 유지한다.

### 2-4. 담당 타입별 적용표

아래 경로는 `com/ovengers/slotkey/` 기준이며, 기본값 대체가 아니라 의미상 부재를 표시한다.

| 대상 | Optional로 표현할 필드·경계 | 일반 non-null로 유지할 필드 |
| --- | --- | --- |
| `reservation/dto/request/ReservationCreateRequest` | `spaceId`, `date`, `startTime`, `endTime` — 검증 전 입력 | 없음. 날짜 변환 메서드는 검증 뒤 `orElseThrow()`로 조합 |
| `ReservationExtendRequest` | `expectedEndTime`, `newEndTime` — 검증 전 입력 | 없음 |
| `ReservationPayRequest` | `spaceVersion` — 검증 전 입력 | 없음 |
| `ForceCancelRequest` | `reason` — 검증 전 입력 | 없음 |
| `ReservationSearchCondition` | `status`, `keyword` — 선택 필터 | 없음 |
| `AdminReservationSearchCondition` | `date`, `spaceId`, `status` — 선택 필터 | 없음 |
| `reservation/dto/response/ReservationResponse` | `holdExpiresAt`, `checkedInAt`, `checkedOutAt`, `cancelledAt`, `spaceVersion`, `refundAmount`, `penaltyAmount` | ID·이용 시각·상태·스냅샷 가격·총액·생성 시각 |
| `ReservationDetailResponse` | `holdExpiresAt`, `checkedInAt`, `checkedOutAt`, `cancelledAt` | 나머지와 `statusHistory` |
| `ReservationListResponse` | `spaceLocation`, `spaceImagePath`, `holdExpiresAt`, `checkedInAt`, `checkedOutAt`, `cancelledAt`; 팩토리의 공간 인자 | 나머지; 공간명 부재 표시는 기존 문구 유지 |
| `AdminReservationResponse` | `memberEmail`, `spaceName` — 관련 행 부재 가능 | 나머지 |
| `AdminReservationDetailResponse` | `memberEmail`, `spaceName`, `cancelledAt`, `checkedInAt`, `checkedOutAt` | 나머지와 이력 목록 두 개 |
| `credit/dto/request/ChargeOrderCreateRequest` | `amount` — 검증 전 Long 입력 | 없음 |
| `ChargeOrderConfirmRequest` | `paymentKey`, `orderId`, `amount` — 검증 전 입력 | 없음 |
| `credit/dto/response/ChargeOrderResponse` | 없음 | 기존 모든 필드 |
| `ChargeOrderConfirmResponse` | 없음 | 기존 모든 필드 |
| `ChargeOrderHistoryResponse` | `paymentMethod`, `paymentProvider` | 나머지 |
| `credit/pg/PgConfirmRequest` | 없음 — 승인 서비스가 만든 필수 입력 | 기존 모든 필드 |
| `PgConfirmationResult` | `paymentKey`, `errorCode`, `unknownReason`, `paymentMethod`, `paymentProvider` | `type` |
| `PgPaymentLookupResult` | `paymentKey`, `orderId`, `totalAmount`, `currency`, `rawStatus` | `status`, `paymentObjectReceived` |
| `PgClient` | DTO 인자·반환은 non-null; 내부 값 부재는 위 결과 객체에 표현 | `confirm`, `findByPaymentKey`의 인자·반환 객체 |
| `credit/webhook/PgWebhookEvent` | `paymentKey`, `reportedStatus`, `retriedCount` | Receiver가 확인한 `transmissionId`, `eventType`, `orderId` |
| `PgWebhookEventResult` | 없음 | 기존 enum 이름·순서 |
| `waitlist/dto/request/WaitlistApplyRequest` | `spaceId`, `startTime`, `endTime` — 검증 전 입력 | 없음 |
| `waitlist/dto/response/WaitlistEntryResponse` | 없음 | 저장 후 반환되는 기존 모든 필드 |
| `notification/dto/response/NotificationResponse` | 없음 | 기존 필드; nullable `readAt`은 `Optional.ofNullable(...).isPresent`로 기존 boolean 도출 |
| `ReadAllResponse` | 없음 | `updatedCount: Int` |
| `UnreadCountResponse` | 없음 | `unreadCount: Long` |

`Optional<Int>`와 `Optional<Long>`를 구분한다. 예약 가격·총액·버전·환불액은 기존 Int, 충전 금액·PG 조회 금액·미읽음 수는 기존 Long 범위를 유지한다. null fixture가 있다는 이유만으로 유효한 저장 객체의 필수 필드를 선택값으로 완화하지 않는다.

### 2-5. 변경 범위와 계약

- Controller·Service·Store·Scheduler·Repository·엔티티의 **Kotlin 전환**은 제외한다. DTO 타입이 바뀌어 필요한 기존 Java 코드의 값 감싸기/꺼내기만 같은 묶음에 포함한다.
- `Reservation`, `ReservationSlot`은 Kotlin이 부르는 getter·builder·생성자의 명시적 Java API가 필요할 때 delombok 범위만 허용한다. 공개 시그니처·JPA mapping·스키마·업무 메서드는 유지한다. 공통 응답·오류의 선행 PR 상태도 실제 구현 전에 확인한다.
- 트랜잭션 애너테이션, 메서드 호출 순서, space/예약/회원 락 순서, 금액 계산, 조건부 UPDATE, 결제 멱등 저장·재응답은 수정하지 않는다. Optional로 꺼낸 값을 기존 인자 자리에 전달한다.
- `ReservationStatusHistoryResponse`, `DoorAccessLogResponse`는 다른 담당 범위다. 이번 응답 목록의 원소 타입·JSON을 그대로 유지한다.
- API 명세와 ERD의 실제 계약은 유지한다. 이번 설계만으로 JSON null을 없애거나 DB NOT NULL을 추가하지 않는다.
- Outbox/Kafka payload 타입은 이번 27개 목록에 없다. 기존 저장 payload·relay·listener를 바꾸지 않고 공유 Jackson 구성이 영향을 받지 않는지 회귀 검증한다.
- PG 승인/조회 상태 판정, `paymentObjectReceived`와 누락 필드의 구분, 웹훅 본문을 승인 근거로 신뢰하지 않는 규칙을 유지한다. Optional 도입을 이유로 불완전 응답을 정상 승인 정보로 보정하지 않는다.

### 2-6. 근거와 확인 수준

아래는 기능 지원의 근거이며 프로젝트 실행 통과의 증거가 아니다.

- [Kotlin 공식 JVM record 문서](https://kotlinlang.org/docs/jvm-records.html): data class의 record 접근자를 제공한다. Optional 반환형 변경에 따른 Java 소스·바이너리 호환은 별도로 처리해야 한다.
- [Jackson 공식 Java 8 모듈](https://github.com/FasterXML/jackson-modules-java8): JDK Optional 지원 모듈이 있다. 로컬 공식 sources artifact `jackson-datatype-jdk8:2.17.2`의 `OptionalDeserializer.getNullValue/getAbsentValue`와 `Jdk8Module`을 읽었다. `configureReadAbsentAsNull` 정책에 따라 누락 처리에 차이가 있으므로 실제 ObjectMapper로 누락·null을 각각 검증한다.
- [Jakarta Bean Validation 3.0 §5.5.1](https://jakarta.ee/specifications/bean-validation/3.0/jakarta-bean-validation-spec-3.0.html#constraintdeclarationvalidationprocess-containerelementconstraints-implicitunwrapping): constraint payload로 내부 값 추출을 지정할 수 있다. 로컬 `hibernate-validator:8.0.1.Final` 소스의 `OptionalValueExtractor`가 빈 Optional을 null로 추출하며 자동 unwrap 기본값은 false인 것을 확인했다.
- [Spring Framework 6.1.13 DataBinder 소스](https://raw.githubusercontent.com/spring-projects/spring-framework/v6.1.13/spring-context/src/main/java/org/springframework/validation/DataBinder.java): 생성자 Optional 인자의 누락 분기를 제공한다. 날짜·숫자·enum의 변환 및 실제 오류 응답은 프로젝트 MockMvc로 검증한다.
- 토스 외부 API의 필드·헤더·엔드포인트·오류 코드는 새로 정의하지 않았다. 이 기록의 PG 필드는 현재 프로젝트 모델을 분석한 것이며 외부 API 신규 조사 결과가 아니다.

## 3. 기각한 대안과 이유

| 대안 | 장점 | 기각한 이유 |
| --- | --- | --- |
| 기존 nullable 유지 | 가장 작은 변경과 자연스러운 Kotlin 표현 | 사용자 요청과 다르다. |
| 값 부재를 0·빈 문자열·최소 날짜·임의 enum으로 대체 | 구현이 단순해 보인다 | 금액 0과 미정, 빈 값과 누락을 합치고 JSON·검색 동작을 바꾼다. |
| 모든 요청 필드를 바로 non-null 값 타입으로 선언 | 간단한 필수값 모델 | 누락·null이 Bean Validation 전에 실패하여 오류 응답을 바꿀 수 있다. |
| 전체 wire DTO를 Java로 남기고 내부 Kotlin DTO를 복제 | 기존 바인딩 보존이 쉽다 | 이번 DTO 전환 목표를 줄이며 모델·애너테이션을 이중 관리한다. |
| 자체 sealed 값 부재 타입 또는 외부 Option 라이브러리 | 상태를 더 자유롭게 표현 | Jackson·Spring·Bean Validation의 별도 통합 비용이 크다. 두 상태만 필요한 현재 범위에서는 JDK Optional보다 이득이 작다. |
| Java 호환 getter가 nullable 값을 계속 반환하도록 우회 | 호출부 변경을 줄인다 | Kotlin 타입이 값 부재를 실제로 다루지 못하고 플랫폼 타입 의존을 숨긴다. |
| 전역 Optional 자동 unwrap·새 전역 null 정책 | 개별 애너테이션이 짧아진다 | 다른 담당 DTO·공통 응답·이벤트까지 영향을 넓힌다. |

## 4. Trade-off

| 얻는 것 | 잃는 것(비용·위험) | 완화책 |
| --- | --- | --- |
| 대상 Kotlin 코드에서 값 부재를 명시하면서 nullable 제거 | Kotlin에서는 nullable보다 Optional 사용이 장황함 | 실제 부재·검증 전 입력에만 제한 |
| 기존 JSON 값·null 유지 | Optional 직렬화 정책·모듈 등록에 의존 | 운영 ObjectMapper 기반 골든 및 다른 Java DTO 회귀 |
| 기존 입력 검증 유지 | 제약별 unwrap payload가 필요; 누락 시 wrapper 검증 오류 | 필드별 누락·null·빈 값·범위 테스트 |
| `@JvmRecord`의 접근자 이름 유지 | Optional로 바뀐 반환형·생성자는 Java 소스/바이너리 비호환 | 한 묶음에서 직접 호출부 수정·전체 재컴파일; 기존 바이너리 혼합 배포 금지 |
| 업무 서비스의 전환 범위 유지 | 기존 Java 경계에는 null이 남음 | Java 파일에서만 변환; 서비스 Kotlin 전환 때 재검토 |

## 5. 검증

### 검증 계획

1. Java 상태에서 도메인 골든과 실제 HTTP의 정상/오류 기준을 먼저 확보한다. 누락·null·빈 값·잘못된 타입·금액 범위·선택 필터·권한을 구분한다.
2. 첫 묶음은 예약 DTO다. Java 기준을 유지한 채 Optional 모델의 입력 값 추출·응답 null·검색 바인딩·Java 호출을 검증한다. 신규 Optional 검증 테스트가 변경 전에 실패하고 변경 후 통과한 근거를 남긴다.
3. 전체 빌드·테스트 수 검사·프론트 검사, 공유 Jackson을 쓰는 PG/Outbox 회귀까지 통과해야 구현 완료다. 하나라도 실행하지 못하면 확인 필요로 보고한다.

### 검증 결과

- 첫 예약 묶음의 요청 6개와 응답 5개를 `@JvmRecord data class`로 전환했다. 필수 저장값은 일반 타입, 검증 전 필수 입력과 실제 선택값은 non-null Optional로 표현했다. 대상 Kotlin DTO에서 `?`, `!!`, Lombok 사용은 0건이다.
- Java 원본에서 고정한 JSON 15건과 HTTP·OpenAPI·Optional 계약을 전환 후 재실행했다. 최종 선택 계약 61건이 실패·오류·스킵 없이 통과했고 프론트 테스트 80건과 타입 검사도 통과했다.
- 실제 `/v3/api-docs` 검사에서 record 요청 본문의 Schema는 getter, 검색 `@ParameterObject`는 field/getter 양쪽에 있어야 기존 설명·예제가 유지됨을 확인했다. 전역 Jackson·Validation·Springdoc 설정은 바꾸지 않았다.
- 전체 `clean build`의 첫 판정에서 테스트 픽스처 2곳의 전환 누락을 발견했다. Optional 단언과 실제 저장 예약의 필수 `createdAt`을 맞춘 뒤 재실행하여 **144 suites, 1,140 tests, failures 0, errors 0, skipped 0**을 확인했다. 기준 검사도 baseline 1,140으로 종료 0이다.
- 별도 읽기 전용 코드 리뷰는 Critical/Important 0건, 검색 OpenAPI 메타데이터 누락 1건을 보고했다. 이를 실제 HTTP 실패 테스트로 재현하고 수정했다. 독립 전체 테스트 재실행·CI·사람 확인은 아직 수행하지 않았다.
- 이 검증은 예약 11개에 한정한다. 나머지 충전·PG·웹훅·대기열·알림 모델은 후속 묶음에서 같은 결정을 적용하고 별도로 검증한다.
- 후속 묶음의 충전·PG·웹훅·대기열·알림 16개도 같은 규칙으로 전환했다. 대상 27개 Kotlin 타입 전체에서 `?`, `!!`, Lombok은 0건이며 전역 Jackson·Validation 설정은 바꾸지 않았다.
- 후속 묶음의 Java 기준 283건과 JSON 골든 15건을 먼저 통과시켰고, Optional 계약 25건의 RED를 확인했다. 최종 전체 `clean build`는 **146 suites, 1,185 tests, failures 0, errors 0, skipped 0**이며 프론트 80건과 타입 검사도 통과했다.
- 별도 읽기 전용 검토가 PG 응답의 `paymentKey` 누락 시 Kotlin NPE가 생기는 회귀 1건을 발견했다. 승인 1건·조회 4건의 실패를 재현한 뒤 Java 어댑터에서 `Optional.ofNullable`로 감싸도록 수정했다. 수정 후 PG 어댑터 44건, 독립 관련 173건, 최종 전체 1,185건이 통과했다.
- 별도 검토의 최종 미해결 Critical·Important·Minor 항목은 0건이다. 독립 실행은 수정 전 전체 1,180건과 수정 후 관련 173건으로 나눠 수행했으며, 수정 후 전체 빌드는 구현 세션에서 수행했다.
- 신규 Kotlin 27개를 기존 Java 소스 루트인 `src/main/java`로 이동한 뒤 현재 코드 HEAD `8cf9cda`에서 최종 검증을 다시 수행했다. JDK 21·Docker 29.6.1 환경의 `clean build`가 8분에 종료 0이었고, **146 suites, 1,185 tests, failures 0, errors 0, skipped 0**, 프론트 80건과 TypeScript 검사 통과를 확인했다.

## 6. 한계와 재검토 조건

- 이 설계는 프로젝트 전체를 null-free로 만들지 않는다. 외부 JSON·DB null과 기존 Java 경계는 유지한다.
- 실제 MockMvc에서 Bean Validation 메시지·누락·빈 쿼리 바인딩·OpenAPI 스키마가 달라지면 동일 계약을 만족할 때까지 전환을 완료 처리하지 않는다. 전역 정책 변경이 필요하면 별도 결정으로 분리한다.
- Java 호출부가 모두 Kotlin으로 옮겨진 뒤 Optional의 비용과 장기 모델을 재검토한다. 그때 nullable 재도입 여부도 사용자 정책에 따라 결정한다.
- 병렬 담당자의 다른 호출 경계까지 업무 변경이 필요하면 묶음을 보류한다. 사용자 요청 없는 다른 도메인 정리는 하지 않는다.

## 면접·발표용 정리

- **30초 설명:** Kotlin DTO에서 nullable을 쓰지 않으면서 JSON 계약을 유지하려고 필수값은 일반 타입, 실제 부재와 검증 전 입력은 Optional로 구분했다. Optional 내부에 기존 제약을 적용하고 Java 경계에서만 감싸거나 꺼냈으며, 골든 JSON·실제 HTTP·OpenAPI와 전체 테스트로 동작이 같음을 확인했다.
- **예상 질문과 답:** 왜 nullable을 그대로 쓰지 않았나? 사용자가 nullable 제거를 요구했으며, 0·빈 문자열 같은 가짜 값으로 계약을 바꾸지 않으려고 표준 컨테이너를 선택했다. 단점은 Java 호출부 수정과 바인딩·검증 테스트 비용이다.
- **숫자·근거 한 줄:** 금요일 대상 27개 전환, 최종 전체 1,185건·프론트 80건 통과, 실패·오류·스킵 0.
