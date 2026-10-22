---
title: Kotlin 플러그인 버전을 1.9.25로 고정
type: 기술 선택
status: 채택
date: 2026-10-20
tags: [kotlin, gradle, spring-boot, migration]
related: [kotlin-lombok-boundary.md, kotlin-jpa-entity-open.md, kotlin-data-class-scope.md]
---

# Kotlin 플러그인 버전을 1.9.25로 고정

> **상태:** 채택 (2026-10-20). 아직 혼용 빌드 PR을 만들기 전이므로 **구현 전 채택**이며, `kotlin-migration/step-0`의 클린 빌드로 최종 확정한다.
> 기준: `dev` `d1a15eca3228c8b1b1f9260fc7a686860d2b1e44`, Spring Boot 3.3.4, Gradle 8.10.2, Java 21 toolchain.

> 2026-10-22 설명 정정: Kotlin과 Jackson의 버전 체계를 구분하고 테스트 수를 과거 기준선으로 명시했다. 채택일과 구현 전 상태는 유지한다.

## 한 줄 요약

Kotlin 플러그인·stdlib·reflect는 Spring Boot 3.3.4의 Kotlin 버전 **1.9.25**에 맞추고, `jackson-module-kotlin`은 별도의 Jackson BOM 버전 **2.17.2**를 따른다. Java→Kotlin 전환과 툴체인 업그레이드는 같은 PR에 섞지 않는다.

## 1. 문제와 배경

프로젝트는 아직 Kotlin 소스가 없는 순수 Java/Spring Boot 3.3.4 애플리케이션이다. 혼용 빌드를 시작하려면 Kotlin Gradle 플러그인(KGP)을 추가해야 하는데, 다음 세 가지가 서로 다른 곳에서 결정된다.

| 대상 | 버전을 결정하는 곳 | 현재 값 |
| --- | --- | --- |
| Kotlin Gradle 플러그인 | `plugins {}` 블록의 명시적 버전 | 아직 없음 |
| Kotlin stdlib·reflect | Spring Boot 3.3.4 BOM의 `kotlin-bom` | `1.9.25` |
| `jackson-module-kotlin` | Spring Boot가 import하는 Jackson BOM | `2.17.2` |
| JVM 바이트코드 | Java toolchain | 21 |

Spring Boot 3.3.4의 `spring-boot-dependencies-3.3.4.pom`은 `<kotlin.version>1.9.25</kotlin.version>`을 선언하고 `kotlin-bom`을 import한다. 반면 **Gradle 플러그인 버전은 BOM이 관리하지 않는다.** `plugins {}`에 버전을 쓰지 않으면 플러그인 저장소/설정에 따라 다른 버전이 선택될 수 있고, 플러그인은 1.9.25인데 stdlib/reflect는 다른 버전이 되는 불일치가 생길 수 있다.

또한 이 프로젝트는 다음 제약을 가진다.

- 기준선은 **135개 테스트 스위트, 1,048개 테스트 전원 통과**다(2026-10-20, `d1a15ec`). 전환 첫 PR부터 이 기준선을 깨지 않아야 한다.
- Java 21 toolchain을 사용한다. Kotlin도 같은 JVM 타깃(21)으로 컴파일해야 한다.
- 마이그레이션은 **작은 PR 단위로 되돌릴 수 있어야** 한다. 언어 전환과 Spring Boot·Gradle 업그레이드를 한 PR에 섞으면 실패 원인을 분리하기 어렵다.
- Kotlin 코드에서 Spring의 `@Nullable`/`@NonNull` 같은 JSR-305 애노테이션을 엄격하게 해석하려면 `-Xjsr305=strict`가 필요하다.

## 2. 선택과 이유

**Kotlin 1.9.25를 단일 버전으로 고정한다.**

### 2-1. 버전 고정 규칙

| 항목 | 고정 방법 | 값 |
| --- | --- | --- |
| KGP (`org.jetbrains.kotlin.jvm`, `plugin.spring`, `plugin.jpa`) | `plugins {}`에 리터럴로 명시 | `1.9.25` |
| `kotlin-stdlib`, `kotlin-reflect` | Spring Boot BOM의 `kotlin-bom`에 위임 | `1.9.25` |
| `jackson-module-kotlin` | Spring Boot가 import하는 Jackson BOM에 위임 | `2.17.2` |
| JVM 타깃 | `kotlin { jvmToolchain(21) }` | 21 |
| 널 가능성 | 컴파일러 인자 | `-Xjsr305=strict` |

`build.gradle`에는 대략 다음 형태로 들어간다. 실제 추가는 `step-0` PR에서 한다.

