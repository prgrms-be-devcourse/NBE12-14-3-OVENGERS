# 목요일 전환 타입의 nullable 제거 보고

> 사용자 요청: 이번 PR의 물음표를 모두 제거하고 커밋·push한다.
> 브랜치: `feat/kotlin-thursday-policies`, 시작 커밋 `544374e`, PR #26.
> 구현자 자기검증이며 별도 전체 검증과 CI는 구분한다. 코드 `3c65285`의 전체 clean build와 테스트 수 검사까지 통과했다.

## ① 변경 파일

- `backend/src/main/kotlin/com/ovengers/slotkey/reservation/policy/ReservationTimePolicy.kt`: 모든 시간 인자를 non-null로 선언하고 기존 null 처리 분기를 제거했다.
- `backend/src/main/kotlin/com/ovengers/slotkey/reservation/dto/ReservationTargetInfo.kt`: 식별자 `Long?`를 `Long`으로 변경했다.
- `backend/src/main/kotlin/com/ovengers/slotkey/credit/pg/PgErrorClassifier.kt`: 코드 인자를 non-null로 선언했다.
- `backend/src/main/kotlin/com/ovengers/slotkey/outbox/OutboxPublishException.kt`: 메시지·원인을 non-null로 선언하고 메시지만 받는 생성자를 추가했다.
- `backend/src/main/java/com/ovengers/slotkey/credit/pg/TossPaymentsPgClient.java`: 코드 누락을 분류기 호출 전에 기존 결과 모름 정책으로 처리한다.
- `backend/src/main/java/com/ovengers/slotkey/reservation/service/ReservationPaymentConfirmService.java`: primitive long 접근자와 Java Long의 소유권 비교를 `Objects.equals`로 맞췄다. 소유권 검사·락 순서는 유지한다.
- `backend/src/test/kotlin/com/ovengers/slotkey/reservation/policy/ReservationTimePolicyTest.kt`, `credit/pg/PgErrorClassifierTest.kt`: non-null 계약에 맞춰 null 테스트를 Java 경계 테스트로 옮겼다. Kotlin의 업무 오류 코드 접근 테스트는 non-null의 잘못된 연장 범위를 사용한다.
- `backend/src/test/java/com/ovengers/slotkey/reservation/policy/NonNullPolicyContractTest.java`: 예약·연장·PG 분류기의 Java null 호출 4개를 검증한다.
- `backend/src/test/java/com/ovengers/slotkey/credit/pg/TossPaymentsPgClientTest.java`: 코드 누락·명시적 null 코드·null 본문 응답 3개를 추가했다.
- `backend/src/test/java/com/ovengers/slotkey/outbox/FakeOutboxPublisher.java`, `OutboxRelayTest.java`: null 원인 대신 메시지 전용 생성자를 사용한다.
- `backend/test-baseline.txt`: 기존 null 테스트 4개는 대체했고 PG 경계 3개를 더해 1,082 → 1,085로 올린다.
- non-null 결정 문서·data class 범위·테스트 결과·이 보고서를 별도 문서 커밋으로 기록한다.

## ② 실행한 명령과 결과

| 명령 | 결과 |
| --- | --- |
| `./gradlew --no-daemon test --tests '*NonNullPolicyContractTest'` (구현 전) | 종료 1, 4개 모두 실패. 기존 nullable 구현은 null에 대해 비즈니스 예외 또는 결과 모름을 반환했다. `/tmp/slotkey-non-null-red.log` |
| 최초 관련 테스트 실행 | `compileJava`에서 `long cannot be dereferenced` 발생. 프로젝션의 `.memberId().equals(...)` 호출부가 boxed Long을 가정한 것을 확인해 `Objects.equals`로 변경했다. |
| 정책·PG·Outbox·프로젝션·소유권 선택 실행 | 종료 0, 8 스위트·107개 통과, 실패/오류/스킵 0. `/tmp/slotkey-non-null-green.log`, XML `/tmp/slotkey-non-null-selected-xml/` |
| `./gradlew --no-daemon ktlintFormat` | 종료 0. 수정 범위 밖의 소스 변경 없음. |
| Kotlin 소스·테스트의 `?` 검색 | `src/main/kotlin`, `src/test/kotlin`에서 0건 |
| `javap` | `ReservationTargetInfo(long, long)`, primitive long 접근자, Outbox 메시지 전용/원인 포함 생성자 확인 |

전체 실행:

