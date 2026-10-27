# 시스템 아키텍처

> **구현 기준: 기능 동결 커밋 `d1a15ec` / 문서 갱신일: 2026-10-21**. 코드·설정 기준의문서 정정이며, 운영 배포나 외부 연동을 새로 실행 검증한 기록은 아니다.

> 출처: 기획서 5장, `데이터모델-아키텍처-결정.md` 항목 3(오케스트레이션 방향), 항목 4(경미한 결정).
> **2026-09-15 갱신**: `docs/core-domain-decisions.md` 반영 — `payment`/외부 Mock PG 개념 삭제, 크레딧 원장(`credit_transaction`)으로 단일화. Scheduler 책임 확장(HOLD 만료, 노쇼, 자동 퇴실). 결제 실패/취소 보상 처리 설명을 §2/§6-2 기준으로 정정.
> **2026-09-22 갱신**: 배포 아키텍처를 AWS로 확정 — 백엔드는 EC2, DB는 RDS(MySQL), 프론트엔드는 S3 정적 호스팅 + CloudFront. Vercel/Railway는 채택하지 않는다. §자세한 내용은 아래 배포 절 참고.
> **2026-09-24 갱신**: 신규 문의 도메인(Inquiry)을 아키텍처 흐름 및 Mermaid 다이어그램에 추가하고, AWS 배포 구성은 설계 결정으로서 로컬/CI 실측 검증과 명확히 분리하여 기술했다.

## 기술 스택 (5-1)

| 영역 | 기술 | 비고 |
| --- | --- | --- |
| 백엔드 | Spring Boot 3.x, Spring Security 6.x, Spring Data JPA | 단일 모놀리스 애플리케이션 내 도메인별 패키지 분리 |
| DB | MySQL, Testcontainers 통합 테스트 | 유니크 제약·트랜잭션으로 동시성 검증 |
| 프론트엔드 | Next.js(App Router) + TypeScript | 조회·예약·결제 확인·출입 검증·문의 화면. 배포 시 정적 export(`output: 'export'`) 지원 |
| 인프라/배포 | AWS EC2(백엔드) + RDS(MySQL) + S3/CloudFront(프론트엔드 정적 호스팅) | 설계 결정 사항. Railway/Vercel은 검토 후 미채택. 2026-10-28 Kotlin 교체 배포와 수동 기능 확인 기록은 [배포 문서](../operations/deploy-cicd.md) §4-2 |
| CI/CD | GitHub Actions, Swagger UI | 빌드·테스트 자동화, API 문서 |

## 전체 흐름 (5-2)

```
사용자
→ React 화면
→ Spring Boot REST API
→ 인증(AUTHN) 및 요청 자원에 대한 권한 확인(AUTHZ)
→ 도메인별 업무 처리
→ MySQL 조회·저장
→ 공통 응답 형식으로 결과 반환
```

```mermaid
flowchart TD
    USER["회원 / 관리자"] --> FE["React"]
    FE --> API["Spring Boot REST API"]

    API --> AUTHN["AUTHN: 로그인/토큰 발급"]
    API --> AUTHZ["AUTHZ: 요청별 필터 + 인가"]
    AUTHZ --> SPACE["Space"]
    AUTHZ --> RES["Reservation Service (오케스트레이터)"]
    AUTHZ --> ACCESS["Door Access Service"]
    AUTHZ --> INQUIRY["Inquiry Service (회원 문의 / 관리자 답변)"]
    AUTHZ --> ADMIN["Admin"]

    RES --> PRICING["Pricing Service"]
    RES --> CREDIT["Credit Service (예약 크레딧 차감/환급)"]
    AUTHN --> SESSION["DB 로그인 세션 / RT 회전"]
    AUTHZ --> SESSION
    SESSION --> DB
    FE --> CHARGE["PG 크레딧 충전"]
    CHARGE --> PG["토스페이먼츠 승인 / 조회"]
    PG --> WEBHOOK["웹훅 수신 / 저장"]
    WEBHOOK --> RECOVERY["PG 조회 / 충전 확정"]
    RECOVERY --> CREDIT
    CHARGE --> CREDIT
    RES --> OUTBOX["슬롯 해제 Outbox 기록"]
    OUTBOX --> DB
    OUTBOX --> RELAY["폴링 relay"]
    RELAY --> KAFKA["Kafka (활성화 설정 시)"]
    KAFKA --> NOTIFY["멱등 컨슈머 / 빈자리 알림"]
    NOTIFY --> DB
    SCHED["Scheduler"] --> JOB1["HOLD 만료 정리 (HELD → EXPIRED)"]
    SCHED --> JOB2["노쇼 판정 (CONFIRMED → NO_SHOW)"]
    SCHED --> JOB3["자동 퇴실 (IN_USE → COMPLETED)"]
    JOB1 --> RES
    JOB2 --> RES
    JOB3 --> RES

    SPACE --> DB[("MySQL")]
    RES --> DB
    CREDIT --> DB
    ACCESS --> DB
    INQUIRY --> DB
    ADMIN --> DB
```

