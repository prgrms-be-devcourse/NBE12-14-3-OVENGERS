# notification-events step 1: 결정 문서

- 브랜치: `feat/notification-events` (로컬, 미커밋·미push)
- ① 변경 파일: `docs/decisions/notification-events.md` (신규)
- ② 실행한 명령: 코드·문서 읽기만 했다. 테스트 실행 없음.
- ③ 가정 / 미확인:
  - `CreditGrantService.grantAdminCredit`의 호출자는 `AdminMemberService.kt:233` 하나로 확인했다.
  - 프런트 헤더에서 관리자에게 알림 벨이 보이는지는 확인하지 못했다(`Header.tsx`에 `!isAdmin` 분기가 많음). 구현 단계에서 확인한다.
  - `UNIQUE(event_id, member_id, reference_id)`가 기존 대기 알림(같은 이벤트·같은 회원·다른 대기 항목)과 충돌하지 않는지는 마이그레이션 테스트로 확인해야 한다.
- ④ 관련 문서: `docs/decisions/outbox-kafka.md`, `waitlist.md`, `notification-ui.md`

## 리뷰 체크리스트

- 새 토픽 `notification-events` 하나, 컨슈머 `slotkey-notifier` 하나
- 기존 `slot-released` 토픽·대기자 컨슈머·SlotReleased 페이로드는 변경 없음
- 수신자: 회원 대상 3종은 페이로드 `memberId`, `INQUIRY_CREATED`는 컨슈머가 ACTIVE 관리자 전원 조회
- `V27`: `type`, `reference_id` 추가, `waitlist_entry_id` nullable, 새 유니크 추가
- `NotificationResponse`는 필드 추가만, `waitlistEntryId`는 nullable

## 사용자 결정이 필요한 질문

1. 토픽 하나에 타입 분기(제안) vs 이벤트별 토픽 분리
2. `INQUIRY_CREATED` 수신자를 ACTIVE 관리자 전원으로 하는 것
3. 관리자 화면에 알림 벨을 노출할지(현재 헤더는 일반 회원 중심)
