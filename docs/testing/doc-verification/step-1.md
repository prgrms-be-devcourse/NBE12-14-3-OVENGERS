# 문서-코드 정합성 검증 step-1: api-spec 엔드포인트·에러 코드 대조

- 작업일: 2026-10-27
- 역할: 검증자(Claude, Sonnet 5.5). 구현자와 분리된 세션이 아니므로 **자기검증이 아니라 문서 대조**이며, 코드는 수정하지 않았다.
- 기준 우선순위: 테스트가 통과하는 코드 > `core-domain-decisions` > `decisions/*` > `api-spec`·`erd` > README·plans
- 대상: `docs/api/api-spec.md`(615줄), `AGENTS.md`의 에러 코드 예시
- 대조 대상 코드: `backend/src/main/**/controller`(컨트롤러 어노테이션), `global/error/ErrorCode.kt`

## 한눈에 보는 결과

| 항목 | 대조 건수 | 일치 | 불일치 | 비고 |
| --- | --- | --- | --- | --- |
| 엔드포인트 (메서드+경로) | 코드 65, 문서 66 | 63 | 1건 (문서 누락) | 나머지 3건은 스크립트 오탐(아래 표) |
| 에러 코드 존재 (enum → 문서) | enum 65 | 62 | 3건 (문서 미언급) | |
| 에러 코드 HTTP 상태 (`CODE(NNN)` 쌍) | 문서의 모든 쌍 | 전부 | 0건 | 상태 표(59~74줄) 행 기준 대조도 0건 |
| `AGENTS.md` 에러 코드 예시 | 6 | 3 | **3건** (enum에 없음) | 문서가 틀림 |

## 불일치 목록

| # | 종류 | 내용 | 근거 | 어느 쪽이 틀렸나 |
| --- | --- | --- | --- | --- |
| 1 | 엔드포인트 | `PATCH /members/me`(닉네임 수정)가 api-spec에 없다. §3에는 `GET /members/me`만 있다. | `MemberController.kt:55` (`@PatchMapping("/me")`, `updateNickname`), 테스트 `MemberControllerTest.kt` 존재, 문서 164줄 | 문서 누락 |
| 2 | 에러 코드 | `MEMBER_WITHDRAWN`(409)이 api-spec 어디에도 없다. 탈퇴 회원에 대한 정지·복구·크레딧 지급에서 발생한다. | `AdminMemberService.kt:195, 228, 260`, `ErrorCode.kt` | 문서 누락 (§3의 `suspend`, `restore`, `credits` 오류 목록에 추가 필요) |
| 3 | 에러 코드 | `ACCESS_DENY_REASON_REQUIRED`(400)가 api-spec에 없다. | `DoorAccessLogService.java:90` | 문서 누락이지만 서버 내부 불변식 검사라 외부 API 오류인지 확인 필요 |
| 4 | 에러 코드 | `ACCESS_LOG_NOT_FOUND`(404)가 api-spec에 없다. | `DoorAccessLogService.java:108` | 위와 같음 |
| 5 | AGENTS.md | `MEMBER_NOT_ACTIVE`(403), `RESERVATION_NOT_CONFIRMED`(422), `TOKEN_ISSUE_TOO_EARLY`(422)가 `ErrorCode`에 없다. 현재 코드에서 비활성 회원은 `ACCOUNT_INACTIVE`(403)이고, 도어 토큰 발급 시간 제한은 2026-09-15에 없어졌다(api-spec 454줄: "발급에는 시간 제한이 없다"). | `ErrorCode.kt` 전수, api-spec 454·468줄 | **문서가 틀림** (옛 규칙의 잔재) |

## 오탐으로 판정한 항목 (스크립트가 불일치로 표시했으나 실제는 일치)

| 표시 | 판정 이유 |
| --- | --- |
| 코드에만 있음: `PUT /admin` | `@PutMapping(value = ["/spaces/{spaceId}/image"], ...)`처럼 여러 줄 어노테이션을 정규식이 못 읽은 것. 실제 경로는 `PUT /admin/spaces/{spaceId}/image`이며 문서(186줄)와 일치 |
| 문서에만 있음: `PUT /admin/spaces/{}/image` | 위와 같은 원인 |
| 문서에만 있음: `DELETE /reservations/{}` | 문서 394줄의 "`DELETE /reservations/{id}`가 아니다"라는 부정 문장 |
| 문서에만 있음: `GET /stats` | `/stats/space-ranking`, `/stats/location-ranking`을 묶어 부른 `GET /stats/**` 표기 |

## 문서 안의 에러 코드 중 enum에 없는 것 (의도된 것인지 확인함)

- `IDEMPOTENCY_KEY_CONFLICT`: 문서가 "미구현"이라고 명시(33줄, 542줄). 일치.
- `RESERVATION_HOLD_EXPIRED`: 문서가 "이 코드가 아니라 `RESERVATION_STATE_CONFLICT`"라고 명시(385줄). 일치.
- 나머지(`NO_SHOW`, `TOP_UP`, `PG_MISMATCH` 등)는 에러 코드가 아니라 enum 값·DB 값이다.

## 실행한 명령과 결과

- 스크립트 2개(Python, `-I` 격리 모드): 엔드포인트 대조 `cmp.py`, 에러 코드 대조 `err.py`. 위치는 세션 scratchpad이며 레포에는 아직 없다.
- 실행 결과는 위 표와 같다. 전체 테스트는 이 단계 범위가 아니므로 실행하지 않았다(코드 변경이 없다).

