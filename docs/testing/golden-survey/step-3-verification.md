# 골든 현황 조사 — 3단계 독립 검증 결과

- 검증자: Claude (구현자 Codex와 별도 세션). 구현자 보고서: [step-3.md](step-3.md)
- 검증 시각: 2026-10-26 22:07~22:20(KST). 브랜치 `test/golden-survey-step3` @ `5555e47`, 작업 트리에 미추적 파일만 있음.
- 환경: JDK 21.0.11, Gradle wrapper, Docker Testcontainers MySQL.

## 판정: 통과 (중 이상 문제 없음)

보고서의 수치와 주장을 그대로 믿지 않고 아래를 직접 실행·대조했다.

| 확인 | 방법 | 결과 |
| --- | --- | --- |
| 전체 테스트 | `./gradlew cleanTest test`, XML 합산 | 종료 0, **1,353건 / 실패 0 / 오류 0 / 스킵 0**, XML 183개. 보고서와 일치 |
| 새 테스트 포함 | XML 클래스별 수 | Webhook 10, Reservation HTTP 4, Outbox 3, Charge 2, Notification 1, Waitlist 1 = **21건** |
| Java 기준 재실행 | `a380133` worktree에서 새 Java 테스트 6클래스 실행 | 종료 0, 21건 통과(저장소 골든과 동일 파일 사용) |
| 골든 바이트 일치 | Java 최초 확보 출력 ↔ 저장소 골든 ↔ Java worktree 골든 `cmp` | 21개 모두 일치, 불일치 0 |
| 골든이 실제로 지키는가 | 골든 3개를 일부러 변조(충전 금액 1000→1100, 결제 totalAmount 10000→10001, Outbox reservationId 10→11)하고 실행 | 변조한 3곳에서 각 1건씩 실패(실패 메시지가 변조 값을 가리킴), 원복 후 재통과. 원복은 `cmp`로 바이트 동일 확인 |
| 운영 코드·기존 파일 변경 | `git diff`, `git status` | 추적 파일 변경 0, `src/main` 변경 0, stage·커밋 없음 |
| 금지 패턴 | 새 Kotlin 8개 파일 grep | Lombok·`data class`·시크릿·`@Disabled`·골든 쓰기/갱신 코드 없음 |
| 재현 패치 | SHA-256 및 내용 | 보고서의 `f23ac09d…343f`와 일치. 신규 8개 Java 파일만 추가(수정·삭제 0) |
| 변동 필드 | `OutboxPayloadGoldenTest` 코드 | `eventId`는 정규화하지 않고 UUID v4·variant 2·DB `event_id` 일치로 검증, 나머지 필드는 골든과 직접 비교 |

## AGENTS.md 검증 항목 대조

1. 전체 테스트 직접 실행: 위 표.
2. 절대 규칙 1–7 위반: 운영 코드 변경이 없어 해당 없음. 새 테스트가 `/pay`·연장·강제 취소(관리자 경로)·만료 409를 실제 HTTP로 확인한다.
3. 에이전트 코드에 상태 변경 도구 연결: 에이전트 코드 변경 없음.
4. Kotlin Lombok·엔티티 `data class`·시크릿·배포 job: 해당 없음(위 grep, 설정 파일 변경 없음).
5. 동시성·멱등성 경계: 새 테스트가 `/pay` 동일 키 재요청 시 차감·원장 1회를 확인한다. 동시 실행 테스트 자체는 추가 범위 아님.
6. `api-spec.md`·`erd.md` 갱신: 동작·스키마 변경이 없어 불필요.
7. decision 문서: 새 기능·기술 도입이 없어 새 문서 없음. "기각한 대안·Trade-off·재검토 조건" 점검 대상 아님.

## 발견 사항 (비차단)

- **1단계 보고서 오류 정정함:** 알림 응답에 `readAt` 필드가 있다고 적었으나 실제는 없다(`id/waitlistEntryId/message/read/createdAt`). `step-1.md`를 고쳤다. 구현자가 먼저 지적했다.
- **`docs/lab/`은 개인 문서 폴더라 로컬 제외 대상이다.** 보고서·패치·검증 문서를 `docs/testing/golden-survey/`로 옮겼다(사용자 결정). 패치 SHA-256은 이동 전후 같다.
- **추가 실행(검증 후):** `./gradlew clean build -x test`(CI `backend-ci.yml`과 같은 명령) 종료 0, ktlint main·test 소스 검사 통과. 프론트는 변경이 없어 실행하지 않았다.
- **Outbox 범위:** `SlotReleasedRecorder`의 저장 payload까지만 고정했다. Kafka 전달·컨슈머까지의 메시지는 범위 밖이다.
- **DTO 팩토리 골든 한계:** 대기 `CANCELLED`(position=0)와 알림 `read=true`는 응답 팩토리의 직렬화만 고정하며, 해당 API 응답 본문이 아니다. 대기 취소 API는 204 본문 없음이 기존 계약이다.
- **직접 검증하지 못한 것:** 전체 `clean build`, 프론트 검사, CI. 이번 변경은 테스트 추가뿐이라 targeted + 전체 테스트로 확인했다. 최종 확인은 CI와 사람 리뷰다. 동시성·복구 계열 통합 테스트는 전체 테스트 실행에 포함됐다(1,353건 통과).
- **ktlint:** 구현자 보고(새 파일 22개 위반 수정 후 종료 0)는 직접 재실행하지 않았다. 전체 빌드 단계에서 확인된다.

## 다음 조치

- 커밋은 코드 커밋과 문서 커밋으로 나눈다. 코드: Kotlin 테스트 8개 + 골든 JSON 21개. 문서: `docs/testing/golden-survey/` 5개 파일.
- scratchpad의 worktree 3개는 사용자 승인 후 제거했다.
