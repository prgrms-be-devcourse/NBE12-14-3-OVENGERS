---
title: 알림 이벤트 확장 (강제 취소·크레딧 지급·문의)
type: 기능
status: 구현 완료 (검증·리뷰 대기)
date: 2026-10-28
tags: [notification, outbox, kafka]
related: [outbox-kafka.md, waitlist.md, notification-ui.md]
---

# 알림 이벤트 확장 (강제 취소·크레딧 지급·문의)

## 한 줄 요약

빈자리 알림 하나뿐이던 Outbox + Kafka 경로에 관리자 강제 취소, 관리자 크레딧 지급, 문의 답변, 문의 등록 알림을 더해, 보내는 쪽이 여러 곳이어도 알림 소비자(컨슈머)는 한 곳에서 받게 한다.

## 1. 문제와 배경

- 현재 Outbox 이벤트는 `slot-released` 하나, 생산 지점은 취소·강제 취소·홀드 만료 3곳, 소비자는 대기자 알림 하나다([outbox-kafka.md](outbox-kafka.md)). 이벤트 종류가 하나라 "Kafka 도입이 과하다"는 반론에 근거가 약하다.
- `notification` 테이블이 대기 전용이다: `waitlist_entry_id BIGINT NOT NULL`, `UNIQUE(event_id, waitlist_entry_id)` (`V20`). `SlotReleasedRecorder`, `SlotReleasedListener/Handler`도 단일 이벤트용이다.
- 돈과 상태가 움직이는 변경(관리자 크레딧 지급 `AdminMemberService` → `CreditGrantService.grantAdminCredit`, 강제 취소 `AdminReservationService.forceCancel`)은 커밋과 알림 기록이 함께 가야 한다. Outbox가 필요한 이유가 가장 분명한 경우다.
- 제약: 3차 전환 완료로 `dev` 머지는 곧 운영 배포다. 마이그레이션은 push 후 수정할 수 없다(번호는 V27). 기존 `slot-released` 토픽·API 응답은 깨지 않는다. 예약·취소 경로의 잠금 순서를 바꾸지 않는다.

## 2. 선택과 이유

- **선택한 방법:**
  1. 새 토픽 `notification-events` 하나에 `eventType`(`RESERVATION_FORCE_CANCELLED`, `CREDIT_GRANTED`, `INQUIRY_ANSWERED`, `INQUIRY_CREATED`)을 넣고, 컨슈머 `slotkey-notifier` 하나가 받는다. 구현 중 payload에 수신자와 문구가 이미 들어 있어 타입별 핸들러는 필요 없다고 판단해 핸들러 하나(`NotificationEventHandler`)로 두었다. 기존 `slot-released`와 대기자 컨슈머는 그대로 둔다.
  2. 생산은 기존과 같이 각 서비스의 성공 전이와 같은 트랜잭션에서 `outbox_event`에 INSERT한다(`Propagation.MANDATORY`). 새 `NotificationEventRecorder`(`Propagation.MANDATORY`)가 같은 방식으로 INSERT한다. `SlotReleasedRecorder`와 그 페이로드·토픽은 건드리지 않는다. 페이로드는 `eventId`, `eventType`, `recipientMemberId`(null이면 관리자 전원), `referenceId`, `message`(생성 시점 문자열 스냅샷), `occurredAt`이고, `partition_key`는 수신자 id 또는 `"ADMIN"`이다.
  3. 회원 대상 이벤트(강제 취소, 크레딧 지급, 문의 답변)는 페이로드의 `recipientMemberId`가 수신자다. `INQUIRY_CREATED`는 컨슈머가 소비 시점에 `ACTIVE`인 관리자 전원을 조회해 한 명당 알림 한 건을 저장한다(fan-out).
  4. `V27`로 `notification`에 `type`, `reference_id`를 추가하고 기존 행을 채운다(`type='WAITLIST_SLOT_RELEASED'`, `reference_id=waitlist_entry_id`). `waitlist_entry_id`는 nullable로 바꾸고 `UNIQUE(event_id, member_id, reference_id)`를 추가한다. 기존 `UNIQUE(event_id, waitlist_entry_id)`는 유지한다. `type`의 기본값은 `WAITLIST_SLOT_RELEASED`다.
  5. `NotificationResponse`에 `type`, `referenceId`를 더한다(응답 필드 추가). `waitlistEntryId`는 유지하되 nullable이 된다.
