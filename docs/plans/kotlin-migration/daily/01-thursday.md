# 1. 목요일(10/22) — Kotlin을 쓸 수 있게 준비하기

> 기존 단계: step-0 → step-1 · 앞으로 할 일을 정리한 계획입니다.
> [0. 팀 공통 안내](00-overview.md) · 다음: [2번 계획](02-friday.md)

## 오늘의 목표

**Java와 Kotlin을 함께 빌드할 수 있게 만들고, 계산 규칙과 상태값부터 Kotlin으로 옮깁니다.**

## 시작 전에 확인할 것

오전에는 이태호가 빌드 설정과 환불 계산 코드 하나를 먼저 옮깁니다. 이 PR이 공통 개발 브랜치에 합쳐지기 전까지 다른 팀원은 기존 테스트를 확인하고 빠진 사례를 보강합니다. 빌드 설정이 합쳐지면 아래 전환을 진행합니다.

## 착수 순서 (2026-10-22 합의)

아래 순서로 작은 PR을 머지한 뒤 담당자별 전환을 시작합니다. 자세한 근거는 [문서 안내의 선행 작업](../README.md#착수-전-선행-작업-2026-10-22-합의)을 봅니다.

1. ktlint 도입 → 2. 새 기준선 측정과 CI 테스트 수 검사 → 3. `step-0` → 4. 엔티티 22개 delombok(금요일 전) → 5. 골든 파일 테스트

## 담당자별 할 일

| 담당 | 할 일 | 확인할 것 |
| --- | --- | --- |
| 김재철 | 공통 응답·오류, 공간 운영시간 규칙, 감사·통계 상태값을 옮깁니다. | 공통 코드를 쓰는 Java 호출부와 응답 JSON이 유지되는지 봅니다. 오전에는 공간·감사·통계의 부족한 테스트를 찾습니다. |
| 박창현 | 출입 시간 규칙·결과값, 토큰 생성·해시 계산 코드를 옮깁니다. | 체크인/재입장 경계 시각을 확인합니다. 오전에는 비활성 회원 거절 기록과 보안 필터·세션 테스트도 확인합니다. |
| 백한비 | 크레딧 내역의 종류와 충전 주문 상태값을 옮깁니다. | 오전에는 잔액 초과 동시 차감, 환불/위약금 기록, 중복 요청 테스트에서 빠진 사례를 찾습니다. |
| 이태호 | 혼용 빌드와 환불 계산을 먼저 옮기고, 이후 예약 시간 규칙·상태값·PG 오류 분류를 옮깁니다. | Java↔Kotlin 호출과 실행 JAR을 확인합니다. 환불 시각 경계와 홀수 금액의 계산 결과를 유지합니다. |
| 천종원 | 회원 역할·상태, 인증 목적, 문의 상태값, DB를 쓰지 않는 검증·인증값 생성 코드를 옮깁니다. | 오전에는 문의 소유권, 토큰 재사용 시 세션 폐기, 관리자 회원 상세와 실제 DB 검증을 확인합니다. |

## 오늘 완료했다고 말하려면

- 첫 빌드 PR이 합쳐지고 Java·Kotlin 컴파일, 테스트, 실행 JAR 생성이 성공해야 합니다.
- 환불 계산은 정확히 시작 1시간 전, 그 직후, 시작 정각, 홀수 금액에서 기존 결과와 같아야 합니다.
- 옮긴 정책의 테스트와 Java 호출부가 통과하고, 공통 응답 JSON도 기존과 같아야 합니다. CI나 실제 기동을 못 확인했다면 그 항목은 따로 남깁니다.
- PR마다 [공통 안내의 전체 빌드·필요한 프론트 검사](00-overview.md)를 실행합니다.

## 다음 날 공유할 내용

금요일에는 합쳐진 PR/커밋, 옮긴 정책과 남은 정책, 공통 응답에 영향이 있는 부분, 보강할 테스트와 담당자를 공유합니다. 첫 빌드가 실패하면 다음 전환보다 빌드 문제 해결을 먼저 합니다.

## 구현할 때 참고

- 공통 응답·오류 PR을 먼저 합칩니다. `BaseTimeEntity`는 일요일, PG 설정은 토요일에 옮깁니다.
- 설정은 [혼용 빌드 계획](../04-mixed-build.md)을 따릅니다. Kotlin 1.9.25·Spring/JPA/allopen·reflect·Jackson 모듈과 Kotlin 소스/테스트 경로를 준비합니다.
- 대표 대상: `ResponseStatus`, `ApiResponse`, `PageResponse`, `ErrorCode`, `BusinessException`과 필요한 예외 처리 호출부, `SpaceRegion`, `SpaceStatus`, `SpaceOperatingHoursPolicy`, 감사/통계 Enum; `AccessDenyReason`, `AccessResult`, `DoorAccessTimePolicy`, 토큰 generator/hasher; `CreditTransactionType`, `ChargeOrderStatus`.
- 이태호: `ReservationRefundPolicy`와 전용 테스트, `ReservationTimePolicy`, `ReservationStatus`, `ReservationTargetInfo`, `PgErrorClassifier`, `OutboxPublishException`, `WaitlistStatus`. 천종원: 회원/인증/문의 Enum과 비영속 검증기·secret generator/hasher.
- 첫 PR은 `backend/`에서 `./gradlew clean compileKotlin compileJava test bootJar` 후 `./gradlew clean build`를 확인합니다. 정책 테스트와 `javap`/호출부 컴파일로 정적 메서드·`SlotWindow.start()/end()`를 확인합니다.
- [Lombok 처리 계획](../05-lombok-boundary.md)에 따라 Kotlin이 직접 사용하는 Java 생성 메서드부터 필요한 만큼 정리합니다.
