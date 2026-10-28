# notification-events step 4: 크레딧 지급·문의 답변 알림

- 브랜치: `feat/notification-events` (로컬, 미커밋·미push)
- 결정 문서: `docs/decisions/notification-events.md`
- 선행: [step-3](step-3.md)

## ① 변경 파일

- main: `AdminMemberService.kt`(`grantCredit` 성공 시 `CREDIT_GRANTED` 기록, 문구 생성), `AdminInquiryService.kt`(`answer` 성공 시 `INQUIRY_ANSWERED` 기록)
- 신규 테스트: `AdminInquiryNotificationIntegrationTest`(3)
- 수정 테스트: `AdminMemberServiceTest`(생성자 인자, 호출·미호출 검증), `AdminMemberDetailServiceTest`(생성자 인자), `AdminMemberServiceIntegrationTest`(커밋 시 outbox 행·내용, 롤백 시 없음)

## ② 실행한 명령과 결과

- 테스트 먼저 작성 → `compileTestKotlin` 실패(생성자 인자 불일치) 확인 → 구현
- 대상 테스트(`*AdminMemberService*`, `*AdminInquiry*`, `*inquiry*`, `*AdminMemberController*`) 통과, `ktlintCheck` 통과
- 전체 테스트(`./gradlew test`, 11분 8초): 1496개 실행, 실패 0, 건너뜀 0. 1~4단계 변경이 모두 들어간 상태의 첫 전체 통과다.

## 추가한 테스트가 검증하는 내용

- 크레딧 지급: 커밋 시 지급받은 회원 앞 PENDING outbox 1건(금액 `50,000원`·사유가 문구에 포함), 감사 로그 실패로 롤백되면 outbox 행 없음, 자기 자신에게 지급(예외) 시 기록기 호출 없음
- 문의 답변: 문의 작성자 앞 outbox 1건(`referenceId` = 문의 id, 제목 포함 문구), 없는 문의에 답변하면 예외와 함께 기록 없음, 200자 제목은 50자로 잘려 문구가 120자 미만

## ③ 가정과 미확인

- **`CREDIT_GRANTED`의 `referenceId`는 회원 id다.** `grantAdminCredit`이 거래 id를 돌려주지 않아서다(잔액만 반환). 알림에서 거래 내역으로 연결할 일이 생기면 반환값 변경이 필요하다.
- 크레딧 지급 알림은 **관리자 수동 지급(`grantCredit`)만** 대상이다. 가입 지급(`SIGNUP_GRANT`)과 PG 충전 확정은 제외했다(요청 범위가 "관리자가 크레딧 충전해주는 것"이었다).
- 같은 문의를 관리자가 다시 답변하면(엔티티가 재답변을 막지 않는다) 알림이 다시 간다. 현재 동작을 바꾸지 않았다.
- 실제 Kafka 왕복과 토픽 자동 생성 여부는 step-3의 미확인 항목과 같다.

## 리뷰가 필요한 설계 판단

- 지급 알림은 감사 로그 저장 뒤, 같은 트랜잭션 끝에 기록한다. 이미 `findMemberForUpdate`로 회원을 잠근 뒤라서 새 잠금 경로는 없다.
- 문의 답변 알림은 `answer()`의 `also` 블록 안에서 기록해 답변 저장과 같은 트랜잭션에 묶인다.

## 리뷰 체크리스트

- 지급 알림 수신자는 지급받은 회원(관리자 아님), 문의 답변 수신자는 문의 작성자
- 롤백 시 outbox 행이 남지 않는다(지급 테스트로 확인)
- `AdminMemberService`·`AdminInquiryService`에 `NotificationEventRecorder` 의존이 추가됨(생성자 변경, 테스트 반영)
- 다음 단계: 문의 등록(`INQUIRY_CREATED`) → 관리자 fan-out, 프런트(관리자 벨, 알림 타입 표시, `waitlistEntryId` nullable)
