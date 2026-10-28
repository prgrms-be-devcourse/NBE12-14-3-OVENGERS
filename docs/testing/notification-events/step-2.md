# notification-events step 2: V27 스키마와 알림 이벤트 기록기

- 브랜치: `feat/notification-events` (로컬, 미커밋·미push)
- 결정 문서: `docs/decisions/notification-events.md` (질문 1~3 모두 승인: 토픽 하나+타입 분기, 관리자 전원 fan-out, 관리자 벨 노출)

## ① 변경 파일

- 신규: `V27__generalize_notification.sql`, `NotificationType.kt`, `NotificationEventRecorder.kt`
- 수정(main): `Notification.kt`(type, referenceId, nullable waitlistEntryId, `of` 팩토리), `NotificationResponse.kt`(`type`, `referenceId` 추가, `waitlistEntryId` nullable), `OutboxEvent.kt`(보조 생성자)
- 신규 테스트: `NotificationSchemaIntegrationTest`(6), `NotificationEventRecorderIntegrationTest`(5)
- 수정 테스트: raw INSERT 4곳에 `reference_id` 추가(`NotificationQueryIntegrationTest`, `NotificationReadIntegrationTest`, `SlotReleasedHandlerTest` 2곳), `NotificationResponse` 생성자 호출 2곳, 골든 JSON 2개(`read-true-response.json`, `list-nonfirst-page-ok.json`)

## ② 실행한 명령과 결과

- 테스트 먼저 작성 → `compileTestKotlin` 실패(클래스 없음) 확인 → 구현
- `./gradlew test --tests '*notification*' --tests '*outbox*'`: 통과. 신규 11개 모두 통과(6 + 5, 실패 0).
- 전체 테스트(`./gradlew test`, 10분 33초): 1471개 중 2개 실패. 둘 다 V27·응답 필드 추가의 예상된 영향이라 수정했다.
  - `AuthSessionMigrationIntegrationTest.allMigrationsApplyToAnEmptyDatabase`: 최종 버전 하드코딩 `"26"` → `"27"`
  - `FridayRemainingDtoGoldenTest`(notification 케이스): 기대 JSON에 `type`, `referenceId` 추가
  - 수정 후 위 두 테스트만 재실행해 통과, `ktlintFormat` 후 `ktlintCheck` 통과. **전체 재실행은 하지 않았다**(검증 세션에서 전체 실행 필요).

## 추가한 테스트가 검증하는 내용

- 스키마: 기존 방식 INSERT는 type 기본값 `WAITLIST_SLOT_RELEASED`, `waitlist_entry_id` NULL 허용, `(event_id, member_id, reference_id)` 중복 거부, 같은 이벤트·같은 회원·다른 reference는 허용(기존 대기 알림 동작 유지)
- 엔티티: 기존 생성자는 `referenceId = waitlistEntryId`, `of()`는 `waitlistEntryId = null`로 저장·재조회
- 기록기: 트랜잭션 없으면 예외, PENDING outbox 행(topic, event_type, partition_key, payload 필드), 관리자 대상은 `recipientMemberId=null`·키 `ADMIN`, 롤백 시 행 없음, `WAITLIST_SLOT_RELEASED` 거부

## ③ 가정과 미확인

- **운영 데이터 백필은 자동 테스트하지 않았다.** `V27`의 `UPDATE ... reference_id = waitlist_entry_id`는 Flyway가 빈 DB에 순차 적용되는 테스트 환경에서는 대상 행이 없다. 운영 DB 적용 전 복사본이나 로컬에서 기존 행이 있는 상태로 한 번 확인해야 한다.
- 새 유니크 `(event_id, member_id, reference_id)`가 운영의 기존 행과 충돌하지 않는지는 위와 같은 이유로 미확인이다(기존 `UNIQUE(event_id, waitlist_entry_id)` 아래에서 `reference_id = waitlist_entry_id`이므로 충돌 가능성은 낮다고 추정).
- `waitlistEntryId`가 nullable이 되어 프런트 타입(`types/api.ts`)은 아직 안 바꿨다(프런트 단계에서 처리).

## 리뷰가 필요한 설계 판단

- 기존 `SlotReleasedRecorder`를 일반화하지 않고 `NotificationEventRecorder`를 새로 만들었다(기존 동작 무변경이 우선).
- `type`에 DB 기본값을 둬서 기존 raw INSERT·구 코드 경로가 깨지지 않게 했다. `reference_id`는 NOT NULL이라 raw INSERT는 값을 줘야 한다.

## 리뷰 체크리스트

- `V27`은 push 전이라 수정 가능, push 후에는 새 파일만 가능
- `NotificationResponse` 변경은 필드 추가와 `waitlistEntryId` nullable뿐이다
- `slot-released` 토픽·`SlotReleasedRecorder`·대기자 컨슈머 코드 변경 없음
- Kotlin 파일에 Lombok·엔티티 `data class` 사용 없음(`Payload`는 엔티티가 아닌 내부 `data class`로 기존 recorder와 동일)
- 다음 단계: 컨슈머(`slotkey-notifier`) 및 이벤트 생산 지점
