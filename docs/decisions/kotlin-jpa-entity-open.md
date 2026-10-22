---
title: JPA 엔티티는 kotlin-jpa no-arg와 allOpen으로 열고 data class로 만들지 않는다
type: 기술 선택
status: 채택
date: 2026-10-20
tags: [kotlin, jpa, hibernate, spring-boot, migration]
related: [kotlin-plugin-version.md, kotlin-lombok-boundary.md, kotlin-data-class-scope.md]
---

# JPA 엔티티는 kotlin-jpa no-arg와 allOpen으로 열고 data class로 만들지 않는다

> **상태:** 채택 (2026-10-20). 아직 Kotlin 엔티티를 만들기 전이므로 **구현 전 채택**이며, `kotlin-migration/step-4`·`step-5`의 Hibernate 통합 테스트로 확정한다.
> 기준: `dev` `d1a15eca3228c8b1b1f9260fc7a686860d2b1e44`, `@Entity` 22개, `@MappedSuperclass` 1개(`BaseTimeEntity`), `@Embeddable` 0개.

> 2026-10-22 코드 대조: `Space.version`의 수동 증가·비교와 기존 엔티티 동등성 보존을 명확히 했다. 일반 클래스·no-arg·JPA allOpen 구성과 채택일은 유지한다.

## 한 줄 요약

Kotlin JPA 엔티티는 `kotlin-jpa`의 합성 no-arg 생성자와 JPA 애노테이션 `allOpen`으로 Hibernate가 요구하는 **비-final 클래스·메서드·기본 생성자**를 만족시키고, `@Entity`/`@MappedSuperclass`에는 값 동등성·`copy`·`toString`이 프록시·생명주기와 충돌하는 **`data class`를 쓰지 않는다.**

## 1. 문제와 배경

Kotlin 클래스와 멤버는 기본이 `final`이고, 주 생성자에 모든 필드를 받는 형태가 일반적이다. 반면 Jakarta Persistence(Hibernate)는 다음을 요구한다.

1. 엔티티 클래스가 `final`이 아니어야 한다. 지연 로딩(`LAZY`) 컬렉션·연관관계는 Hibernate가 만든 **프록시 서브클래스**로 초기화를 지연시키기 때문이다.
2. 엔티티에 **no-arg 생성자**가 필요하다. Hibernate가 리플렉션으로 인스턴스를 만들기 때문이다.
3. 지연 로딩 대상 메서드가 `final`이 아니어야 한다. 프록시가 메서드를 오버라이드해 초기화를 가로채기 때문이다.
4. `@Transactional` 같은 Spring AOP도 기본적으로 클래스/메서드가 `open`이어야 프록시를 만들 수 있다.

현재 프로젝트에는 `@Entity` **22개**와 `@MappedSuperclass` **1개**(`BaseTimeEntity`)가 있고, `@Embeddable`은 아직 없다. 엔티티는 Lombok `@Getter`/`@NoArgsConstructor`로 getter와 기본 생성자를 만들고, `BaseTimeEntity`는 `@MappedSuperclass`로 감사 시각을 상속한다. Kotlin으로 옮기면 이 Java/Lombok 기반 메커니즘을 Kotlin 컴파일러 플러그인과 명시적 코드로 대체해야 한다.

특히 다음 엔티티는 `data class` 금지 이유를 잘 보여준다.

