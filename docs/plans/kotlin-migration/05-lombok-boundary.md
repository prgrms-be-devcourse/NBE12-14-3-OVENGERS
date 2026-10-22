# 05. Lombok 상호 운용 경계

**목적:** Java의 Lombok 생성 API를 Kotlin 컴파일러와 Java 호출부가 안정적으로 사용하도록 전환 단위를 정한다. [상위 계획](00-kotlin-migration-plan.md)의 두 번째 P1 작업이다.

## 현재 경계

2026-10-20 / `d1a15ec`의 운영 Java **293개 중 88개 파일**이 Lombok을 import한다. [`Space`](../../../backend/src/main/java/com/ovengers/slotkey/space/entity/Space.java), [`Reservation`](../../../backend/src/main/java/com/ovengers/slotkey/reservation/entity/Reservation.java), [`Inquiry`](../../../backend/src/main/java/com/ovengers/slotkey/inquiry/entity/Inquiry.java)는 builder·getter·무인자 생성자에 의존한다. [`SpaceResponse`](../../../backend/src/main/java/com/ovengers/slotkey/space/dto/response/SpaceResponse.java)는 `record`이지만 Lombok builder를 사용한다. 서비스/컨트롤러의 `@RequiredArgsConstructor`와 테스트의 `@InjectMocks`도 호출 형태에 영향을 준다.

추가된 PG·Outbox·대기/알림·통계 영역에는 수동 생성자/getter도 섞여 있다. `ChargeOrder`, `WaitlistEntry`, `SpaceRankingSnapshot`, `SlotReleasedHandler` 등의 명시적 API와, `LocalCredential`·인증 엔티티의 Lombok API를 파일별로 구분한다. “추가 파일 전체가 Lombok 의존”이라고 가정하지 않는다.

## 조사와 수정 방법

1. **인벤토리 작성.** `rg -l 'import lombok' backend/src/main/java`로 착수 시 목록을 다시 추출하고 엔티티/DTO/서비스·컨트롤러/기타로 분류한다. record 선언 파일 **93개**와 Lombok 파일 **88개**는 집계 기준이 다르다. 첫 전환 후보의 Lombok 생성 API와 명시적 Java API, Java 테스트 호출부를 구분해 직접 경계만 정리한다.
2. **채택한 경계 처리 적용.** Java 쪽에 명시적 생성자/팩터리·메서드를 추가하거나 해당 클래스를 Kotlin으로 함께 옮겨 직접 호출 경계를 맞춘다. [채택한 Lombok 결정](../../decisions/kotlin-lombok-boundary.md)에 따라 Kotlin Lombok 컴파일러 플러그인은 도입하지 않는다. 경계 수정이 반복적으로 커지면 결정 문서의 재검토 조건에 따라 별도 파일럿·결정 변경을 검토한다.
3. **Java 호환 확인.** `Space.builder()`와 `SpaceResponse.builder()`, `Reservation.createHeld()`, 테스트의 `getXxx()` 호출을 검색한다. Kotlin `data class` 프로퍼티는 Java `record`의 `name()` 접근자를 그대로 만들지 않으므로 [03 계약 문서](03-api-contract.md)에 따라 호출부를 함께 수정한다.
4. **Spring 주입 전환.** Lombok `@RequiredArgsConstructor`를 제거하는 파일은 Kotlin 주 생성자에 모든 의존성을 `private val`로 선언한다. Bean 생성 및 `@Transactional` 프록시 테스트를 실행한다. Java Mockito `@InjectMocks` 테스트는 생성자 변경 후 클린 컴파일과 실제 객체 생성까지 확인한다.
5. **의존성 정리 시점.** Kotlin으로 옮긴 파일의 Lombok 애너테이션·import를 제거한다. 남은 Java 파일이 Lombok을 사용하면 Gradle의 Java annotation processor는 유지한다. 마지막 Java Lombok 의존이 사라진 시점에만 빌드 의존성을 제거한다.

## 선택 기준과 검증

| 선택 | 적용 조건 | 확인할 점 |
| --- | --- | --- |
| 명시적 Java API | 소수의 Kotlin 호출부가 Java Lombok 생성 API를 참조 | Java/Kotlin 양쪽 컴파일, 기존 builder 사용 테스트 유지 |
| 파일 단위 Kotlin 전환 | 해당 클래스와 직접 Java 호출부를 같은 PR에서 갱신 가능 | 생성자·접근자 변경, 직렬화·JPA 동작 및 테스트 수정 |
| Kotlin Lombok 플러그인 | 현재 미도입으로 결정. 기존 결정의 재검토 조건을 충족할 때만 별도 비교 | 도입을 바꾸려면 별도 결정·파일럿이 필요하며 현재 전환 PR의 기본 선택지로 두지 않음 |

`kapt`나 다른 annotation processor를 나중에 추가한다면 Java Lombok 처리 단계가 계속 실행되는지 공식 플러그인 문서와 클린 빌드로 재확인한다. 컴파일 오류를 숨기려고 생성 API를 전역적으로 바꾸지 않는다.

## 결정 문서와 적용 범위

- [Lombok 경계 결정](../../decisions/kotlin-lombok-boundary.md): Kotlin Lombok 컴파일러 플러그인을 도입하지 않고, 전환 파일 또는 Kotlin이 직접 호출하는 Java 경계에서 delombok한다. 미전환 Java 내부는 전환 시점까지 Lombok을 유지한다.
- [data class 범위 결정](../../decisions/kotlin-data-class-scope.md): `data class`는 요청/응답 DTO·이벤트 페이로드·불변 값 객체에 사용한다. Java가 `name()` record 접근자로 호출하는 타입은 `@JvmRecord` 또는 호출부 동시 전환으로 맞춘다. Spring Bean·JPA 엔티티에는 적용하지 않는다. `@ConfigurationProperties`는 일반 `class`가 기본이며, `TossPaymentsProperties`처럼 불변 설정은 바인딩 파일럿을 통과한 경우에만 예외를 검토한다.
- `SpaceOperatingHoursPolicy.SlotWindow`는 중첩 `record`다. Kotlin 전환 시 `@JvmRecord data class`로 `start()`/`end()` 접근자를 보존하거나, record를 별도 Java 파일로 분리한 뒤 정책 클래스만 Kotlin으로 옮긴다. Java 호출부 `SpaceSlotAvailabilityService`·`SpaceOperatingHoursPolicyTest`를 같은 PR에서 컴파일한다.

## 완료 조건

- 첫 전환 범위의 Lombok 경계가 목록화되고 해결 방식이 파일별로 정해진다.
- `cd backend && ./gradlew clean compileKotlin compileJava test`와 최종 필터 없는 `./gradlew clean build`가 통과한다.
- Java 테스트의 builder/getter 사용과 Kotlin 호출이 모두 컴파일·동작한다. 남은 Lombok 의존성은 빌드에 유지된다.
