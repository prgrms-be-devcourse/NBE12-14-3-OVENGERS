# 뮤테이션 테스트(PIT) 도입 계획

> 작성: 2026-10-26. 범위·순서의 기준 문서다. 이유와 trade-off는 [decisions/mutation-testing.md](../../decisions/mutation-testing.md)에 있고 여기에 복사하지 않는다. 상태: 0~4단계와 후속 #154 완료(2026-10-26), 5~6단계 대기. 진행 현황은 아래 "3-1. 진행 현황"을 본다.

## 1. 목표와 범위

- 목표: 포트폴리오 서사가 우선이다(품질 게이트는 목표 아님). AI가 작성·변환한 테스트가 "틀렸는데 통과"하거나 "누락"된 곳을 살아남은 뮤턴트로 찾아 보강한다.
- 포함: 핵심 도메인 로직(정책, 금액 계산, 상태 전이). 이태호 담당(reservation, credit PG 충전)부터 시작하고, 이후 access·space·credit 원장으로 넓힌다
- 제외: Controller, Repository, DTO, 설정, PG 클라이언트. 통합 테스트(Testcontainers) 의존 코드. 동시성·멱등성 검증(기존 통합 테스트가 담당)
- 비목표: 점수 목표, 점수 100%, CI 상시 실행(수동 실행. 실행 시간을 본 뒤 편입 여부 재검토)
- 성과 기록: 전/후 점수(참고), 발견한 구멍 N개, 추가한 테스트 M개, 대표 사례 3개(기존 테스트에서 고른 사례 포함 가능)

## 2. 대상과 현재 테스트 현황

2026-10-26 코드 확인 기준.

| 단계 | 대상 클래스 | 담당 | 줄 수 | 기존 테스트 | 비고 |
| --- | --- | --- | --- | --- | --- |
| 1 | `reservation/policy/ReservationTimePolicy` | 이태호 | 72 | Kotlin 단위 + Java 서비스 테스트 일부 | 시간 경계. 수동 뮤테이션 이력 있음(step-1) |
| 1 | `reservation/policy/ReservationRefundPolicy` | 이태호 | 33 | Kotlin 단위 | 환불 비율 |
| 3 | `reservation/service/PricingService` | 이태호 | 21 | Java 단위 | 가격 계산 |
| 4 | `reservation/entity/Reservation` 상태 전이 메서드 | 이태호 | 324 | 대부분 Java 서비스·통합 테스트 | 단위 테스트 부족 가능성. 먼저 확인 필요 |
| 4 | `credit/entity/ChargeOrder` 상태 전이 | 이태호 | 160 | Java | 금액·멱등성 근처 |
| 5 | `access/policy/DoorAccessTimePolicy` | 박창현 | 64 | Kotlin 단위 + 서비스 테스트 | 출입 시간 경계 |
| 5 | `space/policy/SpaceOperatingHoursPolicy` | 김재철 | 88 | Kotlin 단위 + 서비스 테스트 | 운영 시간 |
| 5 | `credit/service/CreditService` | 백한비 | 53 | Java | 크레딧 차감·증가 |

> 2026-10-26 결정: 한 사람(이태호)이 PIT 설정·실행·측정·테스트 추가를 모두 맡는다. 5단계 대상은 다른 팀원 담당 파일이므로, 새 테스트는 새 파일로 추가하고 PR은 해당 담당자의 리뷰를 받는다. 구멍인지 동치 뮤턴트인지 애매하면 담당자에게 묻는다. 담당 구분은 [team-guide.md](../kotlin-migration/team-guide.md)를 따른다.

## 3. 단계

한 번에 한 단계만 진행한다. 각 단계 보고는 `docs/lab/notes/mutation-testing/step-{번호}.md`에 둔다.