| 엔티티 | 구조적 특징 | `data class`가 특히 위험한 이유 |
| --- | --- | --- |
| `RefreshToken` | `@GeneratedValue(IDENTITY)` ID, `Member`·`AuthSession`에 `@ManyToOne(LAZY)` | persist 전후로 ID가 생기면서 `hashCode`가 바뀐다. `Set`/`Map` 키로 쓰면 조회가 깨진다. 프록시와 실제 엔티티의 `equals`도 비대칭이 된다. |
| `LocalCredential` | `@MapsId` 공유 PK, `Member`에 `@OneToOne(LAZY)` | `memberId`와 `member`가 같은 PK를 공유한다. `copy()`로 복제하면 “detached 중복 엔티티”가 되어 persist/merge 의미가 깨진다. |
| `AuthSession` | 생성자에서 UUID ID를 직접 할당, `Member`에 `@ManyToOne(LAZY)` | `revokedAt` 같은 상태가 변한다. 세션은 “같은 값을 가진 행”이 아니라 생명주기를 가진 개체다. |
| `Space` | `version` int를 코드에서 직접 증가, 가격·운영시간이 mutable | `version`은 낙관적 비교의 기준이다. `copy()`는 버전 증가를 우회하거나 중복 상태를 만든다. |
| `BaseTimeEntity` | `abstract` + `@MappedSuperclass`, `createdAt`/`updatedAt` 감사 필드 | `data class`는 `abstract`가 될 수 없고, 상속된 필드는 주 생성자 프로퍼티가 아니라 `equals/hashCode`에서 빠진다. |
| `Reservation`/`ReservationSlot` | 상태·시간이 mutable, 슬롯 UNIQUE 제약과 결합 | 상태 전이와 동시성 잠금이 핵심이다. 값 동등성으로 예약을 비교하면 “같은 값을 가진 다른 예약”을 같은 것으로 취급할 수 있다. |
| `OutboxEvent`/`IdempotencyKey` | ID·생성 시각이 저장 시점에 결정 | ID 할당 전후 `hashCode`가 달라지고, 이벤트 payload의 값 동등성은 행의 정체성과 다르다. |

## 2. 선택과 이유

### 2-1. 채택한 구성

- **`kotlin-jpa` 플러그인**: `@Entity`, `@Embeddable`, `@MappedSuperclass`에 합성 no-arg 생성자를 추가한다.
- **`kotlin-spring` 플러그인**: `@Component`, `@Service`, `@Repository`, `@Controller`, `@Configuration`, `@Transactional`, `@Async`, `@Cacheable` 등 Spring 애노테이션이 붙은 클래스/메서드를 `open`으로 만든다.
- **JPA 애노테이션에 대한 명시적 `allOpen`**: `kotlin-spring`의 preset은 JPA 애노테이션을 포함하지 않으므로, `@Entity`, `@MappedSuperclass`, `@Embeddable`을 별도로 `allOpen`에 등록한다.
- **`data class` 금지**: `@Entity`와 `@MappedSuperclass`에는 `data class`를 쓰지 않는다. `@Embeddable`은 값 객체 성격이 강하지만, 현재 0개이므로 필요해질 때 별도 파일럿으로 허용 여부를 검증한다.

```groovy
plugins {
    id 'org.jetbrains.kotlin.jvm' version '1.9.25'
    id 'org.jetbrains.kotlin.plugin.spring' version '1.9.25'
    id 'org.jetbrains.kotlin.plugin.jpa' version '1.9.25'
}

allOpen {
    annotation('jakarta.persistence.Entity')
    annotation('jakarta.persistence.MappedSuperclass')
    annotation('jakarta.persistence.Embeddable')
}
```

### 2-2. 왜 이 구성인가

