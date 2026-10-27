# 골든 현황 조사 — 5단계: 인증·회원·문의 응답 DTO

## 1. 범위·판정

- 담당: 천종원(`@vvipia`) — `auth`·`member`·`inquiry` ([팀원별 상세 담당](../../plans/kotlin-migration/team-guide.md)). 1~4단계는 이태호 담당 도메인의 기록이며, 이 단계는 그 절차를 세 도메인에 적용했다.
- 작업일: 2026-10-27(KST). 브랜치 `test/golden` @ `9fd9e35a66fabe5a50ce6c7d9a82454d1a50db19`.
- 배경: 세 도메인은 운영 코드(79개)와 테스트가 모두 Kotlin으로 전환됐지만 `golden/`에 해당 디렉터리가 없었다. 기존 응답 검증은 컨트롤러 테스트의 부분 `jsonPath` 단언뿐이라 필드 누락·추가·`null` 표현을 전체로 고정하지 못했다.
- 결과: 응답 DTO 골든 **16건**(Auth 4, Member 10, Inquiry 2)을 추가했다. Java 기준 생성 → 정적 파일 저장 → 같은 입력의 Kotlin 비교 통과.
- 검증은 구현자 **자기검증**이다. 별도 세션의 독립 검증 문서(`step-5-verification.md`)는 없으며, CI와 PR 리뷰로 확인한다.
- 운영 코드·기존 테스트·기존 골든·API 명세 변경 없음. 기존 추적 파일 수정은 `backend/test-baseline.txt` 하나다(`1322 → 1338`, 사유 주석 1줄).

## 2. Java 기준 커밋 선택