- **오케스트레이션 방향**: 예약 확정(결제) 트랜잭션에서 화살표는 `RES --> CREDIT`이다. Reservation Service가 슬롯 확보(HOLD) → 결제 확인 페이지 → 크레딧 차감 → 예약 확정을 조율하는 오케스트레이터이며, Credit(구 Payment)이 Reservation을 부르는 구조가 아니다. (2026-09-08 결정, 이전 다이어그램의 `PAY --> RES`는 오기. 2026-09-15: 노드명을 `Payment Service + Mock PG` → `Credit Service`로 변경 — 당시에는 외부 PG 연동이 없었고, `payment` 테이블도 애초에 생성되지 않아(V4 빈 마이그레이션 유지) 크레딧 원장(`credit_transaction`)만 남았으므로 "게이트웨이" 뉘앙스를 걷어냄.)
- **AUTHN / AUTHZ 분리**: 로그인·토큰 발급(AUTHN)과 매 요청의 토큰 검증+도메인별 인가(AUTHZ)는 책임이 다르므로 컴포넌트를 분리한다. 현재 `CustomAuthenticationFilter`는 JWT 확인 후 DB 세션의 소유자·만료·폐기와 회원 ACTIVE 상태를 검증한다. RT는 재발급마다 회전하고 소비된 RT 재사용은 해당 세션을 폐기한다. 상세는 [인증 정책](../decisions/authentication-policy.md)을 따른다.
- **Inquiry Service**: 회원 1:1 문의 등록·조회 및 관리자 답변 작성을 처리하며, `inquiries` 테이블(V10)을 단독 관리한다.
- **예약 Scheduler**: 예약 생명주기의 세 가지 배치를 담당한다 — ① 만료된 `HELD`를 `EXPIRED`로 정리(단, 이는 정합성의 보험일 뿐 유일한 방어선이 아니다. 예약 생성 시점에도 만료 HELD를 즉시 정리하므로 배치가 늦어도 신규 예약은 막히지 않는다) ② 시작+15분까지 미체크인인 `CONFIRMED`를 `NO_SHOW`로 전이(슬롯 반환, 환불 없음) ③ 종료 시각이 지난 `IN_USE`를 자동으로 `COMPLETED` 처리(`checked_out_at` = `end_time`, 배치 실행 시각이 아님). 출입 검증은 어떤 배치 결과도 그대로 믿지 않고 매 요청에서 현재 시각·예약 상태를 다시 확인한다(배치 지연 리스크 대응).
- **확장 경로**: PG 충전은 DB 1(준비) → 트랜잭션 밖 승인 → DB 2(확정)로 분리하고, `ChargeOrderRecoveryScheduler`가 결과 미확정 주문을 조회 복구한다. `PgWebhookEventScheduler`는 저장된 웹훅을 처리하며 PG 조회로 검증한다. 예약 취소의 크레딧 환급은 이 PG 경로를 호출하지 않는다.
- **예약 대기·알림**: 슬롯 해제와 같은 트랜잭션의 Outbox → relay → Kafka → 멱등 컨슈머 → 알림 저장 흐름이다. `slotkey.kafka.enabled` 기본값은 `false`이며 비활성 환경에서는 Outbox를 기록해도 전달·알림 생성은 수행하지 않는다. 대기자는 선착순으로 예약하며 자동 승격은 없다. 공간 랭킹은 `SpaceRankingScheduler`가 확정 스냅숏을 기록한다.
- 도메인은 하나의 Spring Boot 애플리케이션 안에서 패키지 단위로 분리하며, 별도의 결제 게이트웨이 서버를 두지 않는다(크레딧 차감은 애플리케이션 내부 로직).

## 학습 범위 밖 기술과 검증 계획 (5-3)

