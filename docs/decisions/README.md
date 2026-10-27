# 의사결정 기록 상태표

기능·기술 선택·정책별 의사결정 기록입니다. 새 문서는 [`TEMPLATE.md`](TEMPLATE.md)의 순서(문제 → 선택과 이유 → 기각한 대안 → Trade-off → 검증 → 한계·재검토 조건)로 쓰고, 면접·발표용 정리를 덧붙입니다. 기존 문서는 문제 → 대안 → 선택과 근거 → 검증 형식이며, 새 형식으로 옮기는 것은 필수가 아닙니다. 범위·순서·일정은 [`plans/`](../plans/)에 두고 이유와 trade-off만 이곳에 씁니다.
상태는 아래 코드 대조 기록을 우선하고, 대조하지 않은 항목은 기존 문서 상태를 유지합니다. "구현 존재"는 코드 확인을 뜻하며 배포·운영 검증 완료를 뜻하지 않습니다.

## 2026-10-21 코드 대조 (기능 동결 기준 `d1a15ec`)

이번 변경은 기능 동결 커밋 `d1a15ec`의 구현을 설명하는 문서 최신화다. 코드와 기능 동결 기준은 변경하지 않는다. 기존 테스트 실행 날짜·커밋·결과는 당시 기록 그대로 유지한다.

아래 표는 기능 동결 커밋에서 확인한 구현이다. 설계 선택·미결정 정책 전체를 재심사한 것은 아니다.

