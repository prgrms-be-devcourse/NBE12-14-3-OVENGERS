---
title: Kotlin Lombok 컴파일러 플러그인을 도입하지 않고 전환 경계에서 delombok
type: 기술 선택
status: 채택
date: 2026-10-20
tags: [kotlin, lombok, migration, java-interop]
related: [kotlin-plugin-version.md, kotlin-jpa-entity-open.md, kotlin-data-class-scope.md]
---

# Kotlin Lombok 컴파일러 플러그인을 도입하지 않고 전환 경계에서 delombok

> **상태:** 채택 (2026-10-20). 아직 혼용 빌드 PR을 만들기 전이므로 **구현 전 채택**이며, `kotlin-migration/step-0`·`step-1`의 컴파일·테스트로 확정한다.
> 기준: `dev` `d1a15eca3228c8b1b1f9260fc7a686860d2b1e44`, Java 소스 293개, Lombok import 88개, record 선언 93개.

> 2026-10-22 설명 정정: 현재 3차 저장소에도 미전환 Java 호출 경계가 있음을 명시했다. Lombok 플러그인 미도입 결정과 채택일은 유지한다.

## 한 줄 요약

Kotlin Lombok 컴파일러 플러그인을 쓰지 않고, **Kotlin으로 바꾸는 파일과 Kotlin이 직접 호출하는 Java 경계만 명시적 멤버로 delombok**하며, 아직 전환하지 않은 Java 내부는 해당 파일을 전환할 때까지 Lombok을 유지한다.

## 1. 문제와 배경

현재 백엔드는 Java 293개 소스 중 **88개 파일이 `import lombok`을 사용**하고, **93개 파일에 `record` 선언**이 있다. 엔티티 22개와 `@MappedSuperclass` 1개도 Lombok(`@Getter`, `@NoArgsConstructor` 등)을 쓴다.

Lombok은 Java 컴파일 시점에 getter·생성자·builder를 생성한다. Kotlin 컴파일러는 Kotlin 코드에 붙은 Lombok 애노테이션을 처리하지 않으며, 혼용 모듈에서 Kotlin이 Java의 Lombok 생성 멤버를 직접 보려면 별도의 **Kotlin Lombok 컴파일러 플러그인**이 필요하다. 즉 혼용 빌드에는 다음 두 가지 선택이 있다.

1. Kotlin Lombok 플러그인을 도입해 Kotlin이 Java의 Lombok 생성 멤버를 보게 한다.
2. Kotlin이 호출하는 Java 경계에서 Lombok 생성 멤버를 명시적 코드로 바꾼다(delombok).

프로젝트의 실제 Lombok 사용은 다음과 같다(파일 기준, 한 파일에 여러 애노테이션이 있을 수 있음).

| 애노테이션 | 파일 수 | 주 용도 | Kotlin 경계에서의 의미 |
| --- | ---: | --- | --- |
| `@RequiredArgsConstructor` | 60 | 서비스·컨트롤러 생성자 주입 | Spring이 생성자를 호출하므로 Kotlin이 직접 호출하지 않으면 경계 변경이 필요 없다. Kotlin 테스트가 직접 생성하면 명시적 생성자가 필요하다. |
| `@Getter` | 24 | 엔티티·DTO 필드 접근 | Kotlin이 `getName()` 같은 생성 getter를 호출하면 명시적 getter 또는 클래스 전환이 필요하다. |
| `@NoArgsConstructor` | 18 | JPA 엔티티 기본 생성자 | Hibernate가 리플렉션으로 호출한다. Kotlin이 직접 호출하지 않으면 경계 변경이 필요 없다. |
| `@Value` | 12 | 불변 값 클래스 | Kotlin이 getter/전체 생성자를 호출하면 명시적 멤버가 필요하다. |
| `@AllArgsConstructor` | 10 | 전체 생성자 | Kotlin이 직접 생성하면 명시적 생성자가 필요하다. |
| `@Builder` | 9 | DTO·엔티티 빌더 | Kotlin이 `builder()`를 호출하면 명시적 builder 또는 DTO 전환이 필요하다. |
| `@Slf4j` | 7 | 로거 | `log`는 private이므로 Kotlin 호출 경계가 아니다. |
| `@Setter` | 1 | 가변 설정 빈 | Kotlin이 setter를 호출하면 명시적 setter가 필요하다. |
| `@Data` | 0 | — | 사용하지 않는다. |
| `@SuperBuilder` | 0 | — | 사용하지 않는다. |
| `@With` | 0 | — | 사용하지 않는다. |

