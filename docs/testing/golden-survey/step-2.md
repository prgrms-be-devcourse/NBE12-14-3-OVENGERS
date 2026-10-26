# 골든 현황 조사 — 2단계: 전환 전 Java 기준 재현 가능 여부

- 범위: 담당 5개 영역(reservation, credit 충전·웹훅, waitlist, outbox, notification). 운영 코드·기존 테스트·dev 브랜치 변경 없음.
- 방식: 별도 worktree(`git worktree`, 스크래치 디렉터리)에서 전환 전 커밋을 열고 골든·HTTP 테스트를 실행. 가이드 5절 "대상이 이미 Kotlin인 경우"에 해당.
- 환경: JDK 21.0.11, Gradle wrapper, Docker 데몬 기동 중(Testcontainers용). 실행 시각 2026-10-26 18:04~18:06.

## 1. 결론

**Java 기준 재현 가능.** 전환 전 커밋 `a380133`(= `61f7cd2^`)에서 대상 DTO가 전부 Java였고, 같은 골든·HTTP 테스트가 Java 상태에서 통과했다. 현재 `dev`(Kotlin)에서도 같은 테스트가 통과한다. 골든 파일과 테스트 코드는 `61f7cd2` 이후 바뀌지 않았다(`git diff 61f7cd2 HEAD`에서 변경 없음, 예약 상태 이력 골든 1개만 추가).

따라서 1단계 보고서의 "전환 전 기준이 아님(확인 불가)"은 이렇게 갱신한다: **예약 DTO 골든과 충전·PG·웹훅·대기·알림 인라인 골든은 Java 출력과 일치함이 확인됨.**

## 2. 기준 커밋 선택

| 후보 | 상태 | 판단 |
| --- | --- | --- |
| `8f47467^` (7613bc0) | credit/dto·waitlist·notification·outbox는 이미 Kotlin, webhook은 Java 5·Kotlin 3 혼재 | 부적합 |
| `020492a^` (6da7870) | waitlist·notification·outbox Java·Kotlin 혼재 | 부적합 |
| **`61f7cd2^` (a380133)** | credit/dto 5·webhook 8·pg 7·waitlist 8·notification 11·outbox 6·reservation/dto 12 모두 Java 위주 | **채택** |

근거: 각 worktree에서 `backend/src/main`의 `.java`/`.kt` 파일 수를 영역별로 센 결과. 대상 클래스 7개(`ChargeOrderResponse`, `ChargeOrderHistoryResponse`, `PgWebhookEvent`, `WaitlistEntryResponse`, `NotificationResponse`, `ReservationResponse`, `AdminReservationDetailResponse`)는 `a380133`에서 `.java`만 존재함을 확인했다.

## 3. 실행 기록

작업 트리: `a380133` 체크아웃 + 아래 파일을 `61f7cd2`에서 **그대로 복사**(미커밋, worktree 한정). 복사본은 원본과 동일하며 수정하지 않았다.

- `credit/dto/FridayRemainingDtoGoldenTest.java`
- `reservation/dto/ReservationDtoGoldenTest.java`
- `reservation/controller/ReservationDtoContractTest.java`
- `src/test/resources/golden/reservation/*.json`

| 환경 | 명령 | 종료 코드 |
| --- | --- | --- |
| Java(`a380133`) 골든 | `./gradlew cleanTest test --tests '*FridayRemainingDtoGoldenTest' --tests '*ReservationDtoGoldenTest' --tests '*ReservationDtoContractTest'` | 0 |
| Java(`a380133`) HTTP | `./gradlew cleanTest test --tests '*ChargeOrderControllerTest' '*WaitlistControllerTest' '*NotificationControllerTest' '*ReservationControllerTest' '*AdminReservationControllerTest' '*TossWebhookIntegrationTest' '*NotificationReadIntegrationTest'` | 0 |
| Kotlin(`dev` @ 5555e47) 전체 위 10개 클래스 | 같은 형태, `cleanTest` 포함 | 0 |

테스트 수는 XML 결과(`build/test-results/test/*.xml`)에서 확인: 실패 0, 오류 0, 스킵 0.

| 테스트 클래스 | Java | Kotlin(dev) |
| --- | --- | --- |
| `FridayRemainingDtoGoldenTest` | 15 | 15 |
| `ReservationDtoGoldenTest` | 15 | 15 |
| `ReservationDtoContractTest` | 36 | 36 |
| `ChargeOrderControllerTest` | 14 | 14 |
| `TossWebhookIntegrationTest` | 13 | 13 |
| `WaitlistControllerTest` (표시명 "대기 신청 컨트롤러 웹 계층 테스트") | 6 | 6 |
| `NotificationControllerTest` | 3 | 3 |
| `NotificationReadIntegrationTest` | 2 | 2 |
| `AdminReservationControllerTest` | 6 | 6 |
| `ReservationControllerTest` | 1 | 1 |

## 4. 한계·미확인

- 위 테스트가 **보호하는 범위**만 Java 기준이 확인된 것이다. 1단계 4절의 빠진 곳(충전 FAILED·값이 채워진 이력 응답, 웹훅 결과 enum 전 값, 대기 CANCELLED, 알림 `read=true`, Outbox payload, `/pay`·extend·force-cancel 응답과 409)은 여전히 골든이 없다.
- `a380133`이 "전환 직전의 마지막 순수 Java"라는 점은 파일 수와 대상 클래스 확장자로만 확인했다. 이후 커밋에서 DTO 동작을 바꾼 변경이 없는지는 골든이 같은 값으로 통과하는 사실로 간접 확인했다.
- 통합 테스트 중 동시성·복구 계열(`PgWebhookEventProcessorIntegrationTest` 등)은 이번에 실행하지 않았다.
- worktree 3개(`wt-8f47467`, `wt-020492a`, `wt-61f7cd2`)는 3단계 검증 후 사용자 승인으로 제거했다. 같은 기준이 필요하면 `git worktree add --detach <경로> a380133`으로 다시 만든다.

## 5. 3단계 제안 (사용자 "다음" 후)

`a380133` worktree에서 빠진 사례를 Java 출력으로 확보하고 정적 JSON으로 저장한 뒤, 같은 입력을 `dev`에서 비교한다. 우선순위(위험 대비 비용):

1. 충전: FAILED·값이 채워진 이력 응답(`ChargeOrderHistoryResponse`)
2. 웹훅 결과 enum 전 값, 대기 CANCELLED 응답, 알림 `read=true` 응답
3. Outbox payload(`OutboxRecordIntegrationTest.assertEvent`가 보는 형식)
4. `/pay`·extend·force-cancel 성공 응답과 409 응답 본문

규칙: 새 파일만 추가, 운영 코드·기존 테스트 수정 금지, 외부 API는 Fake만, 테스트는 `cleanTest` 포함, 커밋은 대상 파일 목록 확인 후.

## 6. 리뷰 체크리스트

- [ ] `a380133`이 기준으로 적절한가(2절 표)
- [ ] worktree에 복사한 테스트·골든이 `61f7cd2`와 동일한가(`git show 61f7cd2:<path>` 대조)
- [ ] 10개 클래스의 Java·Kotlin 테스트 수가 3절 표와 같은가
- [ ] 저장소(`dev`) 작업 트리 변경 없음(`git status` clean)

## 7. 사용자 결정이 필요한 질문

1. 3단계 우선순위(5절)와 범위를 이대로 갈지.
2. 새 골든을 Kotlin 테스트(`src/test/kotlin`)로 추가할지, Java 테스트로 추가할지(현재 기존 테스트는 Java, 전환 방향은 Kotlin).
3. worktree 정리 시점.
