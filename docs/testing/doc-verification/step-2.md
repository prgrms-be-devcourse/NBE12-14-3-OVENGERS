# 문서-코드 정합성 검증 step-2: erd.md와 Flyway 스키마 대조

- 작업일: 2026-10-27
- 역할: 검증자(Claude, Sonnet 5.5). 코드(애플리케이션·마이그레이션)는 수정하지 않았고 `erd.md`와 검사 스크립트만 변경했다.
- 기준 우선순위: 마이그레이션이 만드는 실제 스키마 > `erd.md`
- 이전 단계: [step-1](step-1.md). 이 브랜치는 step-1 브랜치(`docs/api-spec-sync-step1`) 위에 쌓았다. CI 워크플로의 같은 구간을 고치기 때문에 step-1 PR이 먼저 머지되어야 한다.

## 방법

Flyway `V1`~`V26`을 번호 순서로 실행한 것과 같은 최종 스키마(`CREATE TABLE`, `ALTER TABLE ADD/DROP/MODIFY COLUMN`, `RENAME TABLE`, `CREATE INDEX`)를 스크립트로 만들고 `erd.md`와 비교했다. 실제 DB를 띄우지 않고 SQL 텍스트를 해석한다.

| 대조 항목 | 비교 대상 |
| --- | --- |
| 테이블 목록 | 스키마 vs 엔터티 표 |
| 컬럼 | 스키마 컬럼 vs 엔터티 표 "주요 필드" (양방향) |
| UNIQUE | 엔터티 설명의 `UNIQUE(...)` vs 스키마 (양방향) |
| FK | "관계" 블록의 `DB FK` / `FK 없음` 표기 vs 스키마의 FK (양방향) |

## 결과

| 항목 | 대조 건수 | 일치 | 불일치 |
| --- | --- | --- | --- |
| 테이블 | 25 | 25 | 0 |
| 컬럼 | 스키마 전 컬럼 | 전부 | **3건** (erd 누락) |
| UNIQUE | 스키마의 모든 UNIQUE | 전부 | 0 |
| FK | 스키마 FK 23개 | 23 | 0 (관계 블록 23줄과 1:1) |

### 불일치 목록 (모두 erd 쪽 누락, 마이그레이션이 맞음)

| # | 내용 | 근거 |
| --- | --- | --- |
| 1 | `member.withdrawn_at`이 엔터티 표에 없다 | `V11__add_member_withdrawn_at.sql` |
| 2 | `charge_order.payment_method`가 없다 | `V21__add_payment_method_to_charge_order.sql` |
| 3 | `charge_order.payment_provider`가 없다 | 위와 같음 |

세 건 모두 컬럼 추가 마이그레이션이 erd 갱신 없이 들어간 경우다. 원인은 모두 "NULL 허용 컬럼 추가"처럼 변경이 작아서 문서 갱신이 빠진 것으로 보이지만, 이유는 확인하지 못했다(추정).

### 수동으로 확인한 것 (스크립트 대상 밖)

- 문서의 CHECK 블록(`reservation` 5개, `spaces` 1개, `inquiries` 1개)이 스키마의 CHECK와 일치한다.
- 컬럼 타입·NULL 서술 중 `member.balance`(INT NOT NULL DEFAULT 0), `member.email_verified_at`(DATETIME(6) NULL), `refresh_token.auth_session_id`(VARCHAR(36) NOT NULL), `refresh_token.consumed_at`(DATETIME(6) NULL), `auth_session.id`(VARCHAR(36)), `login_attempt_bucket.attempt_count`(SMALLINT UNSIGNED), `login_attempt_bucket.email`(VARCHAR(254)), `verification_guard.purpose`(VARCHAR(30)), `verification_challenge.code_hash`(64자)가 모두 일치한다.
- 인덱스는 자동 검사에서 제외했다. 한 행이 다른 테이블의 인덱스를 함께 적기 때문이다(`space_ranking_snapshot` 행이 `reservation`·`credit_transaction` 인덱스를 설명). 해당 두 인덱스는 스키마에 있음을 확인했다.
- 관계 블록은 일치하지만 mermaid 다이어그램의 `member ||--o{ audit_logs`는 FK가 아닌 논리적 참조다. 본문과 간선 이름(`performs_logical`)에 이미 그렇게 적혀 있어 불일치로 보지 않았다.