또한 record 93개 중 4개는 Lombok을 함께 import한다. 대표적으로 `SpaceResponse`는 `record`이면서 `@Builder(access = AccessLevel.PROTECTED)`를 쓴다. record 접근자는 이미 명시적이지만, builder는 Lombok이 생성하므로 Kotlin이 builder를 호출하면 경계 문제가 된다.

## 2. 선택과 이유

**Kotlin Lombok 컴파일러 플러그인을 도입하지 않고, 전환 경계에서만 delombok한다.**

### 2-1. 경계 원칙

1. **Kotlin으로 바꾸는 파일에서는 Lombok을 제거한다.** Kotlin 파일에는 `@Getter`, `@Builder`, `@RequiredArgsConstructor` 등을 붙이지 않고, `val`/`var` 프로퍼티, 명시적 생성자, 명시적 팩토리, 필요하면 일반 클래스의 `builder`를 쓴다.
2. **Java로 남는 파일이라도 Kotlin이 직접 호출하는 멤버가 Lombok 생성물이면 그 파일을 같은 PR에서 delombok한다.** 예: Kotlin이 `space.getName()`을 호출하는데 `getName()`이 `@Getter`로만 존재하면, `getName()`을 명시적으로 쓰거나 `Space`를 Kotlin으로 전환한다.
3. **Kotlin이 호출하지 않는 Java 내부는 건드리지 않는다.** `@RequiredArgsConstructor`로 Spring이 주입하는 서비스, `@Slf4j` 로거, Hibernate만 쓰는 `@NoArgsConstructor` 엔티티는 해당 파일을 전환할 때까지 Lombok을 유지한다.
4. **컴파일 오류를 경계 탐지기로 쓴다.** Kotlin 컴파일이 “unresolved reference: getName/builder”로 실패하면 그 지점이 경계다. 플러그인을 추가해 오류를 숨기지 않고, 명시적 멤버를 추가하거나 호출부를 같은 PR에서 바꾼다.
5. **delombok은 “Lombok이 생성하던 공개 API를 명시적 코드로 옮기는 것”이다.** 무조건 `delombok` 도구 출력을 그대로 붙이지 않고, 실제 호출되는 멤버만 최소한으로 명시한다. 불필요한 전체 getter/생성자를 한꺼번에 노출하지 않는다.

### 2-2. 왜 이 방식인가