- `env -u SPRING_MAIL_USERNAME -u SPRING_MAIL_PASSWORD -u VERIFICATION_MAIL_FROM -u SLOTKEY_KAFKA_ENABLED ./gradlew --no-daemon clean build`: **종료 0, BUILD SUCCESSFUL in 9m 27s**. ktlint 운영/테스트 위반 0, **140 스위트·1,085개 통과, 실패·오류·스킵 0**.
- XML 타임스탬프: 2026-10-22 16:42:39~16:51:36 KST. 로그 `/tmp/slotkey-non-null-full.log`, XML `/tmp/slotkey-non-null-full-xml/`.
- `python3 .github/scripts/check_test_count.py backend/build/test-results/test backend/test-baseline.txt`: 종료 0, `tests=1085 baseline=1085 skipped=0 failures=0 errors=0`.
- 검증한 코드는 `3c65285` (`refactor(kotlin): remove nullable migration declarations`)로 고정했다. 문서·보고서는 별도 커밋으로 같은 브랜치에 포함한다. PR은 기존 [#26](https://github.com/prgrms-be-devcourse/NBE12-14-3-OVENGERS/pull/26)을 갱신한다.

## ③ 가정한 것과 확인하지 못한 것

- 범위는 이번 PR의 Kotlin 전환 타입과 직접 호출부다. 다른 담당자의 HTTP 요청 DTO와 엔티티 nullable 정책은 바꾸지 않았다.
- 예약 생성·연장 요청은 기존 DTO의 `@NotNull`과 컨트롤러 `@Valid`를 거친다. 내부 정책의 null 호출은 사용자 요청에 따라 Kotlin의 인자 검사 실패로 바뀐다.
- PG 누락 응답은 실제 가능한 외부 입력이므로 기존 `UNKNOWN/INDETERMINATE` 및 null 코드 반환을 Java 어댑터에서 유지한다. 새 외부 엔드포인트·필드·헤더·오류 코드는 도입하지 않았다. 기존 공식 출처는 step-1 보고서에 있다.
- HTTP·메시지·DB 스키마, 결제 멱등성·트랜잭션·락 순서와 배포 조건을 바꾸지 않았다.
- 실제 PG/SMTP/Kafka 호출, 운영 배포와 실행 JAR의 HTTP 기동은 이번 검증에 포함하지 않는다.

## ④ 관련 결정 문서

- [목요일 non-null 계약](../../../decisions/kotlin-thursday-non-null-contract.md): 사용자 후속 요청을 반영한 선택·대안·Trade-off·검증.
- [data class 적용 범위 2-6](../../../decisions/kotlin-data-class-scope.md): 내부 프로젝션 식별자의 non-null 예외와 초기 boxed Long 검증 이력 구분.
- [테스트 보호 장치](../../../decisions/kotlin-test-guardrails.md): 테스트 수와 스킵 0 유지.

## 추가한 테스트와 검증 내용

- Java non-null 계약 4개: 예약 인자 5개와 연장 인자 3개의 null 거절, HTTP 상태 400/500에서 분류기 null 코드 거절. Kotlin에서는 null 호출 자체가 컴파일되지 않으므로 Java에서 호출한다.
- 기존 Kotlin null 테스트 4개를 위 계약 테스트로 대체한다. 삭제·스킵으로 테스트를 줄이지 않는다.
- PG 어댑터 3개: 오류 객체에 코드 없음, 코드가 null, 본문이 JSON null인 경우 기존 결과 모름 응답과 null 코드가 유지된다.
- 기존 실제 MySQL JPQL 프로젝션, 결제 소유권, PG 비정상 JSON, Kafka/Outbox 테스트를 재실행한다.

## 리뷰가 필요한 설계 판단

- 내부 null 호출의 예외 종류와 프로젝션 JVM 시그니처가 초기 전환과 달라진다. 이는 사용자 요청에 따른 의도적 변경이다.
- PG 외부 누락 값의 경계 처리를 유지해야 결제 결과 모름이 프로그래밍 오류로 바뀌지 않는다.

## 리뷰 체크리스트

- [x] 이번 PR의 Kotlin 운영·테스트 파일에 nullable 선언·강제 캐스트·이중 느낌표가 없다.
- [x] null 직접 호출 계약 4개가 구현 전 실패하고 변경 후 통과했다.
- [x] 프로젝션 primitive long 생성자와 실제 DB 조회, Java 소유권 비교가 동작한다.
- [x] PG 누락 코드의 기존 결과 모름 정책과 null 코드 반환을 보존했다.
- [x] 원인 없는 Outbox 예외는 메시지 전용 생성자를 사용하고 더미 원인을 만들지 않는다.
- [x] HTTP DTO 검증·DB 스키마·결제/인증/배포 정책을 바꾸지 않았다.
- [x] 전체 clean build와 테스트 수 검사 결과를 기록했다.
- [x] 코드·문서의 커밋 대상을 분리하고 기존 PR #26 업데이트 범위를 확인했다.

## 사용자 결정이 필요한 질문

- 없음. 사용자가 nullable 제거와 커밋·push를 명시적으로 요청했다.