## 변경 파일

- `docs/architecture/erd.md`: 위 3개 컬럼과 설명 추가, 변경 이력 표에 2행 추가
- `.github/scripts/check_erd.py`: 대조 스크립트 (신규)
- `.github/scripts/test_check_erd.py`: 단위 테스트 9개 (신규)
- `.github/workflows/backend-ci.yml`: 기존 step-1 검사 단계에 `check_erd.py`와 새 테스트를 추가

## 실행한 명령과 결과

- `python3 .github/scripts/check_erd.py <repo>`: 수정 전 3건 실패(위 목록), `erd.md` 수정 후 실패 0건.
- `python3 -m unittest test_check_api_spec test_check_erd`: 17개 통과(step-1 8개 + 이번 9개).
- 탐지력: 테스트 9개가 각각 "ALTER로 추가한 컬럼 누락", "erd에만 있는 컬럼", "DROP한 컬럼 잔존", "테이블 누락", "스키마에 없는 UNIQUE 주장", "설명 없는 스키마 UNIQUE", "없는 FK 주장", "관계 블록에 없는 실제 FK"를 잡는지 확인한다. `RENAME TABLE` 후 FK 참조 이름을 따라가는 경우도 포함한다.
- 전체 백엔드 테스트는 이 단계 범위가 아니므로 실행하지 않았다(애플리케이션 코드 변경 없음).

## 가정한 것과 확인하지 못한 것

- SQL을 정규식으로 해석한다. 현재 26개 마이그레이션에서는 파싱 못 한 문장이 없었지만, `ALTER TABLE ... ADD CONSTRAINT` 같은 다른 구문이 앞으로 추가되면 놓칠 수 있다. 그때는 스크립트를 먼저 확장해야 한다.
- 실제 MySQL에 마이그레이션을 적용한 스키마(`information_schema`)와는 비교하지 않았다. 정규식 해석의 오류를 배제하려면 Testcontainers 등으로 적용한 결과와 대조하는 방법이 있다(step 후보).
- 엔티티 클래스(JPA 매핑)와 DDL의 일치는 이 단계에서 보지 않았다. `ddl-auto` 설정에 따라 엔티티가 스키마와 어긋나도 이 검사는 통과한다.
- 컬럼 타입·CHECK는 자동 검사하지 않는다(위 수동 확인은 일부 표본이다).
- 작업 환경 메모: 이 레포의 공유 체크아웃에서 다른 세션이 브랜치를 바꾸는 일이 있어, 이 단계는 별도 worktree에서 진행했다. step-1 커밋이 한때 의도하지 않은 브랜치(`dev` 로컬)에 만들어졌다가 `docs/api-spec-sync-step1`로 옮겨졌다(원격 `dev`에는 push되지 않았다).

## 관련 결정 문서

- `docs/decisions/*` 변경 없음.

## 리뷰 체크리스트

- [ ] `member.withdrawn_at`이 NULL 허용이고 탈퇴 시 기록된다는 서술을 코드(`Member` 엔티티, 탈퇴 서비스)와 대조
- [ ] `payment_method`·`payment_provider` 서술(토스 응답 `method`, `easyPay.provider`)을 `TossPaymentsPgClient` 매핑과 대조
- [ ] CI 단계가 PR에서 실제로 실행되는지(워크플로 YAML은 로컬에서 파서로 검증하지 못함)
- [ ] 엔티티-DDL 일치 검사를 step 후보로 둘지 결정

## 사용자 결정이 필요한 질문

1. 엔티티 매핑과 DDL의 일치(JPA `ddl-auto: validate` 또는 Testcontainers 기반 대조)를 다음 단계로 할까?

## 발표용 숫자 (step-1 + step-2 누적)

- 대조: 엔드포인트 65, 에러 코드 65, 테이블 25, FK 23. 실제 불일치: api-spec 1(+누락 에러 코드 1) , AGENTS.md 3, erd 3. 상태 코드·FK·UNIQUE 불일치는 0건.
- 두 검사는 CI에서 매 PR마다 실행되고, 단위 테스트 17개가 각 검사의 탐지력을 확인한다.