| 단계 | 내용 | 완료 기준 |
| --- | --- | --- |
| 0 | 결정 확정(목표, 범위, CI, 성과 기록 방식). 결정 문서 §2 갱신 | 완료(2026-10-26), 결정 문서 status 채택 |
| 1 | `build.gradle`에 PIT 플러그인 추가, 정책 2개(`ReservationTimePolicy`, `ReservationRefundPolicy`)로 한정 설정, 로컬 실행 확인 | 정책 2개 baseline 점수·살아남은 뮤턴트 목록 기록, 실행 시간 기록 |
| 2 | 살아남은 뮤턴트 분류(구멍 / 동치 / 대상 아님), 구멍은 테스트 먼저 추가 | 추가 테스트가 해당 뮤턴트를 죽임, 전체 테스트 통과 |
| 3 | `PricingService`로 확장 | 단계 2와 동일 |
| 4 | 상태 전이(`Reservation`, `ChargeOrder`)로 확장(범위 확정). `dev`를 다시 받아 최신 상태에서 baseline 측정. 단위 테스트가 부족하면 먼저 단위 테스트 추가 | 단계 2와 동일 |
| 5 | 다른 팀원 담당 클래스(`DoorAccessTimePolicy`, `SpaceOperatingHoursPolicy`, `CreditService`)로 확장. 단계 2와 동일하게 진행하고 PR은 담당자 리뷰를 받는다 | 단계 2와 동일 |
| 6 | 전/후 점수, 구멍 N개·테스트 M개·대표 사례 3개 정리, 결정 문서 §5·면접 정리 채움, CI 편입 여부 재검토 | 검증 결과 작성 |

### 3-1. 진행 현황 (2026-10-26)

| 단계 | 상태 | 결과 | 근거 |
| --- | --- | --- | --- |
| 0 | 완료 | 결정 확정 | PR #142 |
| 1 | 완료 | PIT 설치, 정책 2개 baseline | PR #142, [step-1](../../lab/notes/mutation-testing/step-1.md) |
| 2 | 완료 | 구멍 1개, 테스트 4개 | PR #144, [step-2](../../lab/notes/mutation-testing/step-2.md) |
| 3 | 완료 | 구멍 0개 | PR #151, [step-3](../../lab/notes/mutation-testing/step-3.md) |
| 4 | 완료 | 구멍 7개, 테스트 9개 (`ChargeOrder` PIT, 예약 조건부 UPDATE 수동 변조) | PR #151, [step-4](../../lab/notes/mutation-testing/step-4.md) |
| 후속 #154 | 완료 | `ChargeOrder` 전이 수동 변조 20건, 구멍 5개, 테스트 2개 | [follow-up-154](../../lab/notes/mutation-testing/follow-up-154.md) |
| 후속 #155 | 대기 | `Reservation` 엔티티의 호출되지 않는 전이 메서드 7개 삭제 여부 (팀 판단) | 이슈 #155 |
| 5 | 대기 | 팀원 담당 클래스. 담당자 사전 공유 필요 | |
| 6 | 대기 | 발표용 정리, 면접 정리, CI 편입 재검토 | |

누적: 구멍 13개, 추가한 테스트 15개(직접 죽인 11개 + 경계 고정용 4개). 상세 수치와 한계는 [결정 문서 §5](../../decisions/mutation-testing.md)에 있다.

### 작업 규칙

- 새 테스트는 기존 테스트 파일에 끼워 넣지 않고 새 파일로 추가한다(다른 팀원의 테스트 전환 PR과 충돌 방지).
- 운영 코드는 수정하지 않는다. 필요해 보이면 멈추고 질문한다(§5).

## 4. 구현 전 확인 항목 (1단계에서 확인 완료: [step-1](../../lab/notes/mutation-testing/step-1.md))

- Kotlin 1.9.25 + Spring Boot 3.3.4 + Gradle 8.10.2에서 사용할 PIT 플러그인과 JUnit 5 연동 버전 (공식 문서로 확인하고 출처를 기록한다)
- Java 테스트가 Kotlin 클래스를 대상으로도 정상적으로 실행되는지
- Kotlin 생성 코드로 인한 노이즈 뮤턴트의 양과 필터 방법
- `Reservation` 상태 전이 메서드에 대한 단위 테스트 유무
- 로컬 실행 시간

## 5. 위험

- Kotlin 노이즈 뮤턴트로 점수가 왜곡될 수 있다. 점수보다 개별 뮤턴트를 읽는다.
- 대상을 넓히면 통합 테스트가 같이 돌아 느려질 수 있다. 대상 테스트를 단위 테스트로 한정한다.
- 이 작업은 기능을 바꾸지 않는다. 운영 코드는 수정하지 않고 테스트만 추가한다. 운영 코드 수정이 필요해 보이면 멈추고 질문한다.