[가이드 §5](../java-kotlin-golden-guide.md#5-불일치와-기준-파일-변경-처리)의 "대상이 이미 Kotlin임" 경우에 해당한다. 1~4단계가 쓴 순수 Java 커밋 `a380133`에서는 세 도메인의 응답 DTO가 **이미 Kotlin**이었다(Step 2, #39 `ab42ae8`에서 전환). 그래서 #39 직전 커밋을 기준으로 삼았다.

| 커밋 | 세 도메인 응답 DTO |
| --- | --- |
| `f2273a558c5e1fe47c7fdb86d5da6f07acc240db` (`f2273a5`, #38) | Java record 12개 — **기준** |
| `ab42ae8` (#39) | Kotlin `data class`로 전환 |
| `a380133` (1~4단계 기준) | 이미 Kotlin — 이 단계의 기준으로 쓸 수 없음 |

## 3. 사례별 대응

경로는 `backend/src/test/resources/golden/` 기준이다. 모든 입력은 고정 값이며 실행 시각·DB 자동 증가 ID·난수가 없다. 기준 시각은 `2026-10-20T09:30:15`, 회원은 ID 7·`golden.user@example.com`·`골든회원`·잔액 300000이다.

| 골든 | 생성 경로 | Kotlin 테스트 | 고정하는 경계 |
| --- | --- | --- | --- |
| `auth/signup-response` | `SignupResponse(member)` | `AuthGoldenTest.signupResponse` | 엔티티 → 응답 3필드 |
| `auth/login-response` | 생성자 | `AuthGoldenTest.loginResponse` | 본문은 `accessToken` 하나 |
| `auth/verification-issue-response` | 생성자 | `AuthGoldenTest.verificationIssueResponse` | `challengeId`, 날짜 2개 |
| `auth/verification-check-response` | 생성자 | `AuthGoldenTest.verificationCheckResponse` | grant 원문, 만료 시각 |
| `member/member-response` | `MemberResponse(member)` | `MemberGoldenTest.memberResponse` | `status` 없음, 큰 잔액, enum |
| `member/status-change-suspended` | `MemberStatusChangeResponse.of` | `MemberGoldenTest.statusChangeSuspended` | 정지 상태·사유·시각 |
| `member/admin-member-without-status-change` | `AdminMemberResponse.of(m, null, null)` | `MemberGoldenTest.adminMemberWithoutStatusChange` | 마지막 변경 시각·사유 `null` |
| `member/admin-member-with-status-change` | `AdminMemberResponse.of` | `MemberGoldenTest.adminMemberWithStatusChange` | 정지 상태와 변경 정보 |
| `member/admin-member-detail` | 생성자 | `MemberGoldenTest.adminMemberDetail` | 중첩 객체, long 건수 |
| `member/admin-reservation-confirmed` | 생성자 | `MemberGoldenTest.adminReservationConfirmed` | 예약 구간·상태·금액 |
| `member/admin-reservation-deleted-space-null-amount` | 생성자 | `MemberGoldenTest.adminReservationDeletedSpaceNullAmount` | `"삭제된 공간"`, `totalAmount` `null` |
| `member/admin-credit-transaction-admin-grant` | 생성자 | `MemberGoldenTest.adminCreditTransactionAdminGrant` | 거래 유형·잔액 |
| `member/admin-credit-transaction-null-reason` | 생성자 | `MemberGoldenTest.adminCreditTransactionNullReason` | 음수 금액, 사유 `null` |
| `member/admin-status-history-system-lock` | 생성자 | `MemberGoldenTest.adminStatusHistorySystemLock` | 시스템 처리 `actorMemberId` `null` |
| `inquiry/inquiry-waiting` | `InquiryResponse.from(Inquiry.create(...))` | `InquiryGoldenTest.inquiryWaiting` | 답변 필드 3개 `null` |
| `inquiry/inquiry-answered` | `from` + `inquiry.answer(...)` | `InquiryGoldenTest.inquiryAnswered` | 답변 내용·답변자·답변 시각 |

- 엔티티 생성이 양쪽에서 같은 API(`Member(email, nickname)`, `Inquiry.create`, `answer`, `updateStatus`)인 경우는 실제 팩토리 경로를 고정했다. ID·`createdAt`·`balance`는 `ReflectionTestUtils.setField`로 넣었다.
- 예약·거래·감사 로그처럼 엔티티 생성 API가 Java와 Kotlin에서 다른 응답은 DTO 생성자에 같은 값을 넣었다. 이 경우 `from(entity)` 매핑 자체는 고정하지 않는다.
- 비교는 기존 `SurveyGoldenAssertions.assertGolden`(JsonNode 트리 동등 비교)을 재사용한다. 키 순서·공백만 무시하고, 필드 추가·누락·`null`·값·타입은 그대로 비교한다. 정규화 없음.

## 4. Java worktree 재현

```bash
# 저장소 루트
git worktree add --detach <임시 경로> f2273a558c5e1fe47c7fdb86d5da6f07acc240db
cd <임시 경로>
git apply <현재 저장소>/docs/testing/golden-survey/step-5-java-baseline.patch
cd backend
GOLDEN_OUT=<캡처 디렉터리> ./gradlew test --tests 'com.ovengers.slotkey.golden.AuthMemberInquiryJavaGoldenCapture' --rerun-tasks
```

- [step-5-java-baseline.patch](step-5-java-baseline.patch)는 Java 테스트 1파일을 추가만 하는 패치다(SHA-256 `bdaca4c7d4f0e96709246e1a103ebdded1b37df64fe718e5dcd60420dc7fc884`). `f2273a5`에서 `git apply --check` 통과를 확인했다.
- `@JsonTest`의 Spring `ObjectMapper`로 직렬화하고 기본 pretty printer로 `GOLDEN_OUT/<domain>/<name>.json`에 쓴다. Java 실행 결과: 1 test, 실패·오류·스킵 0.
- 캡처 JSON 16개를 검토한 뒤 그대로 복사했다. 저장소의 골든 16개는 캡처 원문과 SHA-256이 모두 같다(재포맷·값 편집 없음).
- 캡처용 Java 테스트는 저장소에 추가하지 않았고 worktree는 정리했다.

## 5. 실행 근거

| 명령 (backend) | 결과 |
| --- | --- |
| `./gradlew ktlintCheck test --tests '*AuthGoldenTest' --tests '*MemberGoldenTest' --tests '*InquiryGoldenTest' --rerun-tasks` | 종료 0. Auth 4, Member 10, Inquiry 2 모두 실패·오류·스킵 0 |
| 실패 확인: `golden/inquiry/inquiry-waiting.json`에서 `"answerContent" : null` 줄을 지우고 `InquiryGoldenTest` 실행 | 종료 1, 2건 중 1건 실패. 확인 후 파일을 Java 캡처 원문으로 되돌렸다 |
| `./gradlew ktlintCheck cleanTest test` | 종료 0, `BUILD SUCCESSFUL in 11m 18s`, 2026-10-27 15:43:05~15:54:24 KST |
| `python3 .github/scripts/check_test_count.py backend/build/test-results/test backend/test-baseline.txt`(저장소 루트) | 종료 0, `suites=200 tests=1444 skipped=0 failures=0 errors=0 baseline=1338` |

하한은 [테스트 보호 장치](../../decisions/kotlin-test-guardrails.md)와 기존 관례대로 직전 하한 1322에 신규 16건만 더했다. 실제 전체 실행 수 1,444와 다르며, 그 차이를 이 단계에서 다시 정하지 않았다.

## 6. 변경 파일

```text
backend/src/test/kotlin/com/ovengers/slotkey/auth/AuthGoldenTest.kt
backend/src/test/kotlin/com/ovengers/slotkey/member/MemberGoldenTest.kt
backend/src/test/kotlin/com/ovengers/slotkey/inquiry/InquiryGoldenTest.kt
backend/src/test/resources/golden/auth/*.json        (4)
backend/src/test/resources/golden/member/*.json      (10)
backend/src/test/resources/golden/inquiry/*.json     (2)
backend/test-baseline.txt                            (1322 → 1338)
docs/testing/golden-survey/step-5.md
docs/testing/golden-survey/step-5-java-baseline.patch
docs/plans/kotlin-migration/03-api-contract.md       (문의·인증·회원·관리자 회원 행에 골든 연결)
docs/decisions/kotlin-test-guardrails.md             (구현 확인·검증 결과·mockito-kotlin 도입 기록)
```

커밋은 코드(테스트·골든·하한)와 문서로 나눈다.

## 7. 한계·미확인·후속

- **HTTP 본문 전체 골든은 없다.** 이 단계는 DTO 직렬화만 고정한다. 공통 래퍼는 `common-response` 골든, HTTP 상태·쿠키·오류 매핑은 기존 `AuthControllerTest`·`LoginRateLimitIntegrationTest`·`AdminMember*ControllerTest`·`AdminInquiryControllerTest`가 담당한다. 로그인 429 오류 본문과 `Retry-After`, RT 쿠키 속성은 골든으로 고정하지 않았다.
- `SurveyGoldenAssertions`의 실패 메시지는 `"Java a380133 golden: <path>"`로 고정돼 있다. 이 단계의 기준은 `f2273a5`이므로 실패 메시지의 커밋 표기는 출처와 다르다. 공통 지원 코드라 수정하지 않았다.
- 엔티티 생성 API가 다른 응답(예약·거래·감사 로그)의 `from(entity)` 매핑은 골든 대상이 아니다.
- 회원용 문의 HTTP·DB 검증 보강(P0)은 이 단계와 별개이며 아직 미완료다([01 테스트 기준선](../../plans/kotlin-migration/01-test-baseline.md)).

## 8. 관련 문서

- [Java → Kotlin 골든 가이드](../java-kotlin-golden-guide.md): 기준 확보·비교·불일치 처리 절차.
- [골든 4단계](step-4.md): 직전 단계와 재현 방식.
- [API 계약](../../plans/kotlin-migration/03-api-contract.md): 문의·인증·회원·관리자 회원 행에 이 골든을 연결했다.
- [테스트 보호 장치](../../decisions/kotlin-test-guardrails.md): 테스트 수 하한과 골든 파일 결정.

## 9. 리뷰 체크리스트

- [ ] Java 기준이 응답 DTO가 Java인 `f2273a5`이며, 패치가 Java 테스트 1파일 추가뿐인가.
- [ ] 골든 16개가 Java 캡처 원문과 같고 값 편집·정규화가 없는가.
- [ ] Kotlin 테스트의 입력(ID·시각·문자열·금액)이 Java 캡처 테스트와 같은가.
- [ ] `null` 필드(문의 답변 3개, 회원 변경 정보 2개, `totalAmount`, 거래 `reason`, `actorMemberId`)가 누락이 아니라 `null`로 고정됐는가.
- [ ] 신규 16건이 전체 XML에 포함되고 하한 증가분이 16인가.
- [ ] 운영 코드·기존 테스트·기존 골든 변경이 없는가.
