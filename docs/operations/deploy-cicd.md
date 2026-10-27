# 배포 및 CI/CD

> **설정 기준: 기능 동결 커밋 `d1a15ec` / 문서 갱신일: 2026-10-21**. 해당 커밋의 `.github/workflows/backend-ci.yml`·`frontend-ci.yml` 기준이며, 이번 변경은 문서만 최신화한다. 기능 동결 기준은 유지한다. 아래 §4는 2026-09-25 당시 실행 기록이다. 설정 확인과 운영 배포 성공·기능 검증은 구분한다. PG·예약 대기 구현은 기준 커밋에 포함돼 있으며, 이번 문서 작업에서 운영 환경을 실행 검증하지 않았다.

> **2026-10-28 갱신**: Kotlin 전환 완료 후 `DEPLOY_ENABLED=true`로 배포를 켜고, 3차(Kotlin) JAR를 2차와 같은 EC2·RDS에 교체 배포했다. 결과와 서버에서 확인한 항목은 §4-2, 3차 레포 runner는 §3-5에 적었다. 아래 §4-1은 2026-09-25 당시 2차 기록이다.

## 1. 인프라 구성

| 구성 요소 | 내용 |
| --- | --- |
| 백엔드 | AWS EC2 (Ubuntu), Spring Boot 3.3.4 + Java 21, Gradle |
| DB | AWS RDS MySQL 8.4 |
| 프론트엔드 | Next.js(App Router), `STATIC_EXPORT=true npm run build`로 정적 export 후 S3 + CloudFront 배포 |
| S3 버킷 | `slotkey-web-page` |
| CloudFront distribution ID | `E3PEHWSJNM0BPV` |
| AWS 인증 | IAM 사용자 `slotkey-deploy` (CLI 전용, S3FullAccess + CloudFrontFullAccess). 루트 계정 액세스 키는 사용하지 않음 |

## 2. 백엔드 배포 방식

- **JAR 배포 + systemd 관리**: self-hosted runner가 체크아웃한 `backend`에서 빌드한다. 워크플로는 Docker 이미지로 배포하지 않는다.
- 빌드 산출물: `build/libs/slotkey-0.0.1-SNAPSHOT.jar`
- 설치 JAR: `/home/ubuntu/slotkey/slotkey.jar`. 설치 후 `sudo systemctl restart slotkey`를 실행한다.
- 실행 포트: `8080` (커스텀 `server.port` 설정 없음, 기본값)
- 실행 프로필: 과거 운영 기록은 `prod`. 현재 워크플로에는 Java 실행 인자가 없으므로 실제 프로필은 서버의 systemd unit·환경 설정을 별도로 확인해야 한다.
- **알려진 제약**: EC2가 저사양이라 `./gradlew build` 중 테스트 태스크가 OOM(exit 137)으로 killed되는 문제가 있었음 → EC2에서 직접 빌드할 때는 `./gradlew build -x test`로 테스트 스킵.
- **현재 기동 확인**: `systemctl is-active`와 이번 재시작 이후 journal의 `Started ... in ... seconds` 로그를 확인한다. HTTP 기능 응답이나 DB·외부 서비스 정상 동작을 검증하는 절차는 아니다.

## 3. CI/CD (GitHub Actions)

파일 위치: `.github/workflows/backend-ci.yml` (기존 PR 빌드 워크플로에 배포 job을 추가하는 방식으로 확장 — 별도 워크플로 파일을 새로 만들지 않음).

### 3-1. 트리거

```yaml
on:
  pull_request:
    branches: [dev, main]
  push:
    branches: [dev]
```

- PR 생성/업데이트 시: `build` job만 실행 (테스트 포함 빌드 검증).
- `dev` 브랜치에 실제 push(merge)될 때: `build` → `deploy` 순서로 실행.
- **참고**: 현재는 `dev` push만 실제 배포를 트리거함. 추후 `main`으로 배포 대상을 옮길 경우 `deploy` job의 `if` 조건과 `push.branches`를 같이 수정해야 함.

### 3-2. build job

- `runs-on: ubuntu-latest` (GitHub 호스팅 러너)
- JDK 21(Temurin) 설정 → `./gradlew build` (테스트 포함) 실행
- 저사양 EC2에서 겪었던 OOM 문제와 무관 — GitHub 호스팅 러너는 별도 리소스라 테스트를 스킵하지 않고 그대로 실행

### 3-3. deploy job

