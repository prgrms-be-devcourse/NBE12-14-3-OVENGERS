-- 일시: 2026-10-28
-- 기능: 알림 이벤트 확장(강제 취소·크레딧 지급·문의)을 위한 notification 일반화
-- 참고: docs/decisions/notification-events.md
--
-- 기존 행은 모두 대기자 알림이므로 type 기본값이 WAITLIST_SLOT_RELEASED이고 reference_id는 waitlist_entry_id를 복사한다.
-- 새 유형은 waitlist_entry_id 없이 reference_id(예약·문의 id 등)만 쓴다.
-- UNIQUE(event_id, member_id, reference_id)는 새 유형의 중복 알림을 막는 두 번째 방어선이다(첫 번째는 processed_event).
-- 기존 UNIQUE(event_id, waitlist_entry_id)는 그대로 둔다.

ALTER TABLE notification
    ADD COLUMN type VARCHAR(40) NOT NULL DEFAULT 'WAITLIST_SLOT_RELEASED' AFTER member_id,
    ADD COLUMN reference_id BIGINT NULL AFTER waitlist_entry_id,
    MODIFY COLUMN waitlist_entry_id BIGINT NULL;

UPDATE notification SET reference_id = waitlist_entry_id WHERE reference_id IS NULL;

ALTER TABLE notification
    MODIFY COLUMN reference_id BIGINT NOT NULL,
    ADD CONSTRAINT uk_notification_event_member_reference UNIQUE (event_id, member_id, reference_id);
