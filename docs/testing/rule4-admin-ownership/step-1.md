# 규칙 4: ADMIN 역할의 타인 예약·도어 접근 거부 — step-1 구현 인계

- 이슈: [#194](https://github.com/prgrms-be-devcourse/NBE12-14-3-OVENGERS/issues/194)
- 구현 일자: 2026-10-27
- 브랜치: `test/admin-role-ownership`
- 기준: `git fetch origin dev` 후 `origin/dev`의 `82676f0`에서 생성.
- 작업 도중 원격 `dev`는 `d0512fe`(#197, 빈 테스트 3개 삭제와 관련 문서 정리)로 전진했다. 이 브랜치는 생성 시점의 최신 기준 `82676f0`를 유지하며 최종 작업 브랜치도 `test/admin-role-ownership`이다.
- 역할: Codex 구현 세션의 직접 실행 결과이다. **독립 검증 완료를 의미하지 않으며, 다른 세션의 검증은 대기 중**이다.
- 범위: step-1의 Kotlin 보호 테스트와 인계 기록만 추가한다. 운영 동작·스키마·API 계약은 변경하지 않는다.

## 1. 변경 파일

| 파일 | 변경 내용 | 커밋 구분 |
| --- | --- | --- |
| `backend/src/test/kotlin/com/ovengers/slotkey/reservation/controller/AdminRoleOwnershipIntegrationTest.kt` | ADMIN JWT의 거부 사례 7개와 성공 대조군 2개 | 코드 |
| `docs/decisions/doc-code-consistency-checks.md` | §5에 규칙 4 구현 결과와 이 보고서 링크 추가 | 문서 |
| `docs/testing/rule4-admin-ownership/step-1.md` | 실행·변조 실험·원복·검증 인계·커밋 및 PR 초안 | 문서 |

## 2. 추가한 테스트와 확인 내용

`ReservationIntegrationTestSupport`를 상속하고 `@AutoConfigureMockMvc`, `@Import(SettableClockConfig::class)`를 사용한다. MySQL 8.4 Testcontainers와 실제 Spring 인증 필터·서비스·트랜잭션을 거치며 Mock 인증이나 서비스 대역으로 소유권을 대체하지 않는다.

준비 방식:

- 고정 시각은 `2026-10-26 14:00 Asia/Seoul`이다. 예약은 30분 슬롯 2개이며 체크아웃만 현재 시각에 시작하고, 나머지는 1시간 뒤 시작한다.
- 소유자는 잔액 30,000으로 생성한다. 관리자는 `createMember(0)` 후 SQL로 `ADMIN` 역할로 변경한다. 둘은 서로 다른 회원이다.
- 두 회원 모두 `issueSessionBoundAccessToken`으로 실제 DB 세션에 연결된 JWT를 발급한다. 관리자 JWT의 `role = ADMIN`도 직접 확인한다.
- 확정·이용 중 예약은 `CreditService.charge`로 10,000 차감 원장을 준비하고 소유자 HTTP 요청으로 활성 도어 토큰 1개를 발급한다. 소유자 잔액은 20,000이 된다.
- `IN_USE` 준비는 `CONFIRMED` 예약 생성 후 `status`와 `checked_in_at`을 하나의 SQL로 갱신하여 MySQL CHECK 제약을 지킨다. HELD는 유효한 10분 홀드이며 원장·도어 토큰이 없다.

| 테스트 호출 | 사전 상태 | 기대 응답 |
| --- | --- | --- |
| `POST /api/v1/reservations/{id}/cancel` | `CONFIRMED`, 시작 전 | 403 `FORBIDDEN_NOT_OWNER` |
| `POST /api/v1/reservations/{id}/extend` | `CONFIRMED`, 실제 종료 시각과 30분 연장 바디 | 동일 |
| `POST /api/v1/reservations/{id}/pay` | `HELD`, `Idempotency-Key`와 DB의 실제 `spaceVersion` 바디 | 동일 |
| `POST /api/v1/reservations/{id}/check-out` | `IN_USE`, `checked_in_at` 있음 | 동일 |
| `POST /api/v1/reservations/{id}/door-token` | `CONFIRMED`, 기존 활성 토큰 있음 | 동일 |
| `PATCH /api/v1/reservations/{id}/access-token/revoke` | `CONFIRMED`, 활성 토큰 있음 | 동일 |
| `GET /api/v1/reservations/{id}/access-logs` | `CONFIRMED` | 동일 |

각 매개변수 사례는 별도 준비·정리 단위를 가진다. 호출 전후 다음 값을 비교한다.

- 예약 행 전체(상태·시간·금액 포함), 슬롯 행 전체 및 슬롯 수 2개.
- 소유자 및 관리자 잔액, 두 회원 또는 해당 예약에 연결된 원장 행 전체.
- 활성 도어 토큰의 행 전체와 모든 토큰 행 전체: 개수뿐 아니라 기존 ID·해시·폐기 시각 보존도 확인한다.
- 예약 상태 이력과 출입 로그 행 전체.
- 두 회원의 `idempotency_key` 행 전체 및 0건 확인. pay 거부가 키를 소진하지 않는다.
- 두 회원이 행위자이거나 해당 예약을 대상으로 하는 `audit_logs` 행 전체 및 0건 확인.

대조군:

1. 같은 `CONFIRMED`·시작 전·원장·활성 토큰 조건에서 소유자 JWT의 일반 cancel이 200을 반환한다.
2. 같은 조건에서 ADMIN JWT의 `/api/v1/admin/reservations/{id}/force-cancel`이 200을 반환한다.

둘 다 `CANCELLED`, 슬롯 0개, 활성 토큰 0개, 소유자 잔액 30,000 복구, 관리자 잔액 0 유지, `REFUND` 1건·10,000을 확인한다. 일반 취소는 감사 로그 0건이고, 관리자 강제 취소는 해당 관리자의 `FORCE_CANCEL_RESERVATION` 감사 로그 1건을 확인한다.

## 3. 실행한 명령과 결과

명령 실행 위치는 별도 표기가 없으면 `backend/`이다. 메일 자격 증명과 Kafka 활성 환경변수의 영향을 제거했으며 값을 출력하지 않았다. 시크릿을 추가하거나 실제 외부 API를 호출하지 않았다.

```bash
# 저장소 루트
git fetch origin dev
git switch -c test/admin-role-ownership origin/dev
docker info --format '{{.ServerVersion}}'

# backend/
env -u SPRING_MAIL_USERNAME -u SPRING_MAIL_PASSWORD \
  -u VERIFICATION_MAIL_FROM -u SLOTKEY_KAFKA_ENABLED \
  ./gradlew --no-daemon test --tests '*AdminRoleOwnershipIntegrationTest'

./gradlew --no-daemon ktlintCheck

# 운영 코드 변조 후 같은 테스트 명령 재실행, finally에서 원본 바이트 복원
env -u SPRING_MAIL_USERNAME -u SPRING_MAIL_PASSWORD \
  -u VERIFICATION_MAIL_FROM -u SLOTKEY_KAFKA_ENABLED \
  ./gradlew --no-daemon test --tests '*AdminRoleOwnershipIntegrationTest'

# 원복 후 최종 검사
env -u SPRING_MAIL_USERNAME -u SPRING_MAIL_PASSWORD \
  -u VERIFICATION_MAIL_FROM -u SLOTKEY_KAFKA_ENABLED \
  ./gradlew --no-daemon ktlintCheck test

# 저장소 루트
git diff --exit-code -- backend/src/main
git diff --check
```

| 실행 | 결과 |
| --- | --- |
| Docker 확인 | Server 29.6.1 |
| 신규 클래스 최초 실행 | **9개 통과**, 실패·오류·스킵 0, Gradle exit 0, 43초 |
| `ktlintCheck` 최초 실행 | 신규 파일 줄바꿈 위반으로 실패. 신규 파일만 수정함 |
| `ktlintCheck` 수정 후 | 통과, Gradle exit 0, 11초 |
| 소유권 검사 변조 후 신규 클래스 | **9개 중 거부 사례 7개 실패, 대조군 2개 통과**, 오류·스킵 0, Gradle exit 1, 29초 |
| 원복 후 `ktlintCheck test` | **통과**, 190개 XML suite의 **1,370개 전부 통과**, 실패·오류·스킵 0, Gradle exit 0, **11분 34초** |
| 원복 후 신규 클래스(위 전체 실행에 포함) | **9개 통과**, 실패·오류·스킵 0, 클래스 실행 시간 1.995초 |
| 운영 코드 diff·공백 검사 | `git diff --exit-code -- backend/src/main` 및 `git diff --check` exit 0. 신규 파일 2개도 `git diff --no-index --check /dev/null <파일>`로 별도 확인 |

임시 원시 로그·XML은 `/tmp/slotkey-rule4-{initial,mutation,full,ktlint}.*`에 저장한다. 테스트 JWT가 포함될 수 있는 원시 로그를 저장소에 복사하지 않는다. 이 보고서에 실행 수치와 실패 메시지를 기록하여 `/tmp`가 정리되어도 인계 근거가 남도록 한다.

## 4. 소유권 검사를 깨뜨린 실험과 원복

현재 동작을 고정하므로 최초 테스트는 통과한다. 요청에 따라 다음 **5곳의 소유권 거부 조건 앞에 `false &&`를 붙여 검사를 일시적으로 우회**했다. ADMIN에 한정한 우회보다 넓게, 소유권 거부 자체를 비활성화한 변조다. 운영 변경으로 채택한 것이 아니다.

| 파일 | 변조한 조건 |
| --- | --- |
| `ReservationCancelService.kt` | `reservation.memberId != requiredMemberId` |
| `ReservationExtendService.kt` | 동일 |
| `ReservationPaymentConfirmService.kt` | `targetInfo.memberId != requiredMemberId` |
| `ReservationCheckOutService.kt` | `reservation.memberId != requiredMemberId` |
| `DoorAccessAuthorizationService.kt` | `!isOwner(loginMemberId, reservationMemberId)` — 발급·폐기·로그 조회 공통 |

7개 모두 `Status expected:<403> but was:<...>` 단언에서 실패했다. 준비 실패나 인증 실패로 얻은 적색 결과가 아니다.

| 사례 | 변조 시 실제 상태 | 실패 수 |
| --- | --- | --- |
| cancel | 200 | 1 |
| extend | 422 | 1 |
| pay | 422 | 1 |
| check-out | 500 | 1 |
| door-token | 201 | 1 |
| access-token/revoke | 200 | 1 |
| access-logs | 200 | 1 |

- extend·pay는 잔액이 0인 관리자의 차감 단계에서 422를 반환했다. 따라서 소유권 검사 누락을 탐지한 근거이지, 해당 변조에서 결제·연장이 성공했다는 근거는 아니다.
- check-out은 후속 `ReservationResponse.from`에서 `UninitializedPropertyAccessException: lateinit property startTime has not been initialized`로 500을 반환했다. 소유권 검사 뒤 경로에서 관찰한 문제이며 이번 범위에서는 수정하지 않았다. 정상 소스의 관리자 요청은 이 경로에 들어가지 않고 403을 반환한다.
- 두 성공 대조군은 변조 상태에서도 통과했다.
- 실험은 실행 전 원본 바이트를 백업하고 `try/finally`로 복원했다. 5개 파일 모두 원본 바이트 일치 및 SHA-256 일치를 확인했다. `git show origin/dev:<경로>`의 바이트와도 일치했다. 이후 전체 테스트는 원복된 코드로 실행하여 1,370개가 통과했다.
- 이 실험은 소유권 거부 단언의 탐지력을 확인한다. 부작용 단언 각각을 독립적으로 변조해 검증한 실험은 아니다.

## 5. 가정한 것과 확인하지 못한 것

- 인계 표의 기존 정책을 그대로 테스트한다. 신규 설계 판단·라이브러리·인프라는 도입하지 않았다.
- 공통 지원 클래스처럼 회원 잔액·예약은 직접 fixture로 생성한다. 가입 지급부터 예약 결제까지 전체 사용자 여정을 재현하지 않는다. 확정 예약의 차감 원장과 활성 토큰은 실제 서비스를 거쳐 준비한다.
- 감사 로그와 원장은 테스트 회원·예약 범위로 검사한다. 다른 테스트 fixture나 무관한 관리 작업의 전역 행 수를 0으로 가정하지 않는다.
- 기존 전체 소스 컴파일에서 Lombok 플러그인 및 다른 테스트 파일의 경고가 관찰되었다. 기존 코드 수정은 하지 않았다. 신규 Kotlin 파일은 Lombok을 사용하지 않는다.
- 구현 보고 작성 시점에 독립 검증 세션, CI, 사람 리뷰, 원격 PR 검사는 미실행이었다. 이후 사용자가 커밋·push·PR 생성을 승인했다(§8). 별도 세션 검증과 사람 리뷰는 계속 대기 중이다.
- 공유 체크아웃 외부 변경: 전체 테스트가 진행되던 11:27:59에 다른 작업이 `dev`로 전환하고 11:28:34에 `d0512fe`로 fast-forward했다. 이 실행의 컴파일은 그 전에 끝났으며, 테스트 프로세스가 사용한 코드는 `82676f0`와 신규 테스트다. 최종 확인 때 원래 작업 브랜치로 돌아왔고 다른 작업의 `dev` 커밋은 보존했다. 두 기준의 차이는 빈 클래스 3개 삭제와 문서 2개뿐이고 실행할 테스트·운영 코드는 같다. 최종 소스는 이번 전체 테스트의 컴파일 기준과 일치한다.
- `api-spec.md`·`erd.md`의 계약 변경이 없어 수정하지 않는다. 배포 설정·마이그레이션·에이전트 도구 연결도 변경하지 않는다.

## 6. 관련 결정 문서와 리뷰가 필요한 판단

- 요구사항 기준: [doc-code-consistency-checks.md §5](../../decisions/doc-code-consistency-checks.md)의 “검증 계획: 규칙 4 테스트 인계”.
- 정책 기준: [core-domain-decisions.md](../../core-domain-decisions.md) §6·§12, [authorization-policy.md](../../decisions/authorization-policy.md), [authentication-policy.md](../../decisions/authentication-policy.md).
- 리뷰할 테스트 판단: 확정 예약에 기존 차감 원장·토큰을 준비하여 기존 행의 보존까지 검증한다. HELD에 토큰을 억지로 넣지 않는다.
- 기존 결정 문서의 기각한 대안·Trade-off·재검토 조건은 유지한다. 규칙 4 구현 결과만 §5에 추가한다.
- 위 체크아웃 변조 경로의 500은 별도 조사 후보이며, 이번 테스트 추가의 범위를 넓히지 않았다.

## 7. 리뷰 체크리스트

- [ ] 기준 브랜치가 최신 fetch 당시 `origin/dev`의 `82676f0`인지 확인한다.
- [ ] ADMIN은 소유자와 다른 ACTIVE 회원이며 DB 역할 변경 후 세션 JWT를 발급한다.
- [ ] JWT `role`이 실제 `ADMIN`이며 `sid`가 실제 인증 세션에 연결된다.
- [ ] 7개 경로·HTTP 메서드와 사전 상태가 결정 문서 표와 일치한다.
- [ ] pay는 헤더 `Idempotency-Key`와 실제 공간 버전 바디를 보낸다.
- [ ] extend는 기존 종료 시각과 30분 연장 시각을 보낸다.
- [ ] 거부 결과가 일반적인 접근 금지나 401이 아닌 403 `FORBIDDEN_NOT_OWNER`다.
- [ ] 거부 전후 예약·슬롯·소유자 잔액·원장·활성 토큰·전체 토큰·이력·로그가 같다.
- [ ] pay 거부 후 관리자·소유자의 멱등키가 0건이며 감사 로그도 0건이다.
- [ ] 소유자 cancel과 관리자 전용 force-cancel은 200이며 실제 취소·환불·토큰 폐기를 확인한다.
- [ ] 강제 취소만 ADMIN의 감사 로그 1건을 남긴다.
- [ ] 변조 시 7개 실패·대조군 2개 통과의 근거를 확인한다.
- [ ] 운영 소스 원복 후 diff가 비어 있고 신규 Kotlin에 Lombok·시크릿이 없다.
- [ ] 다른 검증 세션에서 `ktlintCheck`와 전체 `test`를 직접 다시 실행한다.
- [ ] 커밋은 사용자 확인 뒤 코드/문서로 나누며 PR은 `dev`, `@me`, `Test`, `Closes #194`를 지킨다.

## 8. 사용자 결정이 필요한 질문과 커밋 인계

기능·설계 결정 질문은 없다. 사용자가 지정한 “커밋은 사용자 확인 후” 규칙에 따라 대상 목록을 먼저 제시했고, **2026-10-27 사용자의 “올려” 승인**을 받았다. 아래와 같이 코드·문서를 분리 커밋하여 push 및 PR 생성을 진행한다. 코드 커밋은 `e1e8df4`이며 이 문서 커밋 시점에는 push·PR 생성 전이다.

1. 코드 커밋 `test(reservation): deny admin access to other members reservations`
   - `backend/src/test/kotlin/com/ovengers/slotkey/reservation/controller/AdminRoleOwnershipIntegrationTest.kt`
2. 문서 커밋 `docs(testing): record rule 4 ownership verification evidence`
   - `docs/decisions/doc-code-consistency-checks.md`
   - `docs/testing/rule4-admin-ownership/step-1.md`

`git push origin test/admin-role-ownership`으로 push하고 다음 조건의 PR을 생성한다. 생성한 PR 링크는 대화로 제공한다. 머지는 이 단계 범위에 포함하지 않는다.

- 제목: `[Test] 규칙 4: 관리자 역할의 타인 예약·도어 접근 거부 테스트`
- 대상: `dev`
- 담당자: `@me`
- 라벨: `Test`

### PR 본문 초안

```markdown
## Summary

Closes #194

ADMIN 세션 JWT의 타인 예약 접근을 403 `FORBIDDEN_NOT_OWNER`로 고정합니다.

    ADMIN → 타인 예약
      POST cancel / extend / pay / check-out / door-token → 403
      PATCH access-token/revoke → 403
      GET access-logs → 403

예약 상태·슬롯·소유자 잔액과 원장·활성 도어 토큰을 포함한 DB 행을 전후 비교하고,
멱등키(pay)·감사 로그가 남지 않는지 확인합니다.
소유자 cancel 및 관리자 전용 force-cancel 성공을 대조군으로 추가합니다.

## Evidence

- 기존 운영 코드에서 신규 9개 테스트 통과.
- 소유권 거부 조건 5곳을 일시 우회하면 7개 거부 테스트 모두 실패하고 대조군 2개는 통과.
  - cancel 200, extend/pay 422, check-out 500, door-token 201, revoke/access-logs 200.
  - 모두 기대한 403 단언에서 실패. check-out의 후속 응답 변환 500은 별도 관찰로 기록.
- 운영 소스 5개 원복 및 `git diff --exit-code -- backend/src/main` 확인.
- 원복 후 `ktlintCheck test`: 190개 XML suite의 전체 1,370개 통과, 실패·오류·스킵 0,
  Gradle exit 0, 11분 34초. 신규 9개도 포함하여 통과.
- 상세 명령·수치·한계: `docs/testing/rule4-admin-ownership/step-1.md`.
- 별도 세션 검증과 CI는 대기 중.

## Merge Danger

**Door:** two-way

테스트와 검증 기록만 추가하므로 되돌릴 수 있습니다. 운영 코드·스키마 변경은 없습니다.

**Blast Radius:** 테스트 시간

MySQL Testcontainers 기반 HTTP 통합 테스트 9개가 추가되어 CI 실행 시간이 늘어납니다.
```