- **플러그인은 목적지가 아니라 다리다.** Kotlin Lombok 플러그인은 Kotlin이 Java의 Lombok 생성 멤버를 *볼 수 있게* 할 뿐, Kotlin 클래스에 Lombok을 쓸 수 있게 하지 않는다. 결국 각 파일을 Kotlin으로 바꿀 때는 명시적 Kotlin 코드가 필요하다. 그렇다면 경계에서 먼저 명시적 API를 만드는 편이 최종 상태와 가깝다.
- **지원 범위가 프로젝트 사용 범위와 완전히 겹치지 않는다.** Kotlin 공식 [Lombok 플러그인 문서](https://kotlinlang.org/docs/lombok.html)의 지원 목록은 `@Getter`, `@Setter`, `@Builder`, `@SuperBuilder`, `@NoArgsConstructor`, `@RequiredArgsConstructor`, `@AllArgsConstructor`, `@Data`, `@With`, `@Value`이며, `@Tolerate`는 지원 계획이 없다. 프로젝트가 쓰는 `@Slf4j`는 목록에 없다. 플러그인을 도입해도 모든 경계가 자동으로 해결되지 않는다.
- **버전 결합을 늘리지 않는다.** Kotlin Lombok 플러그인은 Kotlin 컴파일러 버전과 Lombok/FreeFair 플러그인 설정에 결합된다. 이미 [Kotlin 1.9.25와 Gradle 8.10.2의 호환성](kotlin-plugin-version.md)이라는 리스크가 있으므로, 여기에 Lombok 플러그인 변수를 더하지 않는다.
- **명시적 JVM API가 리뷰와 널 가능성에 유리하다.** Kotlin이 보는 멤버가 Lombok 생성물이 아니라 소스에 존재하는 명시적 시그니처가 되므로, `@Nullable`/`@NonNull`, 반환 타입, 빌더 기본값을 코드에서 바로 확인할 수 있다. 플러그인 뒤에 숨은 생성 API보다 리뷰가 쉽다.
- **전환 PR을 작고 되돌리기 쉽게 유지한다.** 경계 파일만 손대면 PR이 “정책 클래스 1개 + 그 테스트”처럼 작아진다. 88개 파일을 미리 일괄 delombok하면 diff가 커지고, 기능 PR과 충돌하며, revert 범위도 커진다.

### 2-3. 88개 전체를 미리 delombok하지 않는 이유

- 88개 중 60개가 `@RequiredArgsConstructor`다. 서비스·컨트롤러 계층 전체의 생성자를 명시적으로 바꾸는 일은 diff가 크고, 생성자 파라미터 순서·주입 타입이 바뀌면 Spring 컨텍스트 테스트가 광범위하게 흔들린다.
- 93개 record와 88개 Lombok 파일은 겹치지 않는 부분이 많다. record는 이미 접근자가 명시적이므로, Lombok builder 같은 추가 생성물만 경계에서 다루면 된다. 전체 delombok은 record 전환과 Lombok 제거를 한 덩어리로 만들어 리뷰를 어렵게 한다.
- Lombok 생성물의 공개 API를 전부 테스트가 검증하지 않는다. 일괄 delombok에서 builder 기본값·생성자 노출 범위가 달라져도 기존 1,048개 테스트가 잡지 못할 수 있다. 경계 단위로 바꾸면 해당 경계를 호출하는 테스트를 함께 추가할 수 있다.
- 2026-10-20 기준 기능 동결 상태이고, 첫 전환 PR은 순수 정책 클래스다. 안정적인 기준선을 유지하면서 작은 PR로 시작하는 편이 전체 일정에 유리하다.

### 2-4. 2026-10-22 추가 합의: 엔티티 22개는 전환 전에 먼저 delombok한다

2-3은 "88개 전체를 미리 delombok하지 않는다"이다. 이 결정은 유지하되, **JPA `@Entity` 22개(와 `BaseTimeEntity`)만은 예외**로 별도의 작은 PR에서 먼저 delombok한다. 사용자 확인: 2026-10-22 grill 세션.

- 이유: Kotlin 컴파일러는 Lombok이 만드는 `getEmail()` 같은 메서드를 보지 못해, Kotlin 서비스·컨트롤러가 Java 엔티티를 부르면 컴파일이 깨질 가능성이 크다(이 점은 `step-0`에서 실제 컴파일로 먼저 확인한다). 일정상 컨트롤러·서비스(10/24)가 엔티티(10/25)보다 먼저 전환되므로 경계마다 그때그때 delombok하면 담당자들이 서로 막힌다. 엔티티는 거의 모든 도메인이 호출하는 고빈도 경계다.
- 범위: 기능 변경 없음. Lombok이 만들던 공개 getter·생성자·builder를 같은 시그니처로 소스에 적는다. 서비스의 `@RequiredArgsConstructor` 등 엔티티 밖의 Lombok 사용은 기존 2-1·2-3 원칙대로 둔다.
- 머지 시점: 10/23(금) 전. 엔티티 Kotlin 전환(10/25)은 이미 정리된 클래스의 문법 변환이 된다.
- 검증: 변경 전후 `javap -p`로 공개 시그니처가 같음을 확인하고, 전체 `clean build`와 기존 테스트 수가 기준선 이상인지 확인한다.

| 기각한 대안 | 기각한 이유 |
| --- | --- |
| 호출 경계마다 그때그때 delombok(기존 2-1 그대로) | 엔티티 22개는 호출처가 많아 PR마다 중복 작업과 충돌이 생기고, 일정상 엔티티 전환보다 호출부 전환이 먼저 온다. |
| 엔티티를 먼저 Kotlin으로 전환 | 엔티티는 DB 저장·LAZY 프록시 회귀 테스트가 갖춰진 뒤에 옮긴다고 정했다. |

## 3. 기각한 대안과 이유

| 대안 | 장점 | 기각한 이유 |
| --- | --- | --- |
| Kotlin Lombok 컴파일러 플러그인 도입 | Kotlin이 Java의 Lombok getter/builder/생성자를 별도 수정 없이 볼 수 있다. 경계 delombok 작업이 줄어든다. | 실험적 플러그인이고 지원 애노테이션이 제한적이다(`@Slf4j` 미지원, `@Tolerate` 미지원). Kotlin 코드에 Lombok을 쓰게 해주지도 않아 최종 전환 시 명시적 코드가 여전히 필요하다. Kotlin/Lombok/FreeFair 버전 결합과 빌드 복잡도가 늘고, Kotlin 1.9.25 + Gradle 8.10.2 리스크에 변수를 더한다. 생성 API가 플러그인 뒤에 숨어 리뷰·널 가능성 추적이 어려워진다. |
| 전환 전 88개 파일 전체 일괄 delombok | 이후 Kotlin 경계 문제가 한 번에 사라지고, 플러그인 없이 깔끔한 Java API를 얻는다. | 88개 파일(그중 60개 `@RequiredArgsConstructor`)을 한 PR에서 바꾸면 diff·충돌·리뷰 부담이 크고, revert 범위도 전체가 된다. 생성자·builder 기본값·노출 범위 변경을 검증할 테스트가 부족하다. 첫 혼용 빌드 PR의 목적(정책 클래스 1개 전환)과 무관하게 인프라 변경이 커진다. |
| 모든 Java 파일을 먼저 Kotlin으로 바꾼 뒤 Lombok을 제거 | 경계 문제가 사라진다. | 한 번에 전체 전환하는 “빅뱅” 방식이며, 이 프로젝트의 작은 PR·단계별 검증 원칙과 충돌한다. 실패 시 원인 분리가 불가능하고, 3차 신규 저장소의 전체 전환 계획과도 맞지 않는다. |
| record 93개를 모두 `@JvmRecord` data class로 먼저 바꾸기 | record 접근자 호환을 한 번에 맞출 수 있다. | record와 Lombok은 별개 문제이고, 모든 record가 Java 호출 경계를 가지지는 않는다. `@JvmRecord`는 binary-compatible하지 않아 Java 호출부를 함께 바꿔야 하며, 추가 상태·상속 제약이 있다. [data class 범위 결정](kotlin-data-class-scope.md)에서 필요한 곳만 선택한다. |

## 4. Trade-off

| 얻는 것 | 잃는 것(비용·위험) | 완화책 |
| --- | --- | --- |
| 실험적 Lombok 플러그인 없이 명시적 JVM API 유지 | 경계마다 getter/생성자/builder를 손으로 써야 한다. | 전환 파일과 실제 호출 경계만 대상으로 한다. Kotlin이 호출하지 않는 Java 내부는 그대로 둔다. |
| 작은 PR, 쉬운 revert | Java와 Kotlin을 같은 PR에서 수정하는 경우가 생긴다. | PR 설명에 “경계 파일”과 “이유”를 적고, 호출부·테스트를 함께 넣는다. 한 PR에 여러 경계를 몰아넣지 않는다. |
| 컴파일 오류로 경계를 놓치지 않음 | 컴파일 오류가 날 때마다 반복 작업이 발생한다. | 첫 단계는 Lombok이 없는 순수 정책 클래스부터 시작한다. 고빈도 경계는 해당 Java 파일을 Kotlin으로 먼저 전환하는 선택지도 둔다. |
| 널 가능성·시그니처를 소스에서 직접 확인 | Lombok이 제공하던 간결함을 잃고 보일러플레이트가 늘어난다. | 실제 호출되는 멤버만 최소한으로 명시한다. builder가 필요 없으면 정적 팩토리나 생성자로 대체한다. |
| Kotlin/Lombok/FreeFair 버전 결합 없음 | Java 내부에는 당분간 Lombok이 남아 두 스타일이 공존한다. | 전환 파일에서 Lombok import를 제거하고, 미전환 파일은 전환 시점까지 유지한다는 규칙을 PR 체크리스트에 둔다. |

## 5. 검증

아래 1~8은 전체 전환의 **검증 계획**이며, 실행한 범위의 결과는 이어서 별도 기록한다.

1. **첫 전환 PR(`step-0`)**: `ReservationRefundPolicy`는 Lombok을 쓰지 않는 순수 정적 클래스다. 이 파일을 Kotlin으로 바꾸고, 현재 없는 `ReservationRefundPolicyTest`를 새로 추가한다. Java 호출부 `ReservationCancelService`가 그대로 컴파일되는지 확인한다.
2. **`step-1` 순수 정책 3개**: `ReservationTimePolicy`, `DoorAccessTimePolicy`, `SpaceOperatingHoursPolicy`를 전환한다. 기존 테스트 `ReservationTimePolicyTest`(14개), `DoorAccessTimePolicyTest`(9개), `SpaceOperatingHoursPolicyTest`(5개)가 그대로 통과해야 한다.
3. **record 경계**: `SpaceOperatingHoursPolicy`의 nested `SlotWindow`는 Java `SpaceSlotAvailabilityService`가 `window.start()`/`window.end()`로 호출한다. 정책을 Kotlin으로 바꾸면 `@JvmRecord`를 붙이거나 호출부를 같은 PR에서 `getStart()`/`getEnd()`로 바꾼다. 자세한 기준은 [data class 범위 결정](kotlin-data-class-scope.md)을 따른다.
4. **양방향 컴파일**: Kotlin이 Java의 명시적 멤버를 호출하는 코드와 Java가 Kotlin의 `@JvmStatic`/정적 팩토리를 호출하는 코드가 모두 `compileJava`·`compileKotlin`을 통과하는지 확인한다.
5. **전체 회귀**: `./gradlew --no-daemon clean build`로 기존 테스트와 새 경계 테스트가 모두 통과해야 한다. 135개 스위트·1,048건은 10/20 기준선이고, 실제 실행 수와 실패/스킵은 전환 커밋마다 새로 기록한다.
6. **잔여 Lombok 확인**: Kotlin 파일에 `import lombok`이나 Lombok 애노테이션이 0개인지 확인한다. Java 파일의 Lombok import 수는 전환·delombok한 파일만큼 줄어야 하며, 미전환 파일은 그대로여야 한다.
7. **시그니처 확인**: 필요한 경우 `javap -p`로 변환된 클래스의 생성자/getter/builder/정적 팩토리가 의도한 접근 제어자와 시그니처로 존재하는지 확인한다.
8. **Spring 컨텍스트**: `@RequiredArgsConstructor` 서비스를 Kotlin 테스트에서 직접 생성하는 경우, 명시적 생성자 추가 후 `@SpringBootTest` 또는 단위 테스트가 통과하는지 확인한다.

Kotlin Lombok 플러그인을 도입하지 않으므로 “플러그인 설정 테스트”는 없다. 이 결정의 검증은 **컴파일 성공·기존 테스트·경계 호출 테스트**다.

### 2026-10-22 목요일 담당 정책 전환 검증 결과

- 대상: `b263e07`에서 분기한 `feat/kotlin-thursday-policies` 작업 트리. 기존 환불 정책과 나머지 담당 정책·Enum·예외·프로젝션 6개를 검증했다.
- `ReservationTimePolicy`가 사용하는 `BusinessException`의 Lombok `@Getter`를 제거하고 동일한 `getErrorCode()`를 명시했다. Java 생성자·기존 Java 호출부는 유지했다. Java 빌드 후 Kotlin 테스트에서도 기존 getter를 읽을 수 있었으므로 이번 실행을 “Lombok 미해결 참조 재현”으로 해석하지 않는다.
- 선택 테스트 70개(6 스위트) 통과, 실패·오류·스킵 0. `javap`으로 정책 정적 메서드와 예외 생성자·상속 가능성을 확인했다.
- 나노초 검사를 임시로 제거했을 때 새 경계 테스트 2개가 모두 실패했고, 원래 검사를 보존한 Kotlin 전환 후 통과했다. 변이는 최종 코드에 남기지 않았다.
- Kotlin 전환 파일에 Lombok import/애노테이션은 없다. 남은 Java 사용처 때문에 Gradle Java annotation processor는 유지했다. 엔티티 22개 선행 delombok은 이번 범위에서 진행하지 않았다.
- 전환 후 전체 컴파일·테스트·bootJar 명령은 종료 0(137 스위트·1,057개, 실패·오류·스킵 0). 필터 없는 clean build는 기존 ktlint 26건으로 종료 1이다. 전체 실행·기존 ktlint 제약·독립 검증 여부는 [단계 보고](../lab/notes/kotlin-thursday-policies/step-1.md)와 [테스트 결과](../testing/test-results.md)에 기록한다. CI·운영 배포 성공으로 확대 해석하지 않는다.

- **PR 준비 최종 검증:** dev `2186ca1` 반영 후 코드 `1190d04`의 필터 없는 `clean build`가 종료 0(8분 54초)으로 통과했다. ktlint 위반 0, 139 스위트·1,082개 통과(실패·오류·스킵 0), 테스트 수 검사 통과. 초기 ktlint 26건은 dev #21에서 해소됐다. 자기검증이며 독립 검증·CI는 별도다.

## 6. 한계와 재검토 조건

- **경계 delombok이 PR마다 반복적으로 커질 때**: 예를 들어 한 PR에서 10개 이상의 Java 파일을 delombok해야 하거나, 같은 builder를 여러 Kotlin 파일에서 중복 구현해야 하면 Kotlin Lombok 플러그인 파일럿을 별도 브랜치에서 비교한다.
- **Kotlin Lombok 플러그인이 프로젝트의 사용 애노테이션을 모두 지원하고, Kotlin 1.9.25/Gradle 8.10.2에서 안정적으로 동작할 때**: 플러그인 도입을 재검토할 수 있다. 단, `@Slf4j`·`@Tolerate` 등 미지원 항목은 별도 처리해야 한다.
- **Lombok을 프로젝트에서 완전히 제거할 때**: 이 결정은 “전환 경계 delombok”에서 “Lombok 없음”으로 자연스럽게 대체된다. 마지막 Java 파일 전환 후 `import lombok` 0개를 확인한다.
- **Kotlin 2.x/K2로 올릴 때**: Kotlin Lombok 플러그인의 지원 상태와 FreeFair 플러그인 호환성을 다시 확인한다. 1.9.25 기준 결정을 그대로 2.x에 적용하지 않는다.
- **고빈도 Java 클래스(예: 엔티티)를 많은 Kotlin 파일이 호출할 때**: 경계 delombok이 아니라 해당 클래스를 Kotlin으로 먼저 전환하는 편이 나은지 비교한다. JPA 엔티티는 [JPA 엔티티 open 결정](kotlin-jpa-entity-open.md)의 제약을 함께 따른다.
- **현재 3차 저장소에서 전환할 때**: Java 기준선을 복사한 저장소이므로 미전환 Java의 Lombok 호출 경계가 남아 있다. 해당 파일이 Kotlin으로 바뀌거나 명시적 Java API로 정리될 때까지 이 결정을 적용한다. 저장소를 새로 만들었다는 이유로 호환 경계가 사라지지는 않는다.

## 면접·발표용 정리 (구현 후)

- **30초 설명:** Java 88개 파일이 Lombok을 쓰고 있어 Kotlin과 섞으면 생성 getter/builder가 Kotlin에 보이지 않는 문제가 있었습니다. 실험적 Kotlin Lombok 플러그인을 넣는 대신, Kotlin으로 바꾸는 파일과 Kotlin이 직접 호출하는 Java 경계만 명시적 코드로 delombok했습니다. 전환 PR을 작게 유지하고, 최종 Kotlin 코드와 가까운 상태로 갈 수 있었습니다.
- **예상 질문과 답:**
  - Q. Kotlin Lombok 플러그인을 쓰면 더 편하지 않나요?
    - A. 플러그인은 Kotlin이 Java의 Lombok 생성 멤버를 보게 할 뿐, Kotlin 코드에 Lombok을 쓸 수 있게 하지는 않습니다. 결국 파일을 Kotlin으로 바꿀 때 명시적 코드가 필요하고, 지원 애노테이션도 제한적입니다. 플러그인 의존성과 버전 결합을 늘리지 않으려고 경계 delombok을 택했습니다.
  - Q. 88개를 한 번에 delombok하면 더 깔끔하지 않나요?
    - A. 88개 중 60개가 `@RequiredArgsConstructor`라 서비스 계층 전체 생성자가 바뀝니다. diff와 revert 범위가 커지고, builder 기본값·노출 범위 변경을 기존 테스트가 다 검증하지 못합니다. 전환 파일과 실제 호출 경계만 바꾸는 편이 리뷰와 회귀에 안전합니다.
  - Q. 경계를 놓치면 어떻게 되나요?
    - A. Kotlin 컴파일이 unresolved reference로 실패합니다. 런타임이 아니라 컴파일 시점에 드러나므로, 그 지점에 명시적 멤버를 추가하거나 호출부를 바꿉니다.
- **숫자·근거 한 줄:** Java 293개 중 Lombok import 88개, record 93개, `@RequiredArgsConstructor` 60개·`@Getter` 24개·`@Slf4j` 7개를 실측하고, 첫 전환은 Lombok이 없는 `ReservationRefundPolicy` 1개부터 시작한다.