## 가정한 것과 확인하지 못한 것

- 컨트롤러 어노테이션은 정규식으로 읽었다. 여러 줄 어노테이션이나 클래스 상속으로 선언된 경로는 놓칠 수 있다(오탐 2건이 그 사례). 확인은 수동으로 했다.
- **엔드포인트별 오류 목록은 대조하지 못했다.** 예를 들어 "`POST /reservations/{id}/pay`가 문서에 적힌 오류만 던지는가"는 서비스 코드를 읽어야 해서 이 단계 밖이다. 상태 코드 대조는 "코드 이름 → 상태" 매핑이 맞는지만 확인한다.
- 요청/응답 필드명(DTO)은 대조하지 않았다.
- 문서 200~385줄(충전·웹훅·예약 생성·결제)은 스크립트가 엔드포인트와 에러 코드만 읽었고 서술 내용은 읽지 않았다.
- 3·4번은 외부 API로 노출되는 오류인지 확인하지 못했다.

## 관련 결정 문서

- `docs/decisions/*` 변경 없음. 이 단계의 방법론을 결정 문서로 남기는 것은 step-2 이후에 판단한다(아직 도입이 확정되지 않았다).

## 리뷰 체크리스트

- [ ] 1번: `PATCH /members/me`를 api-spec §3에 추가하고, 요청 필드(`nickname`)와 오류 목록을 `MemberUpdateRequest`로 확인
- [ ] 2번: `MEMBER_WITHDRAWN`(409)을 `suspend`, `restore`, `credits` 오류 목록과 상태 코드 표 409 행에 추가
- [ ] 3·4번: 외부 노출 여부를 확인한 뒤 문서화할지 결정
- [ ] 5번: `AGENTS.md` "자주 참조할 에러 코드"에서 없는 코드 3개를 현재 코드(`ACCOUNT_INACTIVE` 등)로 교체
- [ ] 스크립트를 `scripts/`로 옮겨 CI에 넣을지 결정

## 후속 처리 (브랜치 `docs/api-spec-sync-step1`)

사용자 결정(2026-10-27): 별도 `docs/*` 브랜치에서 처리하고, 대조 스크립트는 CI에 넣는다.

| # | 처리 | 결과 |
| --- | --- | --- |
| 1 | `api-spec.md` §3에 `PATCH /members/me` 추가 | 반영 |
| 2 | `MEMBER_WITHDRAWN`(409)을 `suspend`·`restore`·`credits` 오류 목록과 상태 코드 표 409 행에 추가 | 반영 |
| 3·4 | `ACCESS_DENY_REASON_REQUIRED`, `ACCESS_LOG_NOT_FOUND`: `createDenyLog`는 호출부가 항상 사유를 넘기는 내부 불변식 검사이고, `findById`는 컨트롤러·서비스 어디에서도 호출하지 않는다. 외부 API로 나가지 않으므로 문서화하지 않고 스크립트의 `INTERNAL_ONLY`에 이유와 함께 등록 | 반영 |
| 5 | `AGENTS.md`의 없는 코드 3개를 `RESERVATION_STATE_CONFLICT`(409), `ACCOUNT_INACTIVE`(403)로 교체 | 로컬 파일만 수정. **`AGENTS.md`는 `.gitignore:121`에 있고 git이 추적하지 않아 커밋·PR에 들어가지 않는다** |
| 스크립트 | `.github/scripts/check_api_spec.py`, 테스트 `test_check_api_spec.py`(8개), `backend-ci.yml`에 단계 추가 | 반영 |

### 스크립트 검증

- 레포 현재 상태에서 실행: 실패 0건, 경고 2건(`DELETE /reservations/{}` 부정 문장, `GET /stats` 묶음 표기).
- 단위 테스트 8개 통과. 탐지력 확인: 문서에서 엔드포인트·에러 코드를 빼거나 상태를 틀리게 하거나 enum에 없는 코드를 넣으면 각각 실패로 잡히는 테스트가 있다. 여러 줄 `@PutMapping(value = [...])`를 읽는 회귀 테스트도 포함한다(step-1의 오탐 원인).
- 기존 `test_check_test_count.py` 7개도 그대로 통과.
- CI 워크플로(`backend-ci.yml`)는 로컬에서 YAML 파서로 검증하지 못했고, 같은 작업 디렉터리 기준 명령 실행만 확인했다. 실제 동작은 PR의 CI로 확인해야 한다.

### 한계 (새로 알게 된 것)

- `AGENTS.md`, `CLAUDE.md`가 git에 추적되지 않아 CI는 이 파일을 보지 못한다. 스크립트는 파일이 있을 때만 검사한다. 이 규칙 파일이 팀에 공유되지 않는 것은 별도로 확인할 문제다.
- 문서에만 있는 엔드포인트는 경고만 한다. 문서가 존재하지 않는 API를 설명해도 빌드는 실패하지 않는다.
- 엔드포인트별 오류 목록, DTO 필드 대조는 여전히 하지 않았다(step-2 후보).

## 발표용 숫자 (현재까지)

- 엔드포인트 65개 대조 → 실제 불일치 1건, 에러 코드 enum 65개 대조 → 문서 누락 3건, 문서 쪽 오류(AGENTS.md) 3건. 상태 코드 불일치는 0건.
- 탐지력: 단위 테스트 8개로 확인(누락·상태 불일치·미존재 코드 각각 실패 처리).