- **이유:**
  1. 토픽 하나·컨슈머 하나로 두면 새 이벤트를 추가할 때 인프라(토픽·컨슈머 그룹) 변경 없이 핸들러만 늘면 된다.
  2. 중복 방어는 기존과 같이 `processed_event`(컨슈머, event_id)가 첫 번째, `notification`의 유니크가 두 번째다.
  3. 수신자 해석을 소비 시점에 하면 생산 트랜잭션이 회원 조회를 하지 않아 새 잠금 경로가 생기지 않는다.

## 3. 기각한 대안과 이유

| 대안 | 장점 | 기각한 이유 |
| --- | --- | --- |
| 이벤트 종류마다 토픽·컨슈머 분리 | 소비자별 독립 재처리 | 소비자가 알림 하나뿐이라 토픽·그룹만 늘고 얻는 것이 없다 |
| 서비스에서 `NotificationRepository` 직접 저장(Kafka 없이) | 가장 단순 | 알림이 같은 트랜잭션에 묶이지만 "발행과 수신을 분리한다"는 이 프로젝트의 선택과 어긋나고 수신처 추가 시 서비스 수정이 필요 |
| 별도 `admin_notification` 테이블 | 스키마 분리 | 목록·읽음·배지 API와 화면을 전부 이중으로 만들어야 한다 |
| 알림 테이블 재생성 | 깔끔한 스키마 | 운영 데이터가 있고 `V26` 이후 push된 마이그레이션이라 컬럼 추가로 충분하다 |
| 생산 시점에 관리자 전원을 조회해 이벤트에 담기 | 컨슈머가 단순 | 취소·충전 트랜잭션 안에서 회원 테이블을 읽어 잠금 순서·지연 위험이 생긴다 |

## 4. Trade-off

| 얻는 것 | 잃는 것(비용·위험) | 완화책 |
| --- | --- | --- |
| 이벤트 추가가 생산 지점 추가로 끝난다 | 알림 문구가 생산자 쪽에 흩어진다(payload에 `message` 스냅샷) | 컨슈머는 타입을 가리지 않고 수신자 해석과 저장만 한다. 알 수 없는 타입은 경고 로그 후 건너뜀 |
| 돈·상태 변경과 알림 기록의 원자성 | 알림이 비동기라 지연되고 Kafka가 꺼지면 쌓인다 | 기존 relay와 같은 PENDING 재처리, 중복은 멱등 컨슈머가 흡수 |
| 기존 토픽·API 하위 호환 | `waitlistEntryId`가 nullable이 되어 프런트 타입 변경 | 응답은 필드 추가만, 프런트 타입과 골든 테스트를 함께 갱신 |
| 관리자 알림 | 관리자 수만큼 행이 생긴다(fan-out) | 관리자 수가 적은 현재 규모에서 수용 |

## 5. 검증 결과 (구현 후)

구현자(Claude) 자기검증이다. 별도 세션의 검증과 사람 확인, CI가 최종 안전망이다. 단계별 보고는 [docs/testing/notification-events/](../testing/notification-events/)에 있다.