```groovy
plugins {
    id 'org.jetbrains.kotlin.jvm' version '1.9.25'
    id 'org.jetbrains.kotlin.plugin.spring' version '1.9.25'
    id 'org.jetbrains.kotlin.plugin.jpa' version '1.9.25'
}

kotlin {
    jvmToolchain(21)
}

tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).configureEach {
    kotlinOptions {
        jvmTarget = '21'
        freeCompilerArgs += '-Xjsr305=strict'
    }
}

dependencies {
    implementation 'org.jetbrains.kotlin:kotlin-reflect'
    implementation 'com.fasterxml.jackson.module:jackson-module-kotlin'
}
```

### 2-2. 왜 1.9.25인가

1. **BOM 정합성**: Spring Boot 3.3.4가 이미 1.9.25를 선택했다. 플러그인만 같은 버전으로 맞추면 stdlib·reflect·직렬화 모듈이 BOM 한 곳에서 관리되어, 버전을 여러 곳에서 덮어쓸 필요가 없다.
2. **전환 리스크의 분리**: 이번 작업의 핵심 위험은 Java→Kotlin 의미 차이(널 가능성, JPA 프록시, Lombok 경계)다. Kotlin 2.x의 K2 컴파일러 전환까지 같은 PR에 넣으면 컴파일 오류의 원인을 언어 전환과 컴파일러 전환으로 나눠야 한다. 1.9.25는 Spring Boot 3.3.x가 검증한 조합에 가깝다.
3. **재현성**: 플러그인·stdlib·reflect·Jackson 모듈을 모두 고정하면 CI와 로컬 빌드가 같은 결과를 낸다. 동적 버전(`latest.release`, 범위 지정)은 사용하지 않는다.
4. **Java 21과의 정합**: `jvmToolchain(21)`과 `jvmTarget = '21'`로 Java toolchain과 동일한 바이트코드를 만든다. Java 21에서 컴파일된 테스트와 Kotlin 코드가 같은 JVM에서 실행된다.
5. **Spring 지원**: `kotlin-reflect`는 Spring의 Kotlin 리플렉션 기반 생성자 바인딩·`KClass` 처리에 필요하고, `jackson-module-kotlin`은 Kotlin data class/기본값 역직렬화에 필요하다. 둘 다 BOM이 버전을 관리하므로 별도 버전 상수를 두지 않는다.
6. **널 가능성 엄격화**: `-Xjsr305=strict`로 Spring의 JSR-305 애노테이션을 Kotlin 타입에 반영한다. Java 경계에서 `PlatformType`이 남는 것을 줄이고, `@Nullable`을 non-null로 잘못 다루는 위험을 컴파일 시점에 줄인다.

### 2-3. Gradle 8.10.2에 대한 알려진 리스크

