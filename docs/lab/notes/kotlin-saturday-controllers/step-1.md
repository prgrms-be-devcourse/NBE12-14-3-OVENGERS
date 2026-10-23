# Kotlin 토요일 컨트롤러 전환 — 단계 A 보고

## 진행 결과

- `ReservationController`, `AdminReservationController`를 Java에서 Kotlin으로 전환했다.
- HTTP 경로, 요청/응답 래퍼, 상태 코드, 검증, 인증 주체 전달, 페이징과 OpenAPI 애노테이션을 유지했다.
- 공통 보안 코드 Kotlin PR이 아직 `dev`에 없지만, 사용자가 2026-10-23에 기존 Java 보안 코드와의 혼용 진행을 승인했다.
- 단계 B 이후는 진행하지 않았다.

## 1. 변경 파일

### 코드 커밋 대상

- 삭제: `backend/src/main/java/com/ovengers/slotkey/reservation/controller/ReservationController.java`
- 추가: `backend/src/main/java/com/ovengers/slotkey/reservation/controller/ReservationController.kt`
- 삭제: `backend/src/main/java/com/ovengers/slotkey/reservation/controller/AdminReservationController.java`
- 추가: `backend/src/main/java/com/ovengers/slotkey/reservation/controller/AdminReservationController.kt`

### 문서 커밋 대상

- 추가: `docs/lab/notes/kotlin-saturday-controllers/step-1.md`
- 로컬 `.git/info/exclude`가 `docs/lab/`을 제외하고 있어, 사용자 승인 후 문서 커밋에는 `git add -f`가 필요하다.

현재 브랜치: `feat/kotlin-saturday-reservation-controllers`

## 2. 실행한 명령과 결과

### Java 기준선

```bash
env -u SPRING_MAIL_USERNAME -u SPRING_MAIL_PASSWORD -u VERIFICATION_MAIL_FROM -u SLOTKEY_KAFKA_ENABLED \
  ./gradlew --no-daemon test \
  --tests '*ReservationControllerTest' \
  --tests '*ReservationSecurityIntegrationTest' \
  --tests '*ReservationSearchSecurityIntegrationTest' \
  --tests '*ReservationDtoContractTest' \
  --tests '*ReservationOpenApiContractIntegrationTest'
```

- 결과: `BUILD SUCCESSFUL`, 종료 코드 0
- 테스트: 57개 실행, 57개 통과, 실패 0개, 오류 0개, 스킵 0개

### 전환 중 컴파일

```bash
./gradlew --no-daemon compileJava compileKotlin
```

- 1차 결과: 실패, 종료 코드 1
- 원인: Kotlin KDoc 안의 `"/api/v1/admin/**"`가 중첩 주석 시작으로 해석됨
- 조치: 런타임과 무관한 KDoc 표현을 `"/api/v1/admin/" 하위 경로`로 변경
- 2차 결과: `BUILD SUCCESSFUL`, 종료 코드 0

### 전환 후 계약 테스트

Java 기준선과 같은 테스트 필터로 재실행했다.

- 결과: `BUILD SUCCESSFUL`, 종료 코드 0
- 테스트: 57개 실행, 57개 통과, 실패 0개, 오류 0개, 스킵 0개

### 스타일 검사

```bash
./gradlew --no-daemon ktlintCheck
```

- 1차 결과: 실패, 종료 코드 1
- 원인: 두 Kotlin 파일의 `OpenApiResponse` 별칭 import 위치
- 조치: 별칭 import를 import 블록 마지막으로 이동
- 2차 결과: `BUILD SUCCESSFUL`, 종료 코드 0

### 최종 강제 재실행 검증

```bash
env -u SPRING_MAIL_USERNAME -u SPRING_MAIL_PASSWORD -u VERIFICATION_MAIL_FROM -u SLOTKEY_KAFKA_ENABLED \
  ./gradlew --no-daemon --rerun-tasks \
  compileJava compileKotlin test \
  --tests '*ReservationControllerTest' \
  --tests '*ReservationSecurityIntegrationTest' \
  --tests '*ReservationSearchSecurityIntegrationTest' \
  --tests '*ReservationDtoContractTest' \
  --tests '*ReservationOpenApiContractIntegrationTest' \
  ktlintCheck
```

- 결과: `BUILD SUCCESSFUL in 53s`, 종료 코드 0
- Gradle 태스크: 13개 실행, 실패 0개
- 테스트: 57개 실행, 57개 통과, 실패 0개, 오류 0개, 스킵 0개

### PR 직전 전체 빌드

```bash
env -u SPRING_MAIL_USERNAME -u SPRING_MAIL_PASSWORD -u VERIFICATION_MAIL_FROM -u SLOTKEY_KAFKA_ENABLED \
  ./gradlew --no-daemon clean build
```

- 결과: `BUILD SUCCESSFUL in 7m 53s`, 종료 코드 0
- Gradle 태스크: 17개 실행, 실패 0개
- 테스트: 1,202개 실행, 1,202개 통과, 실패 0개, 오류 0개, 스킵 0개
- PR 직전 전체 `clean build` 1회 원칙을 지켰다.

## 3. 가정한 것과 확인하지 못한 것

### 가정

