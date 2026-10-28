# notification-events step 5: 문의 등록 알림(관리자 fan-out)과 프런트

- 브랜치: `feat/notification-events` (로컬, 미커밋·미push)
- 결정 문서: `docs/decisions/notification-events.md`
- 선행: [step-4](step-4.md)

## ① 변경 파일

- 백엔드 main: `InquiryService.kt`(`create` 성공 시 `INQUIRY_CREATED` 기록, 수신자 null = ACTIVE 관리자 전원)
- 백엔드 테스트: `AdminInquiryNotificationIntegrationTest`(5개로 확장: 등록 시 관리자 대상 이벤트, 수정은 이벤트 없음 추가 / 기존 2개는 이벤트 타입으로 조회 조건 수정)
- 프런트 신규: `src/utils/notificationTarget.ts`(타입별 이동 경로·버튼 문구)
- 프런트 수정: `src/types/api.ts`(`type`, `referenceId`, `waitlistEntryId: number | null`), `src/views/notifications/NotificationsPage.tsx`(타입별 링크, 안내 문구), `tests/notification-ui.test.cjs`(테스트 3개 추가)
- 문서: `docs/api/api-spec.md` §13(알림 타입 표, 응답 필드), `docs/architecture/erd.md`(`notification` V27 반영)

## ② 실행한 명령과 결과

- 테스트 먼저 작성 → 실패 확인(백엔드: 등록 이벤트 없음, 프런트: `notificationTarget.ts` 없음·모든 알림이 빈자리 링크) → 구현
- 백엔드: `AdminInquiryNotificationIntegrationTest` 5개 통과, `ktlintCheck` 통과. 구현 직후 기존 2개 테스트가 실패했으나(문의 등록 이벤트가 함께 생겨 단건 조회가 두 행을 만남) 코드 문제가 아니라 조회 조건 문제라 이벤트 타입으로 좁혀 고쳤다.
- 프런트: `node --test tests/*.cjs` 87개 통과(신규 3개 포함), `npx tsc --noEmit` 오류 없음, `npm run lint` 오류 0(경고 7건은 변경 전과 같은 기존 경고), `STATIC_EXPORT=true npm run build` 성공
- 전체 백엔드 테스트(`./gradlew ktlintCheck test`, 10분 4초): 1498개 실행, 실패 0, 건너뜀 0, `ktlintCheck` 통과. 1~5단계 변경이 모두 들어간 상태의 결과다.

## 추가한 테스트가 검증하는 내용

- 문의 등록: 관리자 대상(`recipientMemberId=null`, 키 `ADMIN`) outbox 이벤트 1건과 제목 포함 문구, 문의 수정은 새 이벤트를 만들지 않음
- 관리자 fan-out 자체(ACTIVE 관리자에게만 저장)는 step-3의 `NotificationEventHandlerIntegrationTest`에서 검증
- 프런트: 타입 5종의 이동 경로·문구, 모르는 타입·타입 없음은 `null`(링크 없음), 목록이 `waitlistEntryId: null`인 알림을 타입별 링크로 렌더

## ③ 가정과 미확인

- **브라우저에서 화면을 직접 확인하지 못했다.** 정적 렌더링 테스트, 타입 검사, 빌드까지만 확인했다. 실제 화면 확인이 필요하다.
- **관리자에게 알림 벨이 이미 보인다.** `Header.tsx`가 `NotificationLink`를 관리자 여부와 무관하게 렌더하고 `/notifications` 라우트는 `RequireAuth`만 요구한다. 백엔드 `NotificationController`도 역할 제한이 없다(`AuthPrincipal`만 사용). 그래서 벨 노출을 위한 코드 변경은 하지 않았다. 다만 관리자 계정으로 실제 접근해 본 것은 아니다.
- 알림 이동 경로 중 `/reservations/{id}`는 예약자 화면이다. 강제 취소 알림의 수신자가 예약자라서 맞지만, 취소된 예약의 상세 화면이 정상 열리는지는 확인하지 못했다.
- 관리자 문의 알림은 `/admin/inquiries/detail?id=`로 이동한다. 관리자가 아닌 사용자에게 도달하는 경로는 없다(수신자가 관리자뿐).
- 실제 Kafka 왕복·토픽 자동 생성은 step-3의 미확인 항목과 같다.

## 리뷰가 필요한 설계 판단

- 문의 등록 이벤트는 `InquiryService.create`의 저장 직후 같은 트랜잭션에서 기록한다(수정·답변과 구분).
- 프런트는 모르는 타입에 링크를 그리지 않는다(서버가 새 타입을 먼저 배포해도 깨지지 않게).
- **마이그레이션 번호 `V27` 충돌 가능성**: `docs/architecture/erd.md`에 V3 Redis 실험 브랜치에 별도의 V27(`auth_session_revocation_retry`)이 있다고 적혀 있다. 그 브랜치는 운영 도입을 보류한 상태라 dev에는 없지만, 되살리면 번호가 겹친다(AGENTS.md: 번호가 겹치면 멈추고 질문). erd 상단에 주석을 남겼다.

## 리뷰 체크리스트

- 문의 등록 알림 수신자는 ACTIVE 관리자 전원, 작성자 본인은 아님
- `notificationTarget`의 타입→경로 매핑이 api-spec §13 표와 일치
- `types/api.ts`의 `waitlistEntryId`가 nullable로 바뀌어 이 필드를 쓰는 다른 코드가 없는지(검색 결과 `NotificationsPage`와 테스트만 사용)
- api-spec·erd 갱신 반영