1. 역할·소유권·상태·시간을 조합한 인가 → `docs/decisions/authorization-policy.md` 참고
2. 동시 예약 제어 (MySQL 유니크 제약 + 트랜잭션, 분산 락 미도입) → `docs/decisions/reservation-concurrency.md` 참고
3. 조건부 상태 변경 (UPDATE 조건 + 영향받은 행 수로 성공 판단, 예: 종료된 `IN_USE`만 `COMPLETED`로, 만료된 `HELD`만 `EXPIRED`로 전이)
4. **예약 크레딧 차감/환급의 원자성**: 예약과 크레딧은 같은 DB·같은 트랜잭션에서 처리한다. PG 충전의 외부 호출·웹훅·조회 복구는 별도 경로다. [핵심 정책 §6-2](../core-domain-decisions.md#6-2-취소-처리-순서)와 [PG 충전 결정](../decisions/pg-charge.md)을 참고한다.
5. 시간 경계 테스트 (`Clock`을 주입받아 체크인 시작·마감, HOLD 만료, 예약 종료의 직전/정확한 경계/직후를 고정 시각으로 테스트)


## 배포 아키텍처와 정적 export를 택한 이유 (2026-09-22)

- **배포 방식**: 기능 동결 기준 백엔드 워크플로는 빌드 JAR를 `/home/ubuntu/slotkey/slotkey.jar`로 설치하고 `slotkey` systemd 서비스를 재시작한다. 서비스 활성 여부와 재시작 이후 기동 완료 로그를 확인하며, HTTP 기능·외부 연동 검증은 별도다. 상세는 [배포·CI/CD](../operations/deploy-cicd.md)를 따른다.
- **구성**: 백엔드(Spring Boot)는 EC2에서 실행, DB는 RDS(MySQL). 프론트엔드는 `STATIC_EXPORT=true npm run build`로 만든 정적 파일(`out/`)을 S3에 올리고 CloudFront로 서빙한다.
- **정적 export를 택한 이유**: Next.js를 자체 서버(Node 런타임)로 띄우려면 별도 컴퓨팅 자원과 운영 비용이 든다. 이 프로젝트의 화면은 서버 컴포넌트나 SSR이 반드시 필요한 요구사항이 없으므로, 빌드 시점에 정적 HTML로 미리 만들어 S3 + CloudFront로 서빙하면 프론트엔드용 서버를 따로 운영하지 않아도 되고 CDN 캐싱으로 응답도 빨라진다.
- **트레이드오프 — 동적 라우트 문제**: 정적 export는 `/spaces/123`처럼 파라미터가 실제 값인 URL을 파일로 미리 만들 수 없다(가능한 모든 ID를 빌드 시점에 알 수 없음). 이를 해결하기 위해:
  - 빌드 시 `/spaces/[spaceId]` 같은 동적 라우트는 플레이스홀더 파일(`/spaces/_.html`)로 만든다.
  - CloudFront Function(viewer-request)이 `/spaces/123` 같은 실제 요청 URL을 `/spaces/_.html`로 리라이트해서 그 플레이스홀더 파일을 돌려준다.
  - 화면에서는 `useRouteId` 훅이 브라우저 주소(`window.location`)에서 실제 ID(`123`)를 다시 읽어와 API 호출에 사용한다 (`frontend/src/hooks/useRouteId.ts`에 구현).
- **API 호출 경로도 배포 대상에 따라 다르다**: 정적 export는 Next.js의 서버 사이드 `rewrites()`를 쓸 수 없으므로(정적 파일에는 서버가 없다), API 요청은 CloudFront의 `/api/*` 동작(behavior)이 직접 EC2 오리진으로 넘긴다. 반면 로컬 개발이나 Vercel처럼 Next.js 서버가 살아있는 환경에서는 기존처럼 `next.config.ts`의 `rewrites()`가 프록시 역할을 한다. 즉 `frontend/next.config.ts`는 `STATIC_EXPORT` 환경변수로 두 배포 방식을 모두 지원하도록 만들어져 있다.
- **설계 결정과 실제 배포 검증의 분리**:
  - 위 AWS 배포 구성(EC2, RDS, S3 + CloudFront, CloudFront Function URL 리라이트)은 프로젝트의 **인프라 설계 결정**이다.
  - 소스 코드 레벨에서 Next.js 빌드 설정(`frontend/next.config.ts`) 및 클라이언트 훅(`useRouteId.ts`)은 정적 export와 rewrites 분기를 모두 지원하도록 구현되어 있다.
  - 그러나 실제 AWS 클라우드 환경에 인스턴스를 프로비저닝하고 CloudFront Function 및 도메인 라우팅이 정상 동작하는지 확인한 엔드투엔드 배포 실측 결과는 현재 저장소 테스트 범위에 포함되지 않는다(미검증 상태로 유지). *(2026-10-28 갱신: EC2 교체 배포와 CloudFront 주소에서의 수동 기능 확인은 [배포 문서](../operations/deploy-cicd.md) §4-2에 기록했다. 자동화된 엔드투엔드 검증은 아니다.)*