- `runs-on: self-hosted` — EC2 인스턴스 자체에 등록한 GitHub Actions self-hosted runner에서 실행
- `needs: build` — build job 성공 시에만 실행
- 조건: `dev` 브랜치로의 push 이벤트일 때만 실행 (PR에서는 스킵)
- 단계: 체크아웃 → JDK 21 설정 → `./gradlew clean build -x test` → `install -D -m 644`로 `/home/ubuntu/slotkey/slotkey.jar` 설치 → 재시작 기준 시각 기록 → `sudo systemctl restart slotkey` → 서비스 활성 상태와 기준 시각 이후 기동 완료 로그 확인(최대 24회, 5초 간격). 서비스 비활성 또는 제한 시간 내 기동 로그 미확인 시 실패한다.

### 3-4. self-hosted runner를 선택한 이유

GitHub Actions에서 EC2로 배포하는 방법은 크게 두 가지:

1. **SSH 직접 접속**: GitHub Secrets에 SSH 키를 등록하고 Actions가 EC2에 접속. EC2의 22번 포트를 인터넷에 열어야 함 — GitHub 호스팅 러너는 고정 IP가 아니라서 IP 기준으로 좁힐 수 없고, 사실상 전체 개방(0.0.0.0/0)이 되어 무차별 대입 공격 등 보안 리스크가 커짐.
2. **self-hosted runner (채택)**: EC2 자체를 GitHub Actions 러너로 등록. 러너가 EC2 안에서 GitHub 쪽으로 나가는 방향으로만 통신하므로, 22번 포트를 열 필요가 전혀 없음. EC2 재시작 시 퍼블릭 IP가 바뀌어도(Elastic IP 미사용) 워크플로 설정을 손볼 필요가 없다는 부수적 이점도 있음.

보안과 운영 안정성 면에서 self-hosted runner가 낫다고 판단해 이 방식을 채택함.

### 3-5. self-hosted runner 설치 정보

- 설치 경로: `/home/ubuntu/actions-runner`
- systemd 서비스로 등록 (`sudo ./svc.sh install && sudo ./svc.sh start`) → EC2 재부팅 시에도 자동 기동
- 서비스명 예: `actions.runner.prgrms-be-devcourse-NBE12-14-2-OVENGERS.ip-172-31-46-159.service`
- 등록 확인: 저장소 Settings → Actions → Runners에서 상태 확인 가능
- **3차 레포 runner (2026-10-28)**: runner는 레포 단위로 등록되므로 2차 runner는 3차 레포의 deploy job을 받지 못한다. 같은 EC2에 `/home/ubuntu/actions-runner-3`로 3차 레포 runner를 따로 등록하고 서비스(`actions.runner.prgrms-be-devcourse-NBE12-14-3-OVENGERS.<호스트>.service`)로 설치했다. 서비스 사용자는 `ubuntu`다(워크플로가 `sudo systemctl restart slotkey`를 쓴다).
- **2차 레포 runner는 중지·비활성화했다.** 켜 두면 2차 레포 `dev` push 때 옛 2차 JAR가 3차를 덮어쓴다. 다시 켜야 하면 먼저 서버의 `slotkey.jar`가 어느 버전인지 확인하고 3차 deploy를 끈다(`DEPLOY_ENABLED` 해제).

### 3-6. 프론트엔드 배포 자동화 (2026-09-30 추가)

파일 위치: `.github/workflows/frontend-ci.yml`

- 이전에는 `STATIC_EXPORT=true npm run build` → S3 업로드 → CloudFront invalidation을 매번 콘솔/CLI에서 수동으로 했음. 이제 `dev` push 시 자동으로 실행됨.
- 트리거는 백엔드와 동일하게 `pull_request: [dev, main]`(빌드 검증만) / `push: [dev]`(빌드+배포), 경로 필터는 `frontend/**`와 `.github/workflows/frontend-ci.yml`이다. 프론트 코드 또는 해당 워크플로 변경 시 실행하며, 백엔드만 바뀐 PR·push에서는 실행되지 않음.
- `build` job (`ubuntu-latest`): `npm ci` → `npm run lint` → `tsc --noEmit` → `node --test tests/*.cjs` → `STATIC_EXPORT=true npm run build`(실제 배포 빌드와 동일한 조건으로 빌드 자체가 되는지 검증).
- `deploy` job: `build` 성공 + `dev` push일 때만, `ubuntu-latest`에서 실행(백엔드와 달리 self-hosted runner 불필요 — EC2에 접속할 필요 없이 AWS API만 호출하면 되므로).
  1. `STATIC_EXPORT=true npm run build`로 `frontend/out/` 생성
  2. `aws-actions/configure-aws-credentials`로 IAM 사용자 `slotkey-deploy`의 키를 사용해 인증
  3. `aws s3 sync out/ s3://slotkey-web-page --delete`
  4. `aws cloudfront create-invalidation --distribution-id E3PEHWSJNM0BPV --paths "/*"`
