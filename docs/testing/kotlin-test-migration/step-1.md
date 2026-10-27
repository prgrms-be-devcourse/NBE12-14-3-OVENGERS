# 담당 도메인 Java 테스트 Kotlin 전환 — 1단계 (Issue #177)

## 1. 범위·판정

- 역할: 구현자(implementer). 아래 실행은 **구현자 자기검증**이며, 독립 검증은 완료됐다([step-1-verification.md](step-1-verification.md), 통과). 하위 에이전트의 정적 검토도 구현 작업 내부 점검이며 독립 검증자·CI·사람 리뷰를 대체하지 않는다.
- 기준: 최신 `origin/dev`를 fetch한 `590d443`에서 `refactor/tests-kotlin-domains`를 생성했다. [Issue #177](https://github.com/prgrms-be-devcourse/NBE12-14-3-OVENGERS/issues/177)의 지정 범위다.
- 요청된 Java **71파일**을 같은 패키지·클래스의 Kotlin 71파일로 전환하고 Java를 삭제했다. 69개 테스트 클래스와 Fake 2개이며, 공통 Mockito helper 1개만 새로 추가했다.
- 도메인 순서: outbox → waitlist → notification → credit → reservation. 각 도메인의 Java 기준 실행과 Kotlin 실행에서 클래스별 수·실패·오류·스킵을 확인한 뒤 다음 전환으로 진행했다.
- 운영 코드·마이그레이션·설정·의존성·배포·기존 골든·타 담당 테스트·공유 Java support·`test-baseline.txt` 변경 없음. 기존 Kotlin 테스트 자동 포맷 없음.
- 최종 전체 테스트: **1,361 / 실패 0 / 오류 0 / 스킵 0**, XML **189개**. 전환 전 dev 전체 수 1,361을 유지했다. 커밋·stage·push·PR·배포는 하지 않았다.
- 보류 클래스 및 사용자 결정이 필요한 질문: **없음**.

## 2. 도메인별 실행 명령과 결과

환경: JDK 21.0.11, Gradle wrapper 8.10.2, 기존 Docker/Testcontainers MySQL. 시각은 XML UTC(KST +9시간). 외부 PG·Kafka 실서비스 호출 없이 기존 Fake·mock·로컬 테스트 서버를 유지했다. 아래 숫자는 실행 / 실패 / 오류 / 스킵이다.

| 도메인 | 전환 파일 | Java | Kotlin | XML 근거 |
| --- | --- | --- | --- | --- |
| outbox | 6 | 16 / 0 / 0 / 0 | 16 / 0 / 0 / 0 | Java 2026-10-27T00:36:34 ~ 2026-10-27T00:37:04; Kotlin 2026-10-27T00:39:56 ~ 2026-10-27T00:40:23 |
| waitlist | 5 | 45 / 0 / 0 / 0 | 45 / 0 / 0 / 0 | Java 2026-10-27T00:40:43 ~ 2026-10-27T00:40:58; Kotlin 2026-10-27T00:42:50 ~ 2026-10-27T00:43:06 |
| notification | 8 | 46 / 0 / 0 / 0 | 46 / 0 / 0 / 0 | Java 2026-10-27T00:43:59 ~ 2026-10-27T00:44:12; Kotlin 2026-10-27T00:47:02 ~ 2026-10-27T00:47:16 |
| credit | 17 | 209 / 0 / 0 / 0 | 209 / 0 / 0 / 0 | Java 2026-10-27T00:47:43 ~ 2026-10-27T00:48:17; Kotlin 2026-10-27T00:54:37 ~ 2026-10-27T00:55:06 |
| reservation | 35 | 244 / 0 / 0 / 0 | 244 / 0 / 0 / 0 | Java 2026-10-27T00:55:46 ~ 2026-10-27T00:58:14; Kotlin 2026-10-27T01:11:53 ~ 2026-10-27T01:14:35 |

대상 69개 테스트 클래스의 Java/Kotlin 실행은 총 **560건**이며, Fake 2개는 테스트 메서드가 없어 실행 수 0이다. 모든 클래스의 파라미터 전개 후 실행 수와 XML testcase 표시 이름 목록을 전환 전후 직접 대조했다.

각 도메인의 실제 명령은 `backend/`에서 `./gradlew cleanTest test` 뒤에 다음 각 FQCN을 `--tests`로 반복 전달한 것이다. 같은 명령을 Java/Kotlin 상태에서 실행했다(따라서 기존 추가 골든 클래스까지 포함하는 넓은 wildcard 실행과 구분한다).

### outbox 실행 대상

```bash
./gradlew cleanTest test \
  --tests 'com.ovengers.slotkey.outbox.KafkaOutboxPublisherTest' \
  --tests 'com.ovengers.slotkey.outbox.KafkaTimeoutDefaultsTest' \
  --tests 'com.ovengers.slotkey.outbox.OutboxRelayDisabledTest' \
  --tests 'com.ovengers.slotkey.outbox.OutboxRelayIntegrationTest' \
  --tests 'com.ovengers.slotkey.outbox.OutboxRelayTest'
```

### waitlist 실행 대상

```bash
./gradlew cleanTest test \
  --tests 'com.ovengers.slotkey.waitlist.controller.WaitlistControllerTest' \
  --tests 'com.ovengers.slotkey.waitlist.service.WaitlistApplyConcurrencyTest' \
  --tests 'com.ovengers.slotkey.waitlist.service.WaitlistDuplicateMappingTest' \
  --tests 'com.ovengers.slotkey.waitlist.service.WaitlistEntryLostUpdateTest' \
  --tests 'com.ovengers.slotkey.waitlist.service.WaitlistServiceIntegrationTest'
```

### notification 실행 대상

```bash
./gradlew cleanTest test \
  --tests 'com.ovengers.slotkey.notification.KafkaConsumerDisabledTest' \
  --tests 'com.ovengers.slotkey.notification.NotificationControllerTest' \
  --tests 'com.ovengers.slotkey.notification.NotificationQueryIntegrationTest' \
  --tests 'com.ovengers.slotkey.notification.NotificationReadIntegrationTest' \
  --tests 'com.ovengers.slotkey.notification.SlotReleasedHandlerConcurrencyTest' \
  --tests 'com.ovengers.slotkey.notification.SlotReleasedHandlerTest' \
  --tests 'com.ovengers.slotkey.notification.SlotReleasedListenerTest' \
  --tests 'com.ovengers.slotkey.notification.consumer.KafkaConsumerConfigTest'
```

### credit 실행 대상

```bash
./gradlew cleanTest test \
  --tests 'com.ovengers.slotkey.credit.controller.ChargeOrderControllerTest' \
  --tests 'com.ovengers.slotkey.credit.dto.FridayRemainingDtoGoldenTest' \
  --tests 'com.ovengers.slotkey.credit.dto.FridayRemainingDtoNullableContractTest' \
  --tests 'com.ovengers.slotkey.credit.pg.PgClientTestProfileIntegrationTest' \
  --tests 'com.ovengers.slotkey.credit.pg.TossPaymentsPgClientTest' \
  --tests 'com.ovengers.slotkey.credit.pg.TossPaymentsPgConfigurationTest' \
  --tests 'com.ovengers.slotkey.credit.pg.TossPaymentsPropertiesTest' \
  --tests 'com.ovengers.slotkey.credit.repository.ChargeOrderRepositoryTest' \
  --tests 'com.ovengers.slotkey.credit.scheduler.ChargeOrderRecoveryProcessorIntegrationTest' \
  --tests 'com.ovengers.slotkey.credit.scheduler.ChargeOrderRecoverySchedulerTest' \
  --tests 'com.ovengers.slotkey.credit.service.ChargeOrderConfirmIntegrationTest' \
  --tests 'com.ovengers.slotkey.credit.service.ChargeOrderConfirmServiceTest' \
  --tests 'com.ovengers.slotkey.credit.service.ChargeOrderServiceTest' \
  --tests 'com.ovengers.slotkey.credit.webhook.PgWebhookEventProcessorIntegrationTest' \
  --tests 'com.ovengers.slotkey.credit.webhook.PgWebhookEventSchedulerTest' \
  --tests 'com.ovengers.slotkey.credit.webhook.TossWebhookIntegrationTest'
```

### reservation 실행 대상

```bash
./gradlew cleanTest test \
  --tests 'com.ovengers.slotkey.reservation.controller.AdminReservationControllerTest' \
  --tests 'com.ovengers.slotkey.reservation.controller.AdminReservationSecurityIntegrationTest' \
  --tests 'com.ovengers.slotkey.reservation.controller.ReservationControllerTest' \
  --tests 'com.ovengers.slotkey.reservation.controller.ReservationDtoContractTest' \
  --tests 'com.ovengers.slotkey.reservation.controller.ReservationOpenApiContractIntegrationTest' \
  --tests 'com.ovengers.slotkey.reservation.controller.ReservationSearchSecurityIntegrationTest' \
  --tests 'com.ovengers.slotkey.reservation.dto.ReservationDtoGoldenTest' \
  --tests 'com.ovengers.slotkey.reservation.dto.ReservationDtoNullableContractTest' \
  --tests 'com.ovengers.slotkey.reservation.policy.NonNullPolicyContractTest' \
  --tests 'com.ovengers.slotkey.reservation.repository.ReservationMemberCountRepositoryTest' \
  --tests 'com.ovengers.slotkey.reservation.repository.ReservationSlotRepositoryTest' \
  --tests 'com.ovengers.slotkey.reservation.scheduler.ReservationBatchProcessorTest' \
  --tests 'com.ovengers.slotkey.reservation.scheduler.ReservationCompletionSchedulerTest' \
  --tests 'com.ovengers.slotkey.reservation.service.AdminReservationConcurrencyIntegrationTest' \
  --tests 'com.ovengers.slotkey.reservation.service.AdminReservationSearchIntegrationTest' \
  --tests 'com.ovengers.slotkey.reservation.service.AdminReservationServiceIntegrationTest' \
  --tests 'com.ovengers.slotkey.reservation.service.AdminReservationServiceTest' \
  --tests 'com.ovengers.slotkey.reservation.service.MemberWithdrawalHoldConcurrencyIntegrationTest' \
  --tests 'com.ovengers.slotkey.reservation.service.OutboxRecordIntegrationTest' \
  --tests 'com.ovengers.slotkey.reservation.service.PricingServiceTest' \
  --tests 'com.ovengers.slotkey.reservation.service.ReservationAdjacentSlotConcurrencyTest' \
  --tests 'com.ovengers.slotkey.reservation.service.ReservationCancelConcurrencyIntegrationTest' \
  --tests 'com.ovengers.slotkey.reservation.service.ReservationCancelServiceTest' \
  --tests 'com.ovengers.slotkey.reservation.service.ReservationCheckOutServiceTest' \
  --tests 'com.ovengers.slotkey.reservation.service.ReservationConcurrencyTest' \
  --tests 'com.ovengers.slotkey.reservation.service.ReservationExtendHoldConcurrencyIntegrationTest' \
  --tests 'com.ovengers.slotkey.reservation.service.ReservationExtendIntegrationTest' \
  --tests 'com.ovengers.slotkey.reservation.service.ReservationExtendServiceTest' \
  --tests 'com.ovengers.slotkey.reservation.service.ReservationHoldExpirationServiceTest' \
  --tests 'com.ovengers.slotkey.reservation.service.ReservationHoldMemberLockIntegrationTest' \
  --tests 'com.ovengers.slotkey.reservation.service.ReservationHoldServiceTest' \
  --tests 'com.ovengers.slotkey.reservation.service.ReservationPartialOverlapConcurrencyTest' \
  --tests 'com.ovengers.slotkey.reservation.service.ReservationPaymentConfirmServiceTest' \
  --tests 'com.ovengers.slotkey.reservation.service.ReservationQueryServiceIntegrationTest' \
  --tests 'com.ovengers.slotkey.reservation.service.SpaceReservationLockIntegrationTest'
```

### 최종 검사

| 명령 | 결과 |
| --- | --- |
| `./gradlew ktlintCheck` | 종료 0, BUILD SUCCESSFUL in 6s |
| `./gradlew clean build -x test` | 종료 0, BUILD SUCCESSFUL in 10s |
| `./gradlew cleanTest test` | 종료 0, **1,361 / 0 / 0 / 0**, XML 189개; BUILD SUCCESSFUL in 11m 20s |
| `python3 .github/scripts/check_test_count.py backend/build/test-results/test backend/test-baseline.txt`(루트) | 종료 0: `suites=189 tests=1361 skipped=0 failures=0 errors=0 baseline=1322` |
| `git diff --check`·보호 파일/변경 범위 점검 | 종료 0. 71 Java 삭제·72 신규 Kotlin·3 경로 문서·보고서만 변경, index 비어 있음 |

전체 XML 시작 범위: `2026-10-27T01:17:39`~`2026-10-27T01:28:17` UTC. 전환한 69개 클래스/560건이 최종 전체 XML에 포함되고 각 클래스의 수·testcase 표시 이름이 Java 기준과 같음을 재확인했다.

로그·각 단계 XML 원본 사본: `/private/tmp/kotlin-test-migration-177-evidence/`. 도메인별 `<domain>-java/`, `<domain>-kotlin/`와 `outbox-kotlin-final/`, 최종 `full-final/`에 보존했다. 이 경로는 임시 근거이며 영구 인계 근거는 본 문서와 기준 커밋·변경 파일이다. 프론트·운영 배포·CI는 이번 검증에 포함하지 않았다.

## 3. 클래스별 변환 표 및 도메인별 코드 파일 목록

아래 Java 경로는 `backend/src/test/java/com/ovengers/slotkey/`, Kotlin 경로는 `backend/src/test/kotlin/com/ovengers/slotkey/`를 기준으로 적었다. 각 행이 Java 삭제와 Kotlin 추가의 한 쌍이며 동시에 남기지 않았다. 테스트 수는 XML의 실제 파라미터 전개 수다.

판정 토큰은 `assertThat`, `assertThatThrownBy`, `assertThrows`, `assertDoesNotThrow`, `assertThatCode`, `assertAll`, `verify`, `verifyNoInteractions`, `verifyNoMoreInteractions`, `jsonPath`, HTTP `status()` 호출을 집계했다. Kotlin 후행 lambda는 Java 괄호 호출과 동일하게 센다. DTO record의 `.status()` → `.status`는 HTTP 상태 판정과 구분하며 필드 접근자 감소를 단언 삭제로 세지 않는다. 토큰의 개수만으로 값·순서를 보장할 수 없어 문자열·숫자·메서드·동시성 제어의 정적 대조와 실행 검증을 병행했다.

### outbox

| Java 삭제 | Kotlin 추가 | 테스트 전/후 | 판정 토큰 전/후 | 토큰별 수(전=후, 0 생략) |
| --- | --- | --- | --- | --- |
| `outbox/FakeOutboxPublisher.java` | `outbox/FakeOutboxPublisher.kt` | 0 / 0 | 0 / 0 | 없음 |
| `outbox/KafkaOutboxPublisherTest.java` | `outbox/KafkaOutboxPublisherTest.kt` | 4 / 4 | 6 / 6 | assertThat=2, assertThatThrownBy=3, verify=1 |
| `outbox/KafkaTimeoutDefaultsTest.java` | `outbox/KafkaTimeoutDefaultsTest.kt` | 1 / 1 | 4 / 4 | assertThat=4 |
| `outbox/OutboxRelayDisabledTest.java` | `outbox/OutboxRelayDisabledTest.kt` | 1 / 1 | 2 / 2 | assertThat=2 |
| `outbox/OutboxRelayIntegrationTest.java` | `outbox/OutboxRelayIntegrationTest.kt` | 7 / 7 | 25 / 25 | assertThat=25 |
| `outbox/OutboxRelayTest.java` | `outbox/OutboxRelayTest.kt` | 3 / 3 | 7 / 7 | verify=7 |

### waitlist

| Java 삭제 | Kotlin 추가 | 테스트 전/후 | 판정 토큰 전/후 | 토큰별 수(전=후, 0 생략) |
| --- | --- | --- | --- | --- |
| `waitlist/controller/WaitlistControllerTest.java` | `waitlist/controller/WaitlistControllerTest.kt` | 6 / 6 | 22 / 22 | verify=3, verifyNoInteractions=2, jsonPath=11, status=6 |
| `waitlist/service/WaitlistApplyConcurrencyTest.java` | `waitlist/service/WaitlistApplyConcurrencyTest.kt` | 1 / 1 | 5 / 5 | assertThat=5 |
| `waitlist/service/WaitlistDuplicateMappingTest.java` | `waitlist/service/WaitlistDuplicateMappingTest.kt` | 4 / 4 | 5 / 5 | assertThat=5 |
| `waitlist/service/WaitlistEntryLostUpdateTest.java` | `waitlist/service/WaitlistEntryLostUpdateTest.kt` | 2 / 2 | 6 / 6 | assertThat=6 |
| `waitlist/service/WaitlistServiceIntegrationTest.java` | `waitlist/service/WaitlistServiceIntegrationTest.kt` | 32 / 32 | 53 / 53 | assertThat=35, assertThatThrownBy=18 |

### notification

| Java 삭제 | Kotlin 추가 | 테스트 전/후 | 판정 토큰 전/후 | 토큰별 수(전=후, 0 생략) |
| --- | --- | --- | --- | --- |
| `notification/KafkaConsumerDisabledTest.java` | `notification/KafkaConsumerDisabledTest.kt` | 1 / 1 | 2 / 2 | assertThat=2 |
| `notification/NotificationControllerTest.java` | `notification/NotificationControllerTest.kt` | 3 / 3 | 10 / 10 | verify=1, verifyNoInteractions=1, jsonPath=4, status=4 |
| `notification/NotificationQueryIntegrationTest.java` | `notification/NotificationQueryIntegrationTest.kt` | 2 / 2 | 6 / 6 | assertThat=6 |
| `notification/NotificationReadIntegrationTest.java` | `notification/NotificationReadIntegrationTest.kt` | 2 / 2 | 10 / 10 | assertThat=5, jsonPath=2, status=3 |
| `notification/SlotReleasedHandlerConcurrencyTest.java` | `notification/SlotReleasedHandlerConcurrencyTest.kt` | 1 / 1 | 4 / 4 | assertThat=4 |
| `notification/SlotReleasedHandlerTest.java` | `notification/SlotReleasedHandlerTest.kt` | 21 / 21 | 32 / 32 | assertThat=32 |
| `notification/SlotReleasedListenerTest.java` | `notification/SlotReleasedListenerTest.kt` | 14 / 14 | 8 / 8 | assertThatThrownBy=4, verify=2, verifyNoInteractions=2 |
| `notification/consumer/KafkaConsumerConfigTest.java` | `notification/consumer/KafkaConsumerConfigTest.kt` | 2 / 2 | 3 / 3 | assertThat=3 |

### credit

| Java 삭제 | Kotlin 추가 | 테스트 전/후 | 판정 토큰 전/후 | 토큰별 수(전=후, 0 생략) |
| --- | --- | --- | --- | --- |
| `credit/controller/ChargeOrderControllerTest.java` | `credit/controller/ChargeOrderControllerTest.kt` | 14 / 14 | 41 / 41 | verify=2, verifyNoInteractions=8, jsonPath=17, status=14 |
| `credit/dto/FridayRemainingDtoGoldenTest.java` | `credit/dto/FridayRemainingDtoGoldenTest.kt` | 15 / 15 | 1 / 1 | assertThat=1 |
| `credit/dto/FridayRemainingDtoNullableContractTest.java` | `credit/dto/FridayRemainingDtoNullableContractTest.kt` | 25 / 25 | 6 / 6 | assertThat=6 |
| `credit/pg/FakePgClient.java` | `credit/pg/FakePgClient.kt` | 0 / 0 | 0 / 0 | 없음 |
| `credit/pg/PgClientTestProfileIntegrationTest.java` | `credit/pg/PgClientTestProfileIntegrationTest.kt` | 1 / 1 | 2 / 2 | assertThat=2 |
| `credit/pg/TossPaymentsPgClientTest.java` | `credit/pg/TossPaymentsPgClientTest.kt` | 44 / 44 | 88 / 88 | assertThat=64, verify=24 |
| `credit/pg/TossPaymentsPgConfigurationTest.java` | `credit/pg/TossPaymentsPgConfigurationTest.kt` | 1 / 1 | 2 / 2 | assertThat=2 |
| `credit/pg/TossPaymentsPropertiesTest.java` | `credit/pg/TossPaymentsPropertiesTest.kt` | 1 / 1 | 1 / 1 | assertThat=1 |
| `credit/repository/ChargeOrderRepositoryTest.java` | `credit/repository/ChargeOrderRepositoryTest.kt` | 5 / 5 | 15 / 15 | assertThat=14, assertThatThrownBy=1 |
| `credit/scheduler/ChargeOrderRecoveryProcessorIntegrationTest.java` | `credit/scheduler/ChargeOrderRecoveryProcessorIntegrationTest.kt` | 16 / 16 | 68 / 68 | assertThat=53, assertThatThrownBy=1, status=14 |
| `credit/scheduler/ChargeOrderRecoverySchedulerTest.java` | `credit/scheduler/ChargeOrderRecoverySchedulerTest.kt` | 2 / 2 | 6 / 6 | verify=6 |
| `credit/service/ChargeOrderConfirmIntegrationTest.java` | `credit/service/ChargeOrderConfirmIntegrationTest.kt` | 27 / 27 | 120 / 120 | assertThat=88, assertThatThrownBy=11, status=21 |
| `credit/service/ChargeOrderConfirmServiceTest.java` | `credit/service/ChargeOrderConfirmServiceTest.kt` | 11 / 11 | 15 / 15 | assertThat=7, assertThatThrownBy=5, verify=3 |
| `credit/service/ChargeOrderServiceTest.java` | `credit/service/ChargeOrderServiceTest.kt` | 14 / 14 | 23 / 23 | assertThat=17, assertThatThrownBy=3, verify=3 |
| `credit/webhook/PgWebhookEventProcessorIntegrationTest.java` | `credit/webhook/PgWebhookEventProcessorIntegrationTest.kt` | 17 / 17 | 61 / 61 | assertThat=61 |
| `credit/webhook/PgWebhookEventSchedulerTest.java` | `credit/webhook/PgWebhookEventSchedulerTest.kt` | 3 / 3 | 5 / 5 | verify=5 |
| `credit/webhook/TossWebhookIntegrationTest.java` | `credit/webhook/TossWebhookIntegrationTest.kt` | 13 / 13 | 50 / 50 | assertThat=34, status=16 |

### reservation

| Java 삭제 | Kotlin 추가 | 테스트 전/후 | 판정 토큰 전/후 | 토큰별 수(전=후, 0 생략) |
| --- | --- | --- | --- | --- |
| `reservation/controller/AdminReservationControllerTest.java` | `reservation/controller/AdminReservationControllerTest.kt` | 6 / 6 | 50 / 50 | assertThat=6, verify=2, verifyNoInteractions=3, jsonPath=32, status=7 |
| `reservation/controller/AdminReservationSecurityIntegrationTest.java` | `reservation/controller/AdminReservationSecurityIntegrationTest.kt` | 3 / 3 | 12 / 12 | jsonPath=9, status=3 |
| `reservation/controller/ReservationControllerTest.java` | `reservation/controller/ReservationControllerTest.kt` | 1 / 1 | 6 / 6 | verify=1, verifyNoInteractions=1, jsonPath=3, status=1 |
| `reservation/controller/ReservationDtoContractTest.java` | `reservation/controller/ReservationDtoContractTest.kt` | 36 / 36 | 38 / 38 | assertThat=1, verify=2, verifyNoInteractions=8, jsonPath=16, status=11 |
| `reservation/controller/ReservationOpenApiContractIntegrationTest.java` | `reservation/controller/ReservationOpenApiContractIntegrationTest.kt` | 2 / 2 | 21 / 21 | assertThat=20, status=1 |
| `reservation/controller/ReservationSearchSecurityIntegrationTest.java` | `reservation/controller/ReservationSearchSecurityIntegrationTest.kt` | 9 / 9 | 71 / 71 | assertThat=9, jsonPath=50, status=12 |
| `reservation/dto/ReservationDtoGoldenTest.java` | `reservation/dto/ReservationDtoGoldenTest.kt` | 15 / 15 | 6 / 6 | assertThat=6 |
| `reservation/dto/ReservationDtoNullableContractTest.java` | `reservation/dto/ReservationDtoNullableContractTest.kt` | 2 / 2 | 4 / 4 | assertThat=4 |
| `reservation/policy/NonNullPolicyContractTest.java` | `reservation/policy/NonNullPolicyContractTest.kt` | 4 / 4 | 9 / 9 | assertThatThrownBy=9 |
| `reservation/repository/ReservationMemberCountRepositoryTest.java` | `reservation/repository/ReservationMemberCountRepositoryTest.kt` | 1 / 1 | 1 / 1 | assertThat=1 |
| `reservation/repository/ReservationSlotRepositoryTest.java` | `reservation/repository/ReservationSlotRepositoryTest.kt` | 12 / 12 | 50 / 50 | assertThat=50 |
| `reservation/scheduler/ReservationBatchProcessorTest.java` | `reservation/scheduler/ReservationBatchProcessorTest.kt` | 6 / 6 | 19 / 19 | assertThat=6, verify=13 |
| `reservation/scheduler/ReservationCompletionSchedulerTest.java` | `reservation/scheduler/ReservationCompletionSchedulerTest.kt` | 5 / 5 | 10 / 10 | verify=9, verifyNoInteractions=1 |
| `reservation/service/AdminReservationConcurrencyIntegrationTest.java` | `reservation/service/AdminReservationConcurrencyIntegrationTest.kt` | 13 / 13 | 184 / 184 | assertThat=171, assertThatThrownBy=2, verify=11 |
| `reservation/service/AdminReservationSearchIntegrationTest.java` | `reservation/service/AdminReservationSearchIntegrationTest.kt` | 7 / 7 | 27 / 27 | assertThat=27 |
| `reservation/service/AdminReservationServiceIntegrationTest.java` | `reservation/service/AdminReservationServiceIntegrationTest.kt` | 3 / 3 | 40 / 40 | assertThat=38, assertThatThrownBy=2 |
| `reservation/service/AdminReservationServiceTest.java` | `reservation/service/AdminReservationServiceTest.kt` | 13 / 13 | 61 / 61 | assertThat=18, assertThatThrownBy=4, verify=39 |
| `reservation/service/MemberWithdrawalHoldConcurrencyIntegrationTest.java` | `reservation/service/MemberWithdrawalHoldConcurrencyIntegrationTest.kt` | 2 / 2 | 21 / 21 | assertThat=19, assertThatThrownBy=2 |
| `reservation/service/OutboxRecordIntegrationTest.java` | `reservation/service/OutboxRecordIntegrationTest.kt` | 14 / 14 | 58 / 58 | assertThat=52, assertThatThrownBy=6 |
| `reservation/service/PricingServiceTest.java` | `reservation/service/PricingServiceTest.kt` | 5 / 5 | 5 / 5 | assertThat=5 |
| `reservation/service/ReservationAdjacentSlotConcurrencyTest.java` | `reservation/service/ReservationAdjacentSlotConcurrencyTest.kt` | 1 / 1 | 7 / 7 | assertThat=7 |
| `reservation/service/ReservationCancelConcurrencyIntegrationTest.java` | `reservation/service/ReservationCancelConcurrencyIntegrationTest.kt` | 5 / 5 | 70 / 70 | assertThat=70 |
| `reservation/service/ReservationCancelServiceTest.java` | `reservation/service/ReservationCancelServiceTest.kt` | 10 / 10 | 43 / 43 | assertThat=7, assertThatThrownBy=6, verify=30 |
| `reservation/service/ReservationCheckOutServiceTest.java` | `reservation/service/ReservationCheckOutServiceTest.kt` | 4 / 4 | 15 / 15 | assertThat=5, assertThatThrownBy=3, verify=7 |
| `reservation/service/ReservationConcurrencyTest.java` | `reservation/service/ReservationConcurrencyTest.kt` | 1 / 1 | 5 / 5 | assertThat=5 |
| `reservation/service/ReservationExtendHoldConcurrencyIntegrationTest.java` | `reservation/service/ReservationExtendHoldConcurrencyIntegrationTest.kt` | 5 / 5 | 42 / 42 | assertThat=42 |
| `reservation/service/ReservationExtendIntegrationTest.java` | `reservation/service/ReservationExtendIntegrationTest.kt` | 7 / 7 | 32 / 32 | assertThat=30, assertThatThrownBy=2 |
| `reservation/service/ReservationExtendServiceTest.java` | `reservation/service/ReservationExtendServiceTest.kt` | 8 / 8 | 22 / 22 | assertThat=1, assertThatThrownBy=7, verify=7, verifyNoInteractions=7 |
| `reservation/service/ReservationHoldExpirationServiceTest.java` | `reservation/service/ReservationHoldExpirationServiceTest.kt` | 3 / 3 | 13 / 13 | assertThat=2, verify=11 |
| `reservation/service/ReservationHoldMemberLockIntegrationTest.java` | `reservation/service/ReservationHoldMemberLockIntegrationTest.kt` | 4 / 4 | 26 / 26 | assertThat=24, assertThatThrownBy=2 |
| `reservation/service/ReservationHoldServiceTest.java` | `reservation/service/ReservationHoldServiceTest.kt` | 7 / 7 | 25 / 25 | assertThat=7, assertThatThrownBy=6, verify=12 |
| `reservation/service/ReservationPartialOverlapConcurrencyTest.java` | `reservation/service/ReservationPartialOverlapConcurrencyTest.kt` | 2 / 2 | 5 / 5 | assertThat=5 |
| `reservation/service/ReservationPaymentConfirmServiceTest.java` | `reservation/service/ReservationPaymentConfirmServiceTest.kt` | 8 / 8 | 22 / 22 | assertThat=2, assertThatThrownBy=6, verify=7, verifyNoInteractions=7 |
| `reservation/service/ReservationQueryServiceIntegrationTest.java` | `reservation/service/ReservationQueryServiceIntegrationTest.kt` | 12 / 12 | 80 / 80 | assertThat=80 |
| `reservation/service/SpaceReservationLockIntegrationTest.java` | `reservation/service/SpaceReservationLockIntegrationTest.kt` | 8 / 8 | 55 / 55 | assertThat=53, assertThatThrownBy=2 |

### 공통 코드 파일

- 신규: `backend/src/test/kotlin/com/ovengers/slotkey/support/MockitoKotlinHelpers.kt`.
- `eqNonNull`은 matcher 등록 후 원래 값을 반환한다. 타입 없는 `any()`와 `any(Class)`는 각각 구분해 유지하며 `captureNonNull`은 generic bridge를 사용한다.
- `nullValue<T>()`는 기존 Java의 명시적 null을 테스트 경계·Mockito 호출에 전달하는 test-only bridge다. 운영 선언을 nullable로 완화하지 않았고 의도한 예외·never 검증을 유지한다.
- `FakeOutboxPublisher.Sent`는 `@JvmRecord`로 기존 Java record 접근자를 유지한다. FakePgClient의 overload·Java 호출면·카운터·지연·callback·nullable last request 의미를 보존했다.
- PR을 예약/나머지로 나눌 때 공통 helper를 포함할 선행 PR과 후속 기준 커밋을 검증자가 정해야 한다. 이 문서에서는 파일 목록만 분리하고 커밋하지 않았다.

## 4. 컴파일·실행 중 확인한 변환 경계

- notification과 credit에서 AssertJ `extracting`이 Tuple overload로 선택되거나 `satisfies`가 모호해 컴파일 실패했다(XML 없음). 스칼라 타입 인자와 `Consumer<T>`를 명시해 Java의 원래 overload를 선택했다. 단언 개수·기대값·순서는 유지했다.
- credit의 `confirm_approvedButDb2Fails_keepsConfirming`에서 209건 중 1건 실패를 확인했다: `expected 2147483647`, `actual 2147483647L`. Java의 primitive long 승격을 Kotlin boxed Object 비교가 대신한 것이었다. 기존 숫자를 `Int.MAX_VALUE.toLong()`로 명시한 뒤 같은 209건 전체 통과를 확인했다. 실패 XML/log는 `credit-kotlin-number-red/`와 `credit-kotlin-number-red.log`다.
- reservation 첫 실행은 `compileTestKotlin`에서 실패했다(13s, 테스트 미실행·XML 없음). 남아 있던 Java 반복문 문법, record 접근자, mutable smart cast, null 반환 transaction callback의 Unit 추론을 각 새 파일에서 수정했다. 원래 반복 경계·리터럴·callback 결과·동기화 순서는 유지했다. 로그: `reservation-kotlin-compile1.log`.
- reservation 중간 실행은 **244 / 1 / 0 / 0**이었다(`reservation-kotlin-proxy-red/`, expected 3L / actual 0L). 원본 lazy 연관 객체의 `getId()`를 `.id`로 바꿔 final/renamed getter가 Hibernate proxy의 open Java 호환 getter를 우회했다. Reservation receiver의 원래 호환 `getX()` 호출을 복원하고 같은 244건을 재실행했다. 운영 엔티티는 수정하지 않았다.
- nullable JDBC 조회 결과와 Java primitive unboxing 의미를 구분했다. JVM boxed 타입이 필요한 reflection/DTO 계약에는 `javaObjectType`을 유지했다.
- 공유 Java support 상속·고정 시계·Testcontainers·동시성 도구는 유지했다. 스레드 수·latch/barrier 순서·timeout·sleep을 줄이거나 테스트를 스킵하지 않았다.
- formatter 명령: `./gradlew -I /private/tmp/kotlin-test-migration-177-format.init.gradle ktlintTestSourceSetFormat`. 신규 71파일+helper 1파일의 정확한 allowlist만 전달했다. 최초 교정 뒤 line-length 보고 2건이 있어 재실행 후 종료 0을 확인했다. 기존 Kotlin 파일 diff는 0이다.
- 새 파일만 대상으로 한 formatter allowlist와 최종 전체 ktlintCheck를 사용한다. 기존 파일 자동 포맷·기대 JSON 재생성·정규화·기준선 조정은 하지 않는다.

정적 검토도 완료했다. 도메인별·전체 범위 검토에서 발견한 Long 승격 및 Reservation getter 문제는 위 실행으로 수정·통과를 확인했고, 미해결 중대 사항은 없다. 구현 내부 검토 근거는 임시 폴더의 `*-review.md`이며 독립 검증자 전체 재실행과 구분한다.

## 5. 문서 변경 목록 (코드와 분리)

Java 테스트 경로를 Kotlin 경로로만 바꿨다. `docs/testing/golden-survey/`는 당시 기록이라 그대로 두었다.

- `docs/decisions/hold-cancel-and-account-ui.md`: 이동한 테스트의 `.java`/`src/test/java` 경로만 `.kt`/`src/test/kotlin`로 수정.
- `docs/testing/test-strategy.md`: 이동한 테스트의 `.java`/`src/test/java` 경로만 `.kt`/`src/test/kotlin`로 수정.
- `docs/plans/kotlin-migration/01-test-baseline.md`: 이동한 테스트의 `.java`/`src/test/java` 경로만 `.kt`/`src/test/kotlin`로 수정.
- 신규 인계 보고: `docs/testing/kotlin-test-migration/step-1.md`.

## 6. 가정·미확인·보류·관련 문서

- 보류 클래스 없음. 설계·동작 변경 없음. 사용자 결정이 필요한 질문 없음.
- 공유 checkout 이력: 작업 도중 다른 checkout으로 dev의 별도 문서/CI 커밋 `626e530`·`8890b0a`가 HEAD에 나타난 것을 reflog로 확인했다. 해당 커밋은 별도 `docs/api-spec-sync-step1` 등 브랜치에서 보존됐음을 확인하고 `git switch refactor/tests-kotlin-domains`로 원래 `590d443` 브랜치에 복귀했다. reset/rewrite/commit은 하지 않았으며 API/CI 변경은 본 브랜치에 포함되지 않는다. backend 소스는 두 커밋에서 변경되지 않았다.
- 원래 Java 동작과 테스트의 의미를 유지하는 언어 변환이다. 새 기능/정책/의존성 도입이 없어 새 decision은 만들지 않았다.
- 기존 `docs/testing/test-strategy.md`와 `02-money-and-concurrency.md`에는 이번 71개 대상 밖인 `ReservationPaymentConcurrencyTest`·`ReservationCancelConcurrencyTest`의 예전 `.java` 링크가 남아 있다. 범위 밖 기존 표기라 수정하지 않았다.
- 기존 Lombok 혼용 플러그인 경고와 신규 변환 파일의 JVM 컬렉션 사용·미사용 파라미터·불필요 `!!` 등 컴파일 경고는 남는다. ktlint/빌드/테스트 실패는 없으며 관련 없는 정리는 하지 않았다.
- 독립 검증자가 전체 테스트·정적 체크리스트·PIT를 재실행해 통과로 판정했다([step-1-verification.md](step-1-verification.md)). 사람 리뷰와 CI는 PR에서 확인한다.
- 기존 운영 PG 승인/정합성·예약 동시성 정책은 기존 테스트가 검증하는 범위를 그대로 유지했다. 테스트 수와 통과만으로 실서비스 외부 API나 운영 배포 검증을 주장하지 않는다.
- 관련: [테스트 보호 장치](../../decisions/kotlin-test-guardrails.md), [파일 단위 작업 절차](../../plans/kotlin-migration/07-code-migration.md#파일-단위-작업-절차), [핵심 도메인 결정](../../core-domain-decisions.md), [AGENTS.md](../../../AGENTS.md).

## 7. 리뷰 체크리스트

- [ ] 최신 dev 기준 브랜치이며 stage·커밋·push·PR이 없는가.
- [ ] 요청된 71쌍만 Java 삭제/Kotlin 추가했고 패키지·클래스·테스트 이름·DisplayName·파라미터가 같은가.
- [ ] 모든 클래스의 XML 실행 수·testcase 표시 이름과 판정 토큰 수가 전환 전후 같은가.
- [ ] 단언의 값·타입·횟수·순서·오류 코드·HTTP 상태·jsonPath 의미가 같은가.
- [ ] 동시성 스레드 수·latch/barrier 순서·timeout·sleep·트랜잭션 choreography가 같은가.
- [ ] Mockito 매처와 명시적 null의 의미를 helper로 보존하고 운영 non-null 선언은 그대로인가.
- [ ] 골든 관련 JSON 문자열과 resources/golden 파일이 한 글자도 바뀌지 않았는가.
- [ ] 공유 Java support·타 담당 테스트·운영·설정·의존성·마이그레이션·배포·test-baseline이 그대로인가.
- [ ] Lombok·엔티티 data class·시크릿 추가·Disabled/스킵·골든 쓰기 코드가 없는가.
- [ ] ktlintCheck·clean build -x test·전체 cleanTest test와 XML 1361/0/0/0을 직접 확인했는가.
- [ ] 문서 변경은 경로만이며 golden-survey 과거 기록을 바꾸지 않았는가.
- [ ] 예약/나머지 PR의 파일 분리와 공통 helper 의존 순서가 검토 가능한가.