1. **`kotlin-jpa`는 no-arg만 담당한다.** 공식 [no-arg 플러그인 문서](https://kotlinlang.org/docs/no-arg-plugin.html)는 `kotlin-jpa`가 `@Entity`, `@Embeddable`, `@MappedSuperclass`에 no-arg 생성을 자동 설정한다고 설명한다. 반면 엔티티를 `open`으로 만들지는 않는다. 따라서 no-arg와 allOpen을 각각 명시해야 한다.
2. **`kotlin-spring`은 JPA 애노테이션을 포함하지 않는다.** 공식 [all-open 플러그인 문서](https://kotlinlang.org/docs/all-open-plugin.html)의 Spring preset은 `@Component`, `@Async`, `@Transactional`, `@Cacheable` 등 Spring 애노테이션을 대상으로 한다. JPA 애노테이션은 preset에 없으므로 `allOpen`에 직접 등록한다. “`kotlin-jpa`를 넣으면 엔티티가 자동으로 open된다”는 가정은 1.9.25에서 틀리다.
3. **Jakarta 애노테이션과 맞는다.** Spring Boot 3는 `jakarta.persistence.*`를 쓴다. Kotlin 1.9.25의 `kotlin-noarg-compiler-plugin` JAR에서 `jakarta.persistence.Entity`, `Embeddable`, `MappedSuperclass` 문자열이 확인되어, `javax.persistence.*`만 지원하는 버전이 아님을 사전 확인했다. 실제 엔티티 전환 PR에서 합성 생성자 생성 여부를 다시 검증한다.
4. **`data class`는 엔티티의 정체성과 충돌한다.** 엔티티는 DB 행의 정체성을 가지며, 필드 값이 같다고 같은 엔티티는 아니다. `data class`의 `equals/hashCode/toString/copy/componentN`은 값 객체에는 유용하지만, 프록시·지연 로딩·ID 할당·상태 전이와 결합하면 아래 문제를 만든다.

| `data class` 생성물 | 엔티티에서 생기는 문제 |
| --- | --- |
| `equals`/`hashCode` | 주 생성자 필드 전체를 사용한다. ID가 persist 후 할당되면 `hashCode`가 바뀌고, `HashSet`/`HashMap` 키가 깨진다. Hibernate 프록시와 실제 엔티티의 `equals`가 비대칭이 된다. |
| `toString` | 연관관계 필드까지 출력하면 지연 로딩이 발생해 `LazyInitializationException`·N+1 쿼리를 유발할 수 있다. |
| `copy` | 같은 ID를 가진 새 인스턴스를 만들어 detached 중복 엔티티가 된다. `@MapsId` 공유 PK(`LocalCredential`)나 `@GeneratedValue` ID에서 특히 위험하다. |
| `componentN` | 내부 필드를 구조 분해로 노출한다. 엔티티의 캡슐화·불변식 유지에 도움이 되지 않는다. |
| `val` 프로퍼티 | Hibernate가 상태를 변경해야 하는 필드에 `val`을 쓰면 JPA 생명주기와 맞지 않는다. `@MapsId`, `@GeneratedValue`, `@Version`/수동 버전 필드는 mutable이어야 한다. |
| 상속·프록시 | [Kotlin data class 규칙](https://kotlinlang.org/docs/data-classes.html)상 다른 클래스를 상속할 수 있지만, 자동 값 비교는 주 생성자 프로퍼티를 기준으로 한다. 엔티티의 프록시·기존 동등성 보존을 위해 일반 클래스를 사용한다. |

5. **명시적 비즈니스 생성자·팩토리를 유지한다.** 합성 no-arg 생성자는 리플렉션 전용이며 Kotlin/Java에서 직접 호출할 수 없다. 따라서 엔티티는 `private`/`protected` 생성자와 정적 팩토리(`create`, `of`)로 불변식을 검증하고, Hibernate는 합성 no-arg로 인스턴스만 만든다. `data class`의 주 생성자 노출과 `copy`는 이 설계를 약화한다.

### 2-3. 엔티티 작성 규칙

- `@Entity`/`@MappedSuperclass` 클래스는 `open class`로 선언하거나 `allOpen`에 맡긴다. 개별 `open`을 수동으로 붙이는 방식은 기본값으로 채택하지 않는다.
- 필드는 `var`를 사용하되, 외부에는 `private set` 또는 변경 메서드만 공개한다.
- 기본 생성자는 `protected`로 두고, 비즈니스 생성자는 필수 값과 불변식을 검증한다. `kotlin-jpa`의 합성 no-arg는 이 생성자와 별개다.
- `equals`/`hashCode`는 기존 엔티티의 동등성 규칙을 유지한다. ID가 같다는 이유만으로 기존 객체 정체성 비교를 바꾸거나 자동 값 비교를 추가하지 않는다. 동등성 변경이 필요하면 별도 검증·설계 변경으로 다룬다.
- `toString`에는 연관관계를 넣지 않는다. 로그에는 ID와 스칼라 필드만 남긴다.
- API 응답에는 엔티티를 직접 노출하지 않고 DTO로 변환한다. DTO는 [data class 범위 결정](kotlin-data-class-scope.md)을 따른다.
- `@Embeddable`은 값 객체이므로 `data class`가 어울릴 수 있지만, no-arg·필드 접근·프록시·변경 감지 요구를 확인한 뒤 별도 파일럿에서 허용한다. 현재는 0개다.

## 3. 기각한 대안과 이유

| 대안 | 장점 | 기각한 이유 |
| --- | --- | --- |
| 모든 엔티티에 수동 `open` + `protected constructor` 작성 | 컴파일러 플러그인 없이 명시적이다. 어떤 클래스가 열려 있는지 소스에서 바로 보인다. | 엔티티 22개와 앞으로 추가될 엔티티마다 반복해야 한다. `open` 하나를 빠뜨리면 컴파일은 통과하고 런타임 프록시/지연 로딩에서 실패할 수 있다. `data class` 금지·생성자 규칙도 수동 규율에 의존한다. 플러그인으로 일관되게 적용하는 편이 안전하다. |
| `kotlin-spring`만 적용 | Spring 빈은 open이 되고 설정이 단순하다. | `kotlin-spring` preset은 JPA 애노테이션을 포함하지 않는다. 엔티티는 여전히 `final`이라 Hibernate 프록시가 실패한다. |
| `kotlin-jpa`만 적용 | no-arg 생성자는 자동으로 생긴다. | `kotlin-jpa`는 all-open을 적용하지 않는다. 엔티티가 `final`로 남아 지연 로딩 프록시가 만들어지지 않는다. |
| 엔티티에 `data class` 허용 | `equals/hashCode/toString/copy`가 자동이고 코드가 짧다. | ID 할당 전후 `hashCode` 변경, 프록시 `equals` 비대칭, `copy`의 detached 중복, `toString`의 지연 로딩, `@MapsId`/`@GeneratedValue`/`@Version`과의 충돌이 있다. 엔티티는 값 객체가 아니라 생명주기를 가진 개체다. |
| `@PersistenceCreator`/생성자 바인딩으로 불변 엔티티 | 불변 객체를 유지할 수 있다. | Spring Data의 constructor binding은 프로젝션·DTO에 적합하고, JPA 엔티티의 프록시·no-arg·변경 감지 요구를 없애지 않는다. Hibernate 엔티티에는 적용되지 않는다. |
| `@Embeddable`까지 전부 `data class`로 | 값 객체 표현이 간결하다. | 현재 `@Embeddable`은 0개이고, JPA가 요구하는 no-arg·필드 접근·변경 감지와 `data class`의 불변성·`copy`가 항상 맞지는 않는다. 필요해질 때 파일럿으로 검증한다. |

## 4. Trade-off

| 얻는 것 | 잃는 것(비용·위험) | 완화책 |
| --- | --- | --- |
| Hibernate가 요구하는 비-final 클래스·메서드·no-arg 생성자를 일관되게 확보 | 컴파일러 플러그인 두 개(`kotlin-jpa`, `kotlin-spring`)와 명시적 `allOpen` 설정이 빌드에 추가된다. | `step-0`에서 플러그인 구성을 고정하고, `javap`·Hibernate 통합 테스트로 검증한다. 설정을 여러 모듈에 중복하지 않는다. |
| 지연 로딩·`@Transactional` 프록시가 정상 동작 | `allOpen`이 엔티티의 모든 멤버를 open으로 만들어 캡슐화가 약해진다. | 필드는 `private`, 변경은 의도한 메서드로 제한한다. open은 프록시 요구이지 public API 확장이 아니다. |
| `data class`의 값 동등성·`copy` 사고를 피함 | `equals/hashCode/toString`을 직접 작성해야 하고, 엔티티 코드가 길어진다. | ID 기반 비교 규칙을 정하고, `toString`은 ID·스칼라 필드만 출력한다. 공통 베이스 클래스나 유틸로 반복을 줄인다. |
| 합성 no-arg가 리플렉션 전용이라 불변식을 우회하지 않음 | Hibernate가 만든 인스턴스는 필드가 null/0일 수 있으므로, 생성자 검증만 믿을 수 없다. | 비즈니스 생성자·팩토리에서 검증하고, `@Column(nullable = false)`·Bean Validation·DB 제약을 함께 둔다. |
| `@MapsId`, `@GeneratedValue`, 수동 버전 필드를 그대로 사용 | `data class`의 간결함을 포기한다. | 엔티티는 명시적 클래스로 작성하고, 외부 표현은 DTO로 분리한다. |

## 5. 검증

아직 Kotlin 엔티티를 만들기 전이므로 **검증 계획**이다. `step-4`/`step-5`에서 아래를 확인한다.

1. **바이트코드**: Kotlin으로 전환한 엔티티에 `javap -p`를 실행해 (a) 클래스가 `final`이 아닌지, (b) 지연 로딩 대상 메서드가 `final`이 아닌지, (c) 합성 no-arg 생성자가 존재하는지 확인한다.
2. **Jakarta 인식**: `@Entity`가 `jakarta.persistence.Entity`일 때 `kotlin-jpa`가 no-arg를 생성하는지 확인한다. 1.9.25 플러그인 JAR의 `jakarta.persistence.*` 문자열은 사전 확인했지만, 실제 컴파일 결과로 다시 검증한다.
3. **Hibernate 생명주기**: MySQL 8.4/Testcontainers와 Flyway V1~V26 스키마에서 `persist` → `flush` → `clear` → `find`를 수행하고, `@GeneratedValue(IDENTITY)` ID가 persist 후 할당되는지 확인한다.
4. **지연 로딩 프록시**: `AuthSession`/`RefreshToken`/`LocalCredential`의 `@ManyToOne(LAZY)`·`@OneToOne(LAZY)`를 트랜잭션 안에서 접근해 초기화되는지, 트랜잭션 밖에서는 `LazyInitializationException`이 발생하는지 확인한다. 프록시 `equals`가 비대칭이 되지 않는지도 확인한다.
5. **공유 PK**: `LocalCredential`을 `@MapsId`로 저장하고, `memberId`와 `member`의 PK가 일치하는지, `copy` 없이 변경 메서드로 비밀번호를 바꾸는지 확인한다.
6. **감사·버전**: `BaseTimeEntity`의 `createdAt`/`updatedAt`이 `Clock` 기반으로 기록되는지 확인한다. `Space.version`은 가격 변경 시 직접 증가하고 예약 결제 경로에서 비교하는 int 값이다. 기본값 0, 수동 증가·비교와 공간의 기존 비관적 잠금을 유지한다. 현재 JPA `@Version`은 사용하지 않는다.
7. **`@Transactional` 프록시**: `@Transactional`이 붙은 Kotlin 서비스의 메서드가 final이 아니어서 트랜잭션 롤백이 동작하는지 확인한다.
8. **정적 검사**: Kotlin 소스에서 `@Entity`/`@MappedSuperclass`가 붙은 `data class`가 0개인지 grep 또는 ArchUnit 규칙으로 확인한다.
9. **전체 회귀**: `./gradlew --no-daemon clean build`로 기존 테스트와 새 영속성 테스트가 모두 통과해야 한다. 135개 스위트·1,048건은 10/20 기준선이며 전환 커밋의 실제 실행 수와 실패/스킵을 새로 기록한다. 엔티티 전환은 `step-4`부터 시작하고 첫 PR은 순수 정책 클래스다.

## 6. 한계와 재검토 조건

- **Kotlin 2.3.20+에서 `kotlin-jpa`가 all-open preset을 제공할 때**: 공식 문서/릴리스 노트를 확인하고, 그때는 수동 `allOpen` JPA 설정을 제거할 수 있는지 재검토한다. 1.9.25의 결정을 그대로 2.x에 적용하지 않는다.
- **Hibernate 7/Spring Boot 4로 올릴 때**: 프록시·no-arg·`@Transactional` 요구가 바뀌는지 확인하고, `allOpen` 대상과 엔티티 작성 규칙을 갱신한다.
- **`@Embeddable`을 실제로 도입할 때**: 값 객체가 no-arg·필드 접근·변경 감지와 충돌하지 않는지 파일럿 테스트 후 `data class` 허용 여부를 별도 결정한다.
- **엔티티 수가 크게 줄거나 전환을 마쳤을 때**: 수동 `open`/생성자 규칙이 더 단순한지 비교한다. 현재 3차 저장소에서는 Java 기준선을 단계별로 옮기므로 기존 매핑·생성자·호출 경계를 확인한다.
- **컴파일러 플러그인이 K2/Kotlin 2.x에서 동작하지 않거나 빌드 시간이 문제가 될 때**: 수동 `open` + `protected constructor` fallback을 검토한다. 단, 누락 시 런타임 실패 위험이 있으므로 정적 검사·통합 테스트를 함께 둔다.
- **`@Access(AccessType.PROPERTY)`로 전환할 때**: 필드 접근과 getter 접근의 프록시/변경 감지 차이를 확인하고, `allOpen` 대상 메서드를 재검토한다.

## 면접·발표용 정리 (구현 후)

- **30초 설명:** Kotlin 클래스는 기본이 final이고 no-arg 생성자가 없어서 JPA/Hibernate 프록시가 깨질 수 있습니다. `kotlin-jpa`로 합성 no-arg를 만들고, `@Entity`·`@MappedSuperclass`·`@Embeddable`을 `allOpen`에 등록해 비-final 클래스·메서드를 확보했습니다. 엔티티는 값 객체가 아니라 생명주기를 가진 개체라서 `data class`는 금지하고, ID·상태 변경을 명시적으로 관리했습니다.
- **예상 질문과 답:**
  - Q. `kotlin-jpa`를 넣으면 엔티티가 자동으로 open되지 않나요?
    - A. 아닙니다. `kotlin-jpa`는 no-arg 생성자만 담당합니다. Spring preset도 JPA 애노테이션을 포함하지 않아서, `@Entity`/`@MappedSuperclass`/`@Embeddable`을 `allOpen`에 직접 등록했습니다. 이 차이를 모르면 컴파일은 통과하지만 런타임 지연 로딩에서 실패합니다.
  - Q. 왜 엔티티에 `data class`를 쓰지 않나요?
    - A. `equals/hashCode`가 주 생성자 필드 전체를 사용해 ID 할당 전후로 값이 바뀌고, `toString`은 지연 로딩을 유발하며, `copy`는 같은 ID의 detached 중복을 만듭니다. `@MapsId` 공유 PK와 `@GeneratedValue` ID에서 특히 위험합니다. 엔티티는 값이 아니라 DB 행의 정체성입니다.
  - Q. `@MapsId`나 `@GeneratedValue`는 어떻게 검증했나요?
    - A. MySQL/Testcontainers에서 persist → flush → clear → find 흐름과 지연 로딩 프록시를 통합 테스트로 확인하고, `javap`으로 합성 no-arg와 non-final 메서드를 확인합니다.
- **숫자·근거 한 줄:** `@Entity` 22개 + `@MappedSuperclass` 1개, 1.9.25 `kotlin-noarg` JAR에서 `jakarta.persistence.*` 확인, 전체 1,048개 테스트 기준선.