Kotlin 공식 [Gradle 호환성 표](https://kotlinlang.org/docs/gradle-configure-project.html)는 **Kotlin 1.9.20–1.9.25의 완전 지원 Gradle 범위를 6.8.3–8.1.1**로 적고 있다. 현재 wrapper는 **8.10.2**이므로 공식 완전 지원 범위 밖이다. 공식 문서는 최신 Gradle도 사용할 수 있지만, 그 경우 **deprecation warning이 나오거나 일부 새 기능이 동작하지 않을 수 있다**고 명시한다.

이 사실을 숨기지 않고 `step-0`의 게이트로 삼는다.

- `./gradlew --no-daemon clean build --warning-mode all`로 빌드하고 KGP/Gradle 경고를 PR에 기록한다.
- 치명적 실패가 없으면 1.9.25를 유지한다.
- 치명적 실패가 있으면 **별도 PR/별도 결정**으로 (a) Gradle wrapper를 8.1.1로 낮추는 안, (b) KGP를 2.1.x로 올리는 안을 비교한다. 이번 전환 PR 안에서 몰래 버전을 바꾸지 않는다.

즉 이 결정은 "1.9.25가 Gradle 8.10.2에서도 동작한다"를 가정하지 않는다. **동작 여부를 step-0에서 먼저 측정하고, 실패 시 버전 결정을 다시 연다**는 조건부 채택이다.

## 3. 기각한 대안과 이유

| 대안 | 장점 | 기각한 이유 |
| --- | --- | --- |
| Kotlin 2.0.21 / 2.1.x를 처음부터 도입 | K2 컴파일러, 최신 언어 기능, Gradle 8.10 계열과의 공식 지원 범위가 넓다. | Spring Boot 3.3.4 BOM은 1.9.25를 관리한다. 2.x를 쓰려면 `kotlin-bom`/stdlib/reflect 버전을 BOM 밖에서 덮어써야 하고, Boot 3.3의 Kotlin 지원이 검증한 조합과 달라진다. K2 전환은 컴파일 진단·동작 차이를 함께 만들므로, Java→Kotlin 전환과 동시에 진행하면 실패 원인을 분리하기 어렵다. Spring Boot 업그레이드 후 별도 PR에서 다룬다. |
| 버전을 지정하지 않고 Spring Boot BOM/외부에 위임 | 버전 상수를 줄이고 BOM과 자동으로 맞출 수 있다. | `plugins {}`의 KGP 버전은 Boot BOM이 관리하지 않는다. 버전을 생략하면 Gradle 설정·플러그인 저장소에 따라 선택이 달라질 수 있고, 플러그인과 stdlib/reflect/Jackson 모듈이 어긋날 수 있다. 빌드 재현성과 원인 추적이 나빠진다. |
| Kotlin 1.9.25 + Gradle wrapper를 8.1.1로 다운그레이드 | KGP 1.9.25의 공식 완전 지원 범위 안에 들어간다. | Gradle 다운그레이드는 Kotlin 전환과 무관한 인프라 변경이다. Spring Boot 3.3.4·Testcontainers·CI 캐시·개발자 로컬 환경에 미치는 영향을 별도로 검증해야 한다. 8.10.2에서 실제로 빌드되는지 먼저 확인하고, 실패할 때만 fallback으로 검토한다. |
| Kotlin 1.9.25 + Kotlin Lombok 컴파일러 플러그인 | Java Lombok 멤버를 Kotlin에서 바로 볼 수 있다. | 버전 결정과 별개로 Lombok 경계 문제를 플러그인에 의존하게 된다. 플러그인은 실험적이고 지원 애노테이션이 제한적이며, Kotlin 코드의 Lombok 애노테이션 사용을 가능하게 하지도 않는다. 자세한 기각 이유는 [Lombok 경계 결정](kotlin-lombok-boundary.md)에 둔다. |

## 4. Trade-off

| 얻는 것 | 잃는 것(비용·위험) | 완화책 |
| --- | --- | --- |
| Kotlin 플러그인·stdlib·reflect의 버전 일치와 Jackson BOM 위임 | Kotlin 2.x의 K2 컴파일러·최신 언어 기능을 당장 쓰지 못한다. | 마이그레이션 기간에는 Kotlin 1.9.25를 유지하고, Jackson 모듈은 해당 BOM 버전을 사용한다. 업그레이드는 별도 PR에서 검토한다. |
| 버전 덮어쓰기 없음, 재현 가능한 빌드 | 1.9.25는 2024년 버전으로, 1.9.x 라인의 기능·버그 수정이 더 이상 활발하지 않다. | 전환 기간을 짧게 유지하고, 보안·호환성 이슈가 확인되면 2.x 전환을 앞당긴다. |
| BOM 정합성 | Gradle 8.10.2는 KGP 1.9.25의 공식 완전 지원 범위(≤8.1.1) 밖이다. | `step-0`에서 `clean build --warning-mode all`로 검증하고, 실패 시 wrapper 8.1.1 또는 KGP 2.1.x를 별도 결정한다. |
| `-Xjsr305=strict`로 널 가능성 경계를 명확히 함 | 기존 Java 코드의 암묵적 null 허용이 컴파일 오류로 드러나 전환 PR이 커질 수 있다. | 오류가 난 지점만 명시적 nullable/`@Nullable`로 고친다. 프로젝트 전체에서 `-Xjsr305`를 끄지 않는다. |
| `kotlin-reflect`로 Spring Kotlin 지원을 온전히 사용 | 런타임 의존성과 jar 크기가 늘어난다. | BOM 버전을 사용하고, 리플렉션이 실제로 필요 없는 모듈이 확인되면 그때 제거를 검토한다. |
| `jackson-module-kotlin`으로 data class·기본값 직렬화 | Jackson 모듈이 하나 더 늘어난다. | BOM 버전을 사용하고, DTO 전환 PR마다 JSON 왕복 테스트를 둔다. |

## 5. 검증

아직 구현 전이므로 **검증 계획**이다. `step-0`에서 아래를 확인하고 결과를 PR과 결과 보고서에 남긴다.

1. **클린 빌드**: JDK 21 환경에서 `env -u SPRING_MAIL_USERNAME -u SPRING_MAIL_PASSWORD -u VERIFICATION_MAIL_FROM -u SLOTKEY_KAFKA_ENABLED ./gradlew --no-daemon clean build`가 통과한다. 135개 스위트·1,048건은 10/20 기준선이며, 기존 테스트와 새 경계 테스트를 포함한 해당 커밋의 전체 실행/실패/스킵 수를 기록한다.
2. **태스크**: `compileJava`, `compileKotlin`, `test`, `bootJar`가 모두 실행된다.
3. **의존성 정합**: `./gradlew dependencyInsight --dependency kotlin-stdlib --configuration runtimeClasspath`와 `kotlin-reflect`로 1.9.25가 선택되는지 확인한다. `jackson-module-kotlin`은 2.17.2인지 확인한다.
4. **Gradle 경고**: `--warning-mode all` 출력에서 KGP 1.9.25와 Gradle 8.10.2의 비호환 경고를 수집한다. 경고가 있어도 빌드·테스트가 통과하면 기록 후 유지하고, 실패하면 fallback 결정을 연다.
5. **양방향 호출**: Java가 Kotlin을 호출하는 최소 코드와 Kotlin이 Java를 호출하는 최소 코드를 각각 컴파일한다. `step-0`에서는 `ReservationRefundPolicy` 전환과 그 테스트가 이 역할을 한다.
6. **애플리케이션 기동**: 테스트 프로필이 아닌 현재 프로필로 `bootRun` 또는 동등한 기동 확인을 한다. DB·메일·Kafka 환경 변수가 필요한 경우 테스트 환경에서 검증하고, 운영 비밀값은 사용하지 않는다.
7. **버전 고정 확인**: `git diff`에 `1.9.25` 외의 Kotlin 버전 상수나 동적 버전이 없는지 확인한다.

Kotlin 버전 자체를 검증하는 단위 테스트는 만들지 않는다. 이 결정의 검증 근거는 **빌드·의존성·기동 로그**이며, 기능 회귀는 기존 1,048개 테스트가 담당한다.

## 6. 한계와 재검토 조건

- **Gradle 8.10.2에서 KGP 1.9.25가 실패하거나 치명적 경고를 낼 때**: Gradle wrapper 8.1.1 고정과 KGP 2.1.x 전환을 별도 결정으로 비교한다. 이 문서의 버전을 조용히 바꾸지 않는다.
- **Spring Boot를 3.4/3.5/4.x로 올릴 때**: 새 Boot BOM의 `kotlin.version`을 확인하고, 그 값으로 이 결정을 갱신한다. BOM이 2.x를 관리하면 2.x 전환을 정식으로 검토한다.
- **Kotlin 1.9.x의 보안·호환성 이슈가 확인될 때**: 전환 기간을 줄이거나 2.x로 조기 전환한다.
- **K2 컴파일러의 진단·성능이 필요해질 때**: 전환 완료 후 별도 PR에서 2.x로 올리고, 전체 1,048개 테스트와 빌드 시간을 비교한다.
- **Gradle을 8.10.2 이후로 올릴 때**: KGP 1.9.25의 공식 지원 상한을 다시 확인한다. 상한을 넘으면 KGP 업그레이드를 Gradle 업그레이드와 함께 결정한다.
- **Kotlin 2.3.20+에서 `kotlin-jpa`가 all-open preset을 제공할 때**: [JPA 엔티티 open 결정](kotlin-jpa-entity-open.md)의 수동 `allOpen` 설정을 제거할 수 있는지 재검토한다.

## 면접·발표용 정리 (구현 후)

- **30초 설명:** Spring Boot 3.3.4가 관리하는 Kotlin 1.9.25를 플러그인과 라이브러리의 단일 버전으로 고정했습니다. 언어 전환과 툴체인 업그레이드를 분리해, 첫 혼용 빌드 PR의 실패 원인을 Kotlin 문법·JPA·Lombok 경계로 좁힐 수 있었습니다.
- **예상 질문과 답:**
  - Q. 왜 최신 Kotlin 2.x가 아니라 1.9.25인가요?
    - A. Spring Boot 3.3.4 BOM이 1.9.25를 관리하고, 2.x는 stdlib/reflect를 BOM 밖에서 덮어써야 합니다. 이번 PR의 목적은 언어 전환이지 컴파일러 전환이 아니므로, BOM과 맞는 버전으로 시작하고 2.x는 Boot 업그레이드 후 별도로 검토했습니다.
  - Q. Gradle 8.10.2와 1.9.25가 공식 지원 범위 밖인데 괜찮나요?
    - A. 공식 완전 지원 상한은 8.1.1이라는 점을 문서에 명시했습니다. `step-0`에서 `--warning-mode all`로 실제 빌드를 검증하고, 실패하면 wrapper 8.1.1 또는 KGP 2.1.x를 별도 결정으로 비교합니다. 가정하지 않고 게이트로 둔 것이 핵심입니다.
  - Q. 버전을 BOM에만 맡기면 안 되나요?
    - A. Gradle 플러그인 버전은 BOM이 관리하지 않습니다. 플러그인은 `plugins {}`에 리터럴로 고정하고, stdlib/reflect/Jackson은 BOM에 위임해 두 곳의 역할을 분리했습니다.
- **숫자·근거 한 줄:** Spring Boot 3.3.4 BOM `kotlin.version=1.9.25`, 기준선 1,048개 테스트, KGP 1.9.25의 공식 완전 지원 Gradle 상한 8.1.1(현재 8.10.2는 step-0에서 검증).