| 항목 | 확인된 사실 | 코드 근거 |
| --- | --- | --- |
| 출입 | 비활성 회원의 출입 검증 요청에 DENY / MEMBER_INACTIVE 기록 구현 | `InactiveMemberAccessLogService`, `CustomAuthenticationFilter` (PR #357) |
| 인증 | DB 세션 검증·RT 회전·로그인 이메일별 제한 구현 | `AuthSessionService`, `RefreshTokenService`, `LoginRateLimitService` (PR #350) |
| PG 충전 | 웹훅 수신·저장·처리 코드 존재. "웹훅 미착수"는 과거 상태 | `TossWebhookController`, `TossWebhookReceiver`, `PgWebhookEventProcessor` |
| Outbox·Kafka | 이벤트 기록·발행 및 슬롯 해제 이벤트 소비 코드 존재 | `SlotReleasedRecorder`, `OutboxRelay`, `SlotReleasedListener`, `SlotReleasedHandler` |
| 예약 대기 | 신청·취소·조회 서비스 구현 | `WaitlistController`, `WaitlistService` |
| 알림 | 목록·미읽음 수·상한 기반 일괄 읽음과 프론트 목록 화면 구현 | `NotificationController`, `NotificationQueryService`, `NotificationsPage` |
| 관리자 회원 상세 | 상세·예약·크레딧·상태 변경 이력 및 프론트 상세 화면 구현 | `AdminMemberController`, `AdminMemberDetailPage` |

이번 확인에서는 운영 PG·Kafka 연결이나 위 기능의 배포 완료를 검증하지 않았습니다. 상세 문서의 과거 "구현 전" 절은 당시 계획으로 읽고, 현재 API 계약은 `api/api-spec.md`를 참조합니다.

## 문서 상태 (기능 동결 기준, 계획 항목은 기존 범위 유지)

| 파일 | 유형 | 한 줄 요약 | 상태 |
|---|---|---|---|
| [`access-key-policy.md`](access-key-policy.md) | 정책 | 출입 토큰은 예약당 활성 1개로 DB 제약으로 수렴시키고, 최초 체크인은 `[start, start+15분]`으로 제한한다. | 채택 |
| [`authentication-policy.md`](authentication-policy.md) | 정책 | 이메일 인증번호·grant 기반 가입/복구, 단계별 차단·정지, 로그인 토큰 생명주기의 V2 적용 정책과 V3 방향을 정리한다. 예약 소유권 등 인가는 `authorization-policy.md`가 담당한다. | V2 구현 반영 / V3 Redis 실험 도입 보류(2026-10-21) / 후속 정책 별도 |
| [`authorization-policy.md`](authorization-policy.md) | 정책 | 인증·인가를 인증·역할·소유권·상태·시간 다섯 축으로 나누고 DB 세션·회원 상태 검증을 적용한다. | 채택 |
| [`member-withdrawal-policy.md`](member-withdrawal-policy.md) | 정책 | 본인 회원탈퇴의 진행 예약·잔여 크레딧·출입 권한 처리와 재가입 기준을 확정한다. | 채택 |
| [`reservation-concurrency.md`](reservation-concurrency.md) | 기술 선택 | `Space → Reservation` 잠금 순서와 조건부 UPDATE로 결제·연장·취소 경합을 막는다. | 채택 |
| [`reservation-idempotency.md`](reservation-idempotency.md) | 기술 선택 | `Idempotency-Key`를 결제 확정 지점에 걸어 크레딧 차감·예약 확정의 중복을 막는다. | 채택 |
| [`pg-charge.md`](pg-charge.md) | 기능 | 토스페이먼츠 테스트 키로 주문서형 SDK와 서버 승인 API를 붙여 크레딧 충전 경로를 만든다. | 채택 (웹훅 A·B 구현 포함, 상세 문서의 별도 검증 대기 기록 유지) |
| [`outbox-kafka.md`](outbox-kafka.md) | 기술 선택 | 도메인 사실을 outbox에 적고 Kafka로 전달해 대기열·알림·감사가 받아 쓰게 한다. 10/21 범위: 폴링 Outbox, `SlotReleased` 1종, 멱등 컨슈머. | 10/21 범위 구현·문서화 반영 |
| [`waitlist.md`](waitlist.md) | 기능 | 예약 대기열을 만든다. 10/21 범위는 빈자리 이벤트 시 대기자 전원 알림(선착순)이고, 1순위 HOLD 제안은 3차다. | 10/21 범위 구현·문서화 반영 |
| [`notification-ui.md`](notification-ui.md) | 기능 | 알림 목록·배지 및 upToId 상한 기반 일괄 읽음을 제공한다. | 구현 반영 (과거 자기검증 기록 유지, 브라우저·CI 재검증 아님) |
| [`hold-cancel-and-account-ui.md`](hold-cancel-and-account-ui.md) | 기능 | HELD 직접 취소, 대기 목록·충전 내역·계정 UI를 정리한다. | 구현 반영 (검증 범위는 상세 기록 참조) |
| [`space-ranking.md`](space-ranking.md) | 기능 | 베스트 공간 랭킹은 끝난 기간만 매일 00:01에 확정값으로 저장하고, 매출은 환불을 환불일에 반영하는 현금주의로 센다. | 구현 완료 (사용자 화면 확인 전, 자기검증) |
| [`kotlin-plugin-version.md`](kotlin-plugin-version.md) | 기술 선택 | Spring Boot 3.3.4 BOM과 맞춰 Kotlin 플러그인·stdlib·reflect를 `1.9.25`로 고정하고, Gradle 8.10.2 호환성 리스크는 `step-0`에서 검증한다. | 채택 (구현 전) |
| [`kotlin-lombok-boundary.md`](kotlin-lombok-boundary.md) | 기술 선택 | Kotlin Lombok 컴파일러 플러그인 없이 전환 파일과 Kotlin 호출 경계만 delombok하고, 미전환 Java 내부는 전환 시점까지 Lombok을 유지한다. | 채택 (구현 전) |
| [`kotlin-jpa-entity-open.md`](kotlin-jpa-entity-open.md) | 기술 선택 | `kotlin-jpa` no-arg와 JPA 애노테이션 `allOpen`으로 엔티티를 열고, `@Entity`/`@MappedSuperclass`에는 `data class`를 쓰지 않는다. | 채택 (구현 전) |
| [`kotlin-data-class-scope.md`](kotlin-data-class-scope.md) | 기술 선택 | `data class`는 DTO·이벤트 페이로드·불변 값 객체에 한정하고, Java record 접근자 경계는 `@JvmRecord` 또는 같은 PR의 호출부 전환으로 맞춘다. | 채택 (구현 전) |
| [`step-up-auth.md`](step-up-auth.md) | 기능 | 크레딧 사용·계정 변경 같은 고위험 행위에 2차 비밀번호 재인증을 요구한다. | 3차 범위 |
| [`mutation-testing.md`](mutation-testing.md) | 기술 선택 | AI가 쓴 테스트가 틀렸는데 통과하거나 누락된 곳을 찾기 위해, 핵심 도메인 로직에 한정해 PIT 뮤테이션 테스트를 도입한다. | 채택 (0~6단계 구현·검증 완료, 2026-10-26) |
| [`doc-code-consistency-checks.md`](doc-code-consistency-checks.md) | 기술 선택 | 문서가 코드와 어긋나는 것을 잡기 위해 엔드포인트·에러 코드·스키마는 스크립트로 CI에서 대조하고, 규칙-테스트 대응·오류 목록·DTO는 범위를 좁혀 근거와 함께 단계별로 대조한다. | 채택 (PR #179~#182 구현 반영, 규칙 4 테스트 인계 대기) |

> Kotlin 마이그레이션 결정 4건은 [Kotlin 마이그레이션 계획](../plans/kotlin-migration/00-kotlin-migration-plan.md)의 P1~P5와 연결되며, 모두 `status: 채택`·"구현 전"이다. 검증 게이트는 각 문서의 5~6번과 [코드 전환 계획](../plans/kotlin-migration/07-code-migration.md)의 `step-0`~`step-6`에 있다.

## 상태 판단 근거

| 파일 | 근거 |
|---|---|
| `access-key-policy.md` | 상태 표기 없음. 2026-09-15 갱신으로 `core-domain-decisions.md` §8 확정 내용을 반영했고, "선택 및 근거"가 확정 서술이며 검증 방법이 통합 테스트로 특정됨. |
| `authentication-policy.md` | PR #350의 DB 세션·RT 회전·이메일별 로그인 제한과 현재 서비스 구현 대조. |
| `authorization-policy.md` | JWT와 DB 세션·회원 상태 검증, 도메인별 역할·소유권·상태·시간 검사 대조. |
| `member-withdrawal-policy.md` | 서두에 "상태: **정책 확정**" 명기 (2026-09-29). |
| `reservation-concurrency.md` | "2026-09-24 최신화 — 현재 백엔드 소스에 전면 구현되어 있다" + 단위·다중 스레드 통합 테스트 클래스명 명시. |
| `reservation-idempotency.md` | 상태 표기 없음. 2026-09-15 갱신 반영 + 실존 테스트(`ReservationPaymentConcurrencyTest`)를 검증 방법으로 제시. |
| `pg-charge.md` | 상세 문서의 6단계 완료·웹훅 A/B 구현 기록과 수신·처리 코드 확인. 운영 재검증은 아님. |
| `outbox-kafka.md` | 상세 문서의 2~5단계 완료 기록과 relay·consumer 코드 확인. Kafka 활성화는 설정에 의존. |
| `waitlist.md` | 신청·취소·조회 및 슬롯 해제 시 알림 처리 구현 확인. |
| `space-ranking.md` | 서두 "status: 구현 완료", "5. 검증 계획" 아래 "검증 결과" 기록(전체 942 tests 통과, 자기검증). 사용자 화면 확인 전. |
| `step-up-auth.md` | 서두 "**3차 프로젝트 범위**다. 2026-10-21 마감 목록에는 넣지 않는다." |

> `access-key-policy.md`, `authorization-policy.md`, `reservation-idempotency.md`는 문서 자체에 상태 표기가 없어
> 본문 근거로 "채택"으로 판단했습니다. 각 문서 서두에 상태 표기를 추가하는 것은 이 PR 범위 밖입니다.