- 전체 백엔드 테스트 1498개 통과(실패 0, 건너뜀 0), `ktlintCheck` 통과. 프런트 Node 테스트 87개, `tsc`, lint(오류 0), 정적 빌드 통과.
- 스키마(`NotificationSchemaIntegrationTest`): 기존 방식 INSERT가 기본 타입으로 들어가고, 같은 이벤트·회원·참조는 거부하며, 같은 이벤트·회원의 다른 대기 항목은 허용한다(기존 동작 유지).
- 기록기(`NotificationEventRecorderIntegrationTest`): 트랜잭션 필수, 롤백 시 outbox 행 없음, 관리자 대상은 수신자 null·키 `ADMIN`.
- 컨슈머(`NotificationEventListenerTest`, `NotificationEventHandlerIntegrationTest`): 잘못된 payload는 재시도 없이 거부, 모르는 타입은 건너뜀, 같은 이벤트 재전달은 알림 1건, 비활성·없는 수신자는 건너뜀, 관리자 fan-out은 ACTIVE 관리자만.
- 생산 지점: 강제 취소·크레딧 지급·문의 답변·문의 등록 모두 커밋 시 outbox 1건, 롤백 시 0건(`AdminReservationServiceIntegrationTest`, `AdminMemberServiceIntegrationTest`, `AdminInquiryNotificationIntegrationTest`). 기록기→relay→리스너 왕복은 `OutboxRelayIntegrationTest`(브로커는 Fake).
- **검증하지 못한 것:** 실제 Kafka 브로커 왕복과 토픽 자동 생성, 운영 DB에 기존 알림 행이 있는 상태의 `V27` 백필, 브라우저에서의 화면 확인.

## 6. 한계와 재검토 조건

- 소비자가 여전히 알림 하나뿐이다. 감사·통계 같은 두 번째 소비자가 실제로 생기기 전까지 "브로커가 꼭 필요한가"는 열린 질문이다(분리와 재처리가 근거).
- 인스턴스 1개·폴링 relay 가정은 그대로다. 다중 인스턴스가 되면 relay 중복 발행을 재검토한다.
- 개별 읽음·알림 종류별 필터·실시간 푸시(WebSocket/SSE)는 이번 범위가 아니다.
- 관리자 수가 크게 늘면 fan-out 행 수를 재검토한다.

## 면접·발표용 정리 (구현 후)

- **30초 설명:** 빈자리 알림 하나뿐이던 Outbox + Kafka 경로에 관리자 강제 취소, 크레딧 지급, 문의 답변, 문의 등록 알림을 더했다. 각 변경과 같은 트랜잭션에서 outbox 테이블에 이벤트를 기록하고, relay가 Kafka 토픽 `notification-events`로 내보내며, 컨슈머 하나가 중복을 걸러 알림을 저장한다. 보내는 쪽이 4곳으로 늘어도 받는 쪽은 한 곳이다.
- **예상 질문과 답:**
  - Q. 왜 이벤트 종류마다 토픽·컨슈머를 나누지 않았나요? A. 소비자가 알림 하나뿐이라 토픽과 그룹만 늘고 얻는 게 없다. 수신자와 문구를 이벤트에 담아 컨슈머는 타입을 가리지 않고 저장만 한다.
  - Q. 소비자가 하나뿐인데 Kafka가 꼭 필요한가요? A. 솔직히 소비자가 늘기 전까지는 약한 근거다. 지금의 근거는 발행과 수신의 분리, 재처리, 그리고 돈·예약 상태 변경과 알림 기록의 원자성(outbox)이다. 감사·통계 같은 두 번째 소비자가 생기면 가치가 커진다.
  - Q. 관리자 전원 알림은 어떻게 하나요? A. 이벤트의 수신자를 null로 두고 컨슈머가 소비 시점의 ACTIVE 관리자를 조회해 한 명당 한 건 저장한다. 변경 트랜잭션에서 회원 테이블을 읽지 않아 새 잠금 경로가 없다.
  - Q. 중복 전달은요? A. 전달은 최소 1회라 중복이 가능하고, `processed_event`(컨슈머, 이벤트 id)가 첫 방어선, `notification`의 유니크가 두 번째 방어선이다.
- **숫자·근거 한 줄:** 전체 테스트 1498개 통과, 이벤트 4종 모두 커밋 시 1건·롤백 시 0건을 통합 테스트로 확인(자기검증, 실제 브로커 왕복은 미검증).
