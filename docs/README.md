# Slot Key 문서 색인

> 구현 기준: 기능 동결 커밋 `d1a15ec` / 문서 갱신일: 2026-10-22. 현재 API 계약과 과거 설계·실행 기록을 구분하며, 운영 배포·외부 연동의 새 검증을 뜻하지 않습니다.

목적별로 묶은 문서 지도입니다. 설계 판단이 서로 어긋날 때는
[`core-domain-decisions.md`](core-domain-decisions.md)를 기준으로 삼습니다.

| 폴더 | 무엇이 있나 | 대표 문서 |
|---|---|---|
| [`product/`](product/) | 기획·요구사항·약관·UI 결정 | [요구사항 정의](product/requirements.md) · [UI 의사결정](product/UI_DESIGN_DECISIONS.md) · [예약 약관 문안 검토](product/booking-terms-review.md) |
| [`architecture/`](architecture/) | 시스템 구조와 데이터 모델 | [시스템 아키텍처](architecture/system-architecture.md) · [ERD](architecture/erd.md) |
| [`api/`](api/) | API 명세와 연동 계약 | [REST API 명세](api/api-spec.md) · [프론트 결제 API 계약](api/FRONTEND2_PAYMENT_API.md) · [관리자 공간 대표 사진](api/admin-space-image-upload.md) |
| [`decisions/`](decisions/) | 기능별 의사결정 기록 | [결정 상태표](decisions/README.md) |
| [`operations/`](operations/) | 배포와 장애 대응 | [배포·CI/CD](operations/deploy-cicd.md) · [트러블슈팅](operations/troubleshooting.md) · [Gmail SMTP 설정](operations/gmail-smtp-setup.md) |
| [`testing/`](testing/) | 테스트 전략과 실행 결과 | [테스트 전략](testing/test-strategy.md), [테스트 결과](testing/test-results.md), [뮤테이션 테스트 결정](decisions/mutation-testing.md), [V3 인증 실험 결과](testing/auth-v3-experiment-results.md) |
| [`plans/`](plans/) | 진행 예정 계획 시리즈 | [Kotlin 마이그레이션 문서 안내](plans/kotlin-migration/README.md) · [인증 고도화](plans/auth/README.md) · [뮤테이션 테스트 계획](plans/mutation-testing/00-mutation-testing-plan.md) |

## 루트에 두는 문서

- [`core-domain-decisions.md`](core-domain-decisions.md) — 예약·체크인 핵심 비즈니스 로직 확정본.
  다른 문서와 충돌하면 이 문서가 우선합니다. `AGENTS.md`와 백엔드·프론트엔드 소스 주석이
  이 경로를 직접 참조하므로 루트에 둡니다.

## 계획 시리즈

진행 예정 작업은 [`plans/`](plans/) 아래에 시리즈로 둡니다.

- **Kotlin 마이그레이션** — [문서 안내](plans/kotlin-migration/README.md)에서 시작합니다.
  현재 3차 저장소에서 진행할 [목~화 요일별 계획](plans/kotlin-migration/daily/00-overview.md), [팀원별 상세 담당](plans/kotlin-migration/team-guide.md), 기존 기술 계획 00~07을 함께 제공합니다.
- **인증 고도화 (V1 → V2 DB → V3 Redis)** — 시작점: [인증 문서 지도](plans/auth/README.md), [로드맵](plans/auth/roadmap.md), [V2 계획](plans/auth/v2-plan.md), [V3 Redis 설계와 실험 경과](plans/auth/v3-redis-design.md), [V3 인증 실험 결과](testing/auth-v3-experiment-results.md)(2026-10-21 V2 유지, V3 도입 보류).
  정책은 [인증 정책](decisions/authentication-policy.md), V1 기준점은 [V1 구현 해설](architecture/auth-v1-implementation.md), V2 구현 이력은 [V2 구현 해설](architecture/auth-v2-implementation.md), 보안 테스트는 [인증 보안 테스트](testing/auth-security-tests.md).
  초기 Redis 검토 문서는 [보관 자료](plans/auth/archive/redis-initial-plan/01-redis-adoption-scope.md)로만 남깁니다(현재 계획 아님).
- **예약 대기열 + Outbox + Kafka (구현 반영, 자동 승격은 후속 범위)** — [구현 계획](plans/waitlist-outbox/00-waitlist-outbox-plan.md) · [대기 신청 규칙](plans/waitlist-outbox/01-waitlist-rules.md) · [Outbox·Kafka 설계](plans/waitlist-outbox/02-outbox-kafka-design.md)
