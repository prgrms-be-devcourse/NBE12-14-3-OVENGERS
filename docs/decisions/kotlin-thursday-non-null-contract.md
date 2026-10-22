---
title: 목요일 전환 타입의 필수값을 non-null로 제한한다
type: 정책
status: 채택
date: 2026-10-22
tags: [kotlin, null-safety, java-interop, migration]
related: [kotlin-data-class-scope.md, kotlin-test-guardrails.md]
---

# 목요일 전환 타입의 필수값을 non-null로 제한한다

## 한 줄 요약

사용자의 2026-10-22 후속 요청에 따라 이번 PR의 Kotlin 타입에서 nullable 선언을 제거하고, 외부 PG의 누락 값은 Java 어댑터에서 처리한다.

## 1. 문제와 배경

- 초기 전환은 Java의 null 입력·boxed Long 호환을 보존하려고 정책 인자, 프로젝션, PG 오류 코드와 예외 생성자를 nullable로 선언했다.
- 사용자가 이번 전환 파일의 물음표를 모두 제거하고 커밋·push하도록 요청했다. 이 요청은 해당 타입의 기존 null 보존 결정에 우선한다.
- PG 오류 응답의 코드 누락·파싱 실패는 실제로 가능한 외부 입력이다. 필수값 선언 때문에 결과 모름 처리가 실패해서는 안 된다.
- 범위는 PR #26의 전환 타입과 직접 호출부·테스트다. 다른 담당자의 요청 DTO와 저장소 전체 nullable 정책은 바꾸지 않는다.

## 2. 선택과 이유

- `ReservationTimePolicy`의 모든 시간 인자와 `ReservationTargetInfo`의 식별자를 non-null로 선언한다. 내부 정책에 null을 전달하는 Java 호출은 Kotlin의 인자 검사로 즉시 실패한다. HTTP 요청 검증은 기존 DTO의 Bean Validation 경계를 유지한다.
- 프로젝션의 JVM 생성자·접근자는 boxed Long에서 primitive long으로 바뀐다. 실제 MySQL JPQL 생성자 조회와 기존 Java 호출부를 검증한다.
- `PgErrorClassifier`는 non-null 코드만 받는다. `TossPaymentsPgClient`가 오류 코드 누락을 먼저 판정해 기존 `UNKNOWN/INDETERMINATE`와 null 오류 코드를 반환한다. 빈 문자열·공백 분류는 기존과 같다.
- Outbox 예외의 메시지와 원인은 non-null로 선언하고, 원인이 없는 경우 메시지만 받는 생성자를 사용한다. 더미 원인을 만들지 않는다.
- 기존 null 입력 테스트 4개는 Java에서 non-null 계약 위반을 확인하는 4개로 교체하고, PG 어댑터의 누락 코드 응답 테스트를 추가한다.

## 3. 기각한 대안과 이유

| 대안 | 장점 | 기각한 이유 |
| --- | --- | --- |
| nullable 선언 유지 | 초기 Java null 동작 보존 | 사용자의 명시적인 non-null 요청과 다르다. |
| 물음표만 지우고 호출부 유지 | 변경 줄이 적다 | PG 코드 누락과 원인 없는 예외 생성이 NullPointerException으로 바뀐다. |
| null을 빈 문자열·더미 예외로 치환 | 타입 검사를 통과한다 | 누락과 빈 문자열의 관측 값을 합치거나 실제로 없던 원인을 만든다. |

## 4. Trade-off

| 얻는 것 | 잃는 것(비용·위험) | 완화책 |
| --- | --- | --- |
| Kotlin 필수값 계약과 호출 시점 검사 | 내부 Java null 호출은 비즈니스 예외 대신 NullPointerException | 변경된 계약을 Java 경계 테스트로 명시한다. HTTP 검증 회귀를 실행한다. |
| 식별자 non-null 보장 | JVM 생성자·접근자가 primitive long으로 변경 | 전체 재컴파일·실제 JPQL 프로젝션 테스트 |
| 외부 누락 값과 내부 정책의 구분 | Java PG 어댑터에 누락 분기 추가 | 누락·명시적 null·잘못된 JSON을 Mock 서버로 검증 |

## 5. 검증

검증 계획: Java null 호출 테스트의 실패를 먼저 확인하고, 변경 후 정책·PG 어댑터·Outbox·JPQL 테스트 및 전체 clean build를 실행한다.

검증 결과(구현자 자기검증, 코드 `3c65285`):

- 변경 전 Java null 계약 테스트 4개가 모두 실패했고 변경 후 모두 통과했다.
- 관련 8 스위트·107개 통과. 실제 MySQL JPQL 조회, Java 소유권 비교와 PG 누락 코드 3개도 통과했다.
- `javap`에서 `(long, long)` 프로젝션 생성자와 primitive long 접근자, Outbox의 메시지 전용·원인 포함 생성자를 확인했다.
- 필터 없는 `clean build` 종료 0, 9분 27초. 전체 **140 스위트·1,085개 통과, 실패·오류·스킵 0**, ktlint 위반 0. 테스트 수 검사도 통과했다.
- Kotlin 운영·테스트 소스의 물음표와 이중 느낌표는 모두 0건이다. 외부 실제 PG·SMTP·Kafka, 운영 배포, 독립 검증·새 CI 결과는 이번 로컬 실행에 포함하지 않는다.
- 상세 명령·로그·가정은 [단계 보고](../lab/notes/kotlin-thursday-policies/step-2.md)에 있다.

## 6. 한계와 재검토 조건

- HTTP 요청 DTO나 엔티티 전체의 nullable 규칙은 이번 결정의 대상이 아니다.
- 프로젝션 생성자에 리플렉션으로 boxed Long 시그니처를 요구하는 외부 소비자가 생기면 재검토한다.
- null을 입력으로 처리해야 하는 새로운 외부 경계가 생기면 어댑터에서 명시적으로 처리한다.

## 면접·발표용 정리 (구현 후)

- **30초 설명:** 내부 필수값은 Kotlin의 non-null 타입으로 제한하고, 실제로 값이 누락될 수 있는 PG 응답은 Java 어댑터에서 기존 결과 모름 정책으로 처리한다.
- **예상 질문과 답:** null 허용을 모두 지워도 되는가? 외부 누락 값의 처리를 먼저 확인하고, 필요한 경계 분기를 유지한 뒤 내부 타입을 제한한다.
- **숫자·근거 한 줄:** non-null 계약 4개 RED→GREEN, 선택 107개·전체 1,085개 통과, 스킵 0.
