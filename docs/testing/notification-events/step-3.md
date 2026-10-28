# notification-events step 3: 컨슈머와 강제 취소 알림

- 브랜치: `feat/notification-events` (로컬, 미커밋·미push)
- 결정 문서: `docs/decisions/notification-events.md`
- 선행: [step-2](step-2.md)

## ① 변경 파일

- 신규(main): `NotificationEventListener.kt`(토픽 `notification-events`, 그룹 `slotkey-notifier`), `NotificationEventHandler.kt`(processed_event 선점 + 알림 저장, 수신자 null이면 ACTIVE 관리자 전원)
- 수정(main): `AdminReservationService.kt`(강제 취소 성공 시 `RESERVATION_FORCE_CANCELLED` 이벤트 기록, 문구 생성), `MemberRepository.kt`(`findAllByRoleAndStatus`)
- 신규 테스트: `NotificationEventListenerTest`, `NotificationEventHandlerIntegrationTest`
- 수정 테스트: `AdminReservationConcurrencyIntegrationTest`(outbox 단정), `AdminReservationServiceTest`(생성자 인자, 호출·미호출 검증), `AdminReservationServiceIntegrationTest`(커밋 시 outbox 행·내용, 롤백 시 없음), `OutboxRelayIntegrationTest`(기록기→relay→리스너 왕복), `SlotReleasedListenerContractTest`, `KafkaConsumerDisabledTest`

## ② 실행한 명령과 결과

- 테스트 먼저 작성 → `compileTestKotlin` 실패(클래스 없음) 확인 → 구현
- 대상 테스트(`*NotificationEvent*`, `*AdminReservationService*`, `*notification*`, `*outbox*`, `*OutboxRelay*`, `*Kafka*`, `*ListenerContract*`) 통과, `ktlintCheck` 통과
- 구현 중 실패 2건은 테스트 쪽 문제였고 고쳤다: MySQL JSON 컬럼은 payload를 `"key": value`(공백 포함)로 정규화하므로 문자열 `contains` 대신 JSON 파싱으로 바꿨다. Mockito `anyNonNull()`은 `Long` 원시 인자에 NPE라 `anyLong()`으로 바꿨다.
- 전체 테스트(`./gradlew test`, 10분 33초): 1493개 중 1개 실패. 제 변경의 예상된 영향이라 수정했다.
  - `AdminReservationConcurrencyIntegrationTest`(강제 취소 커밋 케이스): outbox 총 개수를 1건으로 단정하고 있었는데, 이제 강제 취소 한 번에 `slot-released` 1건과 `notification-events` 1건이 생긴다. 토픽 구성(`containsExactlyInAnyOrder`)으로 검증하도록 바꿨다.
  - 수정 후 해당 클래스(13개)만 재실행해 통과, `ktlintCheck` 통과. **전체 재실행은 하지 않았다**(검증 세션에서 전체 실행 필요).
  - 롤백 케이스의 `count() == 0` 단정은 그대로 통과한다(롤백 시 두 이벤트 모두 없음).

## 추가한 테스트가 검증하는 내용

- 리스너: 정상 payload 전달, `recipientMemberId: null`은 관리자 전원 대상, 모르는 필드 무시, 모르는 타입·`WAITLIST_SLOT_RELEASED`는 예외 없이 건너뜀, 잘못된 payload 11종은 `IllegalArgumentException`(재시도 안 함)
- 핸들러: ACTIVE 회원에게 저장(type, reference_id, waitlist_entry_id NULL), 같은 eventId 재전달 시 1건, SUSPENDED·없는 회원은 건너뜀, 관리자 fan-out은 ACTIVE 관리자만
- 강제 취소: 커밋 시 예약자 앞 PENDING outbox 1건(타입, 키, recipient, reference, 공간명·사유 포함), 감사 로그 실패로 롤백되면 outbox 행 없음, 종료 상태 예약은 기록 안 함
- 파이프라인: 기록기 → relay → 리스너 왕복(브로커는 Fake), 같은 payload 2회 전달해도 알림 1건

## ③ 가정과 미확인

- **실제 Kafka 브로커 왕복은 테스트하지 않았다.** CI 규칙(Fake만 사용)에 따라 relay→리스너는 Fake 발행 payload를 직접 전달했다. 토픽 `notification-events`가 브로커에 자동 생성되는지(auto-create)는 로컬 compose·운영 설정을 확인하지 못했다. 운영 배포 전 확인 필요.
- 기존 `slot-released` 컨슈머와 별개의 컨슈머 그룹이 하나 더 생긴다(운영 Kafka 힙 512MB 환경에서의 영향은 미측정).
- 알림 문구는 생성 시점 스냅샷이다. 강제 취소 사유가 200자를 넘으면 잘린다(`notification.message`는 500자).
- 관리자 알림 노출(프런트)은 아직 없다.

## 리뷰가 필요한 설계 판단

- 타입별 핸들러 분리를 접고 핸들러 하나로 두었다(수신자·문구가 payload에 있어 분기할 것이 없다). 결정 문서에 반영했다.
- `recipientMemberId` 키가 없는 payload는 거부한다. 키가 있고 null일 때만 관리자 전원 대상이다(필드 누락이 관리자 전원 발송으로 번지는 것을 막기 위함).
- 수신자가 비활성이거나 없으면 예외 없이 건너뛴다(재시도 루프 방지, 대기자 알림 핸들러와 같은 정책).
- 강제 취소 이벤트는 `slotReleasedRecorder.record` 바로 뒤에 기록한다. 같은 트랜잭션 안의 쓰기 하나가 늘 뿐 새 잠금 경로는 없다.

## 리뷰 체크리스트

- 토픽 `notification-events`, 그룹 `slotkey-notifier`, processed_event 컨슈머명 `notification-notifier`
- `slotkey.kafka.enabled=false`면 리스너 빈이 없다(`KafkaConsumerDisabledTest`)
- 기존 `slot-released` 리스너·핸들러·기록기 코드 변경 없음
- 강제 취소 알림은 예약자(`reservation.memberId`)가 수신자, 관리자는 아님
- 다음 단계: 크레딧 지급·문의 답변 생산 지점(4단계), 문의 등록 fan-out과 프런트(5단계)