- **워크플로가 참조하는 GitHub Secrets** (실제 값·등록 상태는 이번 문서 작업에서 조회하지 않음):
  - `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`: IAM 사용자 `slotkey-deploy`의 CLI 키
  - `AWS_REGION`: S3 버킷 리전(예: `ap-northeast-2`)
  - 프론트 배포 빌드는 Repository Variable `NEXT_PUBLIC_TOSS_PAYMENTS_CLIENT_KEY`도 참조한다. 실제 값은 문서에 기록하지 않는다.

## 4. 검증 기록

### 4-1. 2차 배포 (2026-09-25)

- `dev` push → `build`(3m35s) → `deploy`(1m52s), 총 5m33s 만에 자동 배포 완료 확인
- 배포 후 `ps aux`로 프로세스 PID가 재기동 시점 기준으로 새로 바뀐 것 확인
- 외부에서 `curl http://<EC2 퍼블릭 IP>:8080` 호출 시 정상 응답(`AUTHENTICATION_REQUIRED`) 확인 — 서버 및 인증 필터 정상 동작

### 4-2. 3차(Kotlin) 교체 배포 (2026-10-28)

**구성**: 2차와 같은 EC2(c7i, 메모리 3.7GiB)의 `slotkey` 서비스·`/home/ubuntu/slotkey/slotkey.jar`·8080, 같은 RDS `slotkey` 스키마, 같은 `/home/ubuntu/slotkey.env`를 그대로 쓴다. 2차를 동시에 띄우지 않고 교체한 이유는 메모리다(JVM `-Xmx1g` + 같은 호스트의 Kafka). 워크플로 수정은 없다.

**DB 호환**: 기능 동결 커밋 `d1a15ec`와 전환 후 `dev`의 `db/migration`은 diff가 없다(V1~V26). 배포 전 RDS의 `flyway_schema_history` 최대 버전이 26이고 V24~V26이 `success = 1`임을 직접 조회해 확인했다. 그래서 3차 기동이 새 마이그레이션을 적용하지 않는다.

**절차와 결과**
1. RDS 스냅샷 생성, 2차 JAR를 `slotkey.jar.2nd.bak`으로 보관(롤백용).
2. 3차 레포에 runner 등록(§3-5), AWS 시크릿 3개와 `NEXT_PUBLIC_TOSS_PAYMENTS_CLIENT_KEY` 변수 등록. S3 버킷과 CloudFront ID는 워크플로 파일에 고정돼 있어 별도 등록이 필요 없다.
3. `DEPLOY_ENABLED=true` 등록 후 `dev` 실행을 다시 돌렸다. `build` 11분 20초, `deploy` 2분 34초 통과.
4. 서버 journal에 `Started ... in 13.52 seconds`, `slotkey.jar` 85MB(2차 79MB)로 교체, `curl http://localhost:8080/`가 401(인증 필터 동작). 배포 후 가용 메모리 2.4GiB, 스왑 사용 143MiB.
5. 2차 runner 서비스 중지·비활성화, 3차 runner `active` 확인.
6. CloudFront 주소에서 로그인, 예약 HOLD 후 결제, 크레딧 충전(토스 sandbox), 도어 토큰 발급을 사람이 직접 확인했다. 자동화된 E2E 기록은 아니다.

**확인하지 않은 것**: 회원가입부터 이용 완료까지의 전체 흐름 재현, 부하·동시성의 운영 환경 측정, Kafka·Outbox 이벤트 소비의 운영 확인, 보안그룹 외 네트워크 구성. 보안그룹 `slotkey-c7i-sg`는 인바운드가 SSH(22)와 8080 두 개이며 둘 다 접두사 목록 소스다. 9092(Kafka)·3306은 열려 있지 않다.

**롤백**: `slotkey.jar.2nd.bak`을 `slotkey.jar`로 복사하고 `sudo systemctl restart slotkey`. DB가 같은 V26이라 2차 JAR와 호환된다. 스키마가 더 올라간 뒤에는 이 전제가 깨지므로 RDS 스냅샷으로 복구한다.

## 5. 향후 개선 후보 (이번 사이클에서는 보류)

- Elastic IP 미적용 상태 — EC2 재시작 시 IP가 바뀔 수 있음(현재 self-hosted runner 구조라 CI/CD 자체는 영향 없지만, 프론트/문서에 박아둔 IP 값은 갱신 필요)
- Actuator 도입 후 정식 `/actuator/health` 기반 헬스체크로 전환
- `main` 브랜치 배포 전환 시 워크플로 조건 수정
- PG·예약 대기 구현은 현재 소스에 존재한다. 운영 활성화 및 외부 연동 검증 상태는 별도 실행 기록으로 확인한다.
