# 골든 현황 조사 — 4단계 독립 검증 결과

- 검증자: Claude (구현자 Codex와 별도 세션). 구현자 보고서: [step-4.md](step-4.md)
- 검증 시각: 2026-10-27 00:06~00:30(KST). 브랜치 `test/golden-http-bodies` @ `0703cc9`, 작업 트리에 미추적 파일과 `backend/test-baseline.txt` 수정만 있음.
- 환경: JDK 21.0.11, Gradle wrapper, Docker Testcontainers MySQL.

## 판정: 통과 (중 이상 문제 없음)

| 확인 | 방법 | 결과 |
| --- | --- | --- |
| 전체 테스트 | `./gradlew cleanTest test`, XML 합산 | 종료 0, **1,361건 / 실패 0 / 오류 0 / 스킵 0**, XML 189개. 보고서와 일치 |
| 신규 테스트 포함 | XML 클래스별 수 | 충전 confirm 1, 대기 신청 1, 알림 목록 1, 웹훅 빈 본문 1, Outbox 리스너 3, 토픽·그룹 상수 1 = **8건** |
| CI 기준선 검사 | `check_test_count.py` (CI와 같은 스크립트) | 통과: `suites=189 tests=1361 skipped=0 failures=0 errors=0 baseline=1322` |
| Java 기준 재현 | 새 worktree에 `a380133` 체크아웃 → 문서(`step-3.md` 재현 절차)대로 step-3 패치, 기존 골든, step-4 패치 적용 → `*HttpBodyJavaGoldenTest` | 두 패치 모두 적용 성공, 종료 0, **3건 통과**. 운영 코드가 `.java`임 확인 |
| 골든 바이트 일치 | Java worktree 골든 ↔ 저장소 골든 `cmp`, SHA-256 대조 | 3개 모두 일치, 해시가 보고서와 같음 |
| 패치 SHA-256 | `step-4-java-baseline.patch` | 보고서와 일치(`202c6de8…dcd8f`). 추가 전용 4파일 |
| 골든이 실제로 지키는가 | 골든 3개를 일부러 변조(충전 `amount` 1000→1100, 대기 `position` 2→3, 알림 `totalPages` 3→4)하고 실행. 변조 적용 여부를 `cmp`로 먼저 확인 | 각 테스트 1건씩 실패, 원복 후 바이트 동일 |
| 운영 코드·기존 파일 변경 | `git status` | 추적 파일 변경은 `test-baseline.txt` 1개(`1314 → 1322`, 사유 주석 1줄). 운영 코드·기존 테스트·기존 골든 변경 0, stage·커밋 없음 |
| 금지 패턴 | 새 Kotlin 7개 파일 grep | Lombok·`data class`·골든 쓰기/갱신 코드·`@Disabled`·시크릿 값 없음. JWT 시크릿은 `@Value`로 테스트 프로파일에서 주입 |
| lint·빌드 | `ktlintCheck`, `clean build -x test` | 종료 0 (ktlint는 입력 불변으로 이전 통과 결과 재사용) |

## 코드 검토 소견

- **B5(Outbox → 리스너)**: 기대 `eventId` 3개를 리터럴로 두고 리스너가 파싱한 값으로 기대값을 만들지 않는다. 리터럴은 골든 파일의 값과 같다. `verifyNoMoreInteractions`로 추가 호출도 막는다.
- **B4(웹훅 빈 본문)**: 응답 바이트 길이 0을 검사한다. JSON으로 파싱하지 않아 `null`·`{}`도 실패한다.
- **A(HTTP 본문 3개)**: 운영 컨트롤러·MVC 직렬화·응답 포장은 실제 경로이고 서비스만 대역이다. 입력의 ID·시각을 고정해 정규화 없이 트리 동등 비교한다.

## 발견 사항 (비차단)

- **실제 서비스·DB 경로는 이 테스트의 범위 밖이다.** A는 서비스 계층을 대역으로 두므로 HTTP 응답 계약만 보호한다. 상태 전이·정합성은 기존 통합 테스트 몫이다(보고서도 같은 한계를 적음).
- **B6(상수 2개)**는 값만 확인한다. 실제 `@KafkaListener` 설정이 이 상수를 쓰는지는 확인하지 않는다.
- **기준선 하한(1,322)과 실제 수(1,361) 사이에 39건 간격이 있다.** 프로젝트 규칙대로 "새 테스트 수만큼 올림"을 지킨 결과이며, 이전부터 있던 간격이다.
- **직접 검증하지 못한 것:** 프론트 검사, CI(PR에서 확인). Java 재현은 새 골든 3건에 한정했고 이전 단계 Java 테스트를 재실행하지 않았다.
- 이번 검증에서 만든 임시 worktree는 제거했다.

## 다음 조치

- 커밋은 코드와 문서를 나눈다. 코드: Kotlin 7개 + 골든 JSON 3개 + `test-baseline.txt`. 문서: `step-4.md`, `step-4-verification.md`, `step-4-java-baseline.patch`.