- 사용자의 승인에 따라 공통 보안 코드가 Java인 현재 혼용 상태에서도 단계 A를 진행했다.
- 요청 DTO의 nullable 프로퍼티는 `@Valid` 검증을 통과한 뒤에만 서비스로 전달되므로, 서비스 호출 직전에 `requireNotNull`로 기존 Java의 non-null 전달 계약을 표현했다.
- `@ModelAttribute` 관리자 검색 조건은 기존 Java의 null 방어를 유지하기 위해 nullable 매개변수와 기본 검색 조건 대체 로직을 유지했다.

### 확인하지 못한 것

- 아직 병합되지 않은 공통 보안 Kotlin 코드와의 최종 컴파일·통합 호환성
- 실제 PG·Kafka·메일 연결(단계 A 범위 밖)
- CI 및 사람 검증자의 독립 검증 결과

## 4. 관련 `docs/decisions` 문서

- `docs/decisions/reservation-idempotency.md`: 결제 API의 `Idempotency-Key` 필수 및 성공 응답 재생 정책
- `docs/decisions/authorization-policy.md`: 관리자 권한과 소유권 분리 및 인증·인가 정책
- `docs/decisions/kotlin-lombok-boundary.md`: Kotlin 파일에서 Lombok을 사용하지 않는 경계
- `docs/decisions/kotlin-test-guardrails.md`: Java 기준선과 Kotlin 전환 후 동일 계약 검증
- `docs/core-domain-decisions.md` §2, §6, §11: HOLD → 결제 2단계, 관리자 강제 취소, 스키마 기준

새 기능·기술·설계를 도입하지 않았으므로 새 decision 문서는 작성하지 않았다.

## 5. 추가한 테스트와 검증 내용

- 추가한 테스트 없음
- 기존 테스트가 다음 경계를 이미 검증해 테스트 추가가 불필요하다고 판단했다.
  - `ReservationControllerTest`: 결제 시 `Idempotency-Key` 누락 400, `IDEMPOTENCY_KEY_REQUIRED`, 결제 서비스 미호출
  - `ReservationDtoContractTest`: `@Valid`, 요청 DTO null/형식/경계, 멱등키 전달, 관리자 강제 취소 권한, 검색 조건 바인딩
  - `AdminReservationControllerTest`: `@ModelAttribute`, `Pageable` 기본 크기 20, 필터 및 검증 오류 JSON
  - `AdminReservationSecurityIntegrationTest`: 관리자 API의 401/403/200 및 에러 코드
  - `ReservationSearchSecurityIntegrationTest`: `@CurrentMember`, 본인 예약 한정, 페이징 및 401
  - `ReservationOpenApiContractIntegrationTest`: 요청 스키마와 선택 검색 파라미터 계약

## 6. 리뷰가 필요한 설계 판단과 이유

- nullable Kotlin DTO 값을 서비스의 non-null 인자로 넘길 때 `requireNotNull`을 사용했다. HTTP 요청에서는 `@Valid`가 먼저 실행되어 기존 검증 응답을 유지하며, 관련 DTO 계약 테스트가 통과했다.
- 관리자 검색 조건 매개변수를 nullable로 유지했다. Spring MVC는 일반적으로 모델 객체를 생성하지만, 기존 Java 코드의 null 대체 방어를 제거하지 않기 위한 선택이다.
- Swagger `ApiResponse`와 공통 `ApiResponse` 이름 충돌은 Kotlin import alias로만 해소했다. 생성되는 OpenAPI 계약 테스트가 통과했다.

## 7. 사용자가 결정해야 할 질문

없음. 사용자가 코드·문서 커밋, push 및 PR 생성을 승인했다.

제안 커밋 메시지:

- 코드: `refactor(reservation): migrate reservation controllers to Kotlin`
- 문서: `docs(reservation): record controller migration verification`

## 리뷰 체크리스트

- [x] `ReservationController.java` 삭제와 `ReservationController.kt` 추가가 같은 작업에 포함되어 있다.
- [x] `AdminReservationController.java` 삭제와 `AdminReservationController.kt` 추가가 같은 작업에 포함되어 있다.
- [x] Kotlin 파일에 Lombok을 사용하지 않았다.
- [x] JPA 엔티티와 `data class`를 추가하지 않았다.
- [x] `@Valid`, `@ModelAttribute`, `Pageable`, `@CurrentMember` 동작을 유지했다.
- [x] `@RequestHeader("Idempotency-Key")`를 필수로 유지했다.
- [x] 멱등키 누락 시 400과 기존 에러 JSON을 유지했다.
- [x] 관리자 예약 API의 401/403과 기존 에러 JSON을 유지했다.
- [x] 관리자 강제 취소 경로와 관리자 ID 전달을 유지했다.
- [x] HTTP 요청/응답, 메시지, DB 스키마를 변경하지 않았다.
- [x] 범위 밖 보안 설정·필터·서비스·엔터티를 수정하지 않았다.
- [x] 실제 PG·Kafka·메일 연결을 검증했다고 주장하지 않는다.
- [x] 단계 B~D를 진행하지 않았다.
- [x] 코드와 문서를 별도 커밋으로 분리했다.
- [x] PR 직전 전체 `clean build`를 1회 실행했다.
