# 배포·서버 세팅

서버 세팅 진행 상황과 배포 구성, 이 주제로 팀·멘토와 정한 내용을 한곳에 모았다.
최종 갱신 2026-10-06. 서버 상태는 문서보다 실제 명령 결과가 우선이다.

> 이 레포는 public 이다. 인스턴스 ID·공인 IP·비밀값은 적지 않는다. EC2 콘솔과 서버의 `deploy/.env` 에서 확인한다.

## 한눈에

| 항목 | 내용 |
|---|---|
| 서버 | 카테캠이 팀당 EC2 1대 제공 (2026-08-31 ~ 2026-11-20). t3.medium 4GB · Ubuntu 24.04 · 50GB · 서울 리전 고정 |
| 접속 | Session Manager (22 포트를 열지 않음) |
| 주소 | `https://<EIP 대시 표기>.sslip.io` (예: EIP `1.2.3.4` → `1-2-3-4.sslip.io`). 실제 주소는 팀 채널 공지 |
| 구성 | 단일 VM + docker compose. Caddy 가 `/`→프론트, `/api`→Spring 으로 경로 분기 (같은 오리진) |
| 배포 | `develop` 머지 → GitHub Actions(self-hosted runner) 가 서버에서 다시 빌드·기동 |
| 담당 | 곽태훈 (스펙 담당표에서는 BE 리드의 몫이었으나 9월 말부터 곽태훈이 진행) |

## 1. 제약

- **RDS · ALB · IAM 액세스 키 생성은 불가.** 필요하면 카테캠 인프라 매니저에게 요청하면 검토해 준다.
  → GitHub Actions 에서 AWS API(SSM 등)로 배포할 수 없다. 22 포트도 닫혀 있어 SSH 배포도 안 된다. 그래서 self-hosted runner 를 쓴다 (6절).
- **Elastic IP 는 승인 없이 자가 할당 가능.** EC2 콘솔 → 네트워크 및 보안 → 탄력적 IP. 할당·연결 완료.
- **org third-party 앱 접근 정책은 미해결.** 그래서 GitHub OAuth App 은 개인 계정 소유로 만들었다. org 소유로 옮기려면 `kakaotechcampus-4` org 설정 → Third-party access → OAuth App Policy 를 org owner 가 확인해야 한다. 옮겨도 `deploy/.env` 의 두 값만 바꾸면 된다.
- 비용은 신경 쓰지 않아도 된다. 카테캠이 서버를 제공한다.

## 2. 상태 (2026-10-06 기준)

| 항목 | 상태 | 비고 |
|---|---|---|
| Session Manager 접속 · swap 4GB · Docker + Compose · 로그 회전 · `ubuntu` docker 그룹 | ✅ | 9/13 ~ 9/15 |
| Elastic IP 할당·연결 | ✅ | 10/5 |
| 보안그룹 80/443 | ✅ | 이미 열려 있었다 (외부에서 접속 확인) |
| 도메인 + HTTPS | ✅ | sslip.io + Let's Encrypt (Caddy 자동 발급·갱신) |
| Dockerfile 3종 · 운영 compose · Caddy | ✅ | PR #93 |
| GitHub OAuth App | ✅ | 개인 계정 소유 (1절) |
| 서비스 3개 동시 기동 (스펙 R-7) | ✅ | 10/5. 4개 컨테이너 healthy |
| 배포 환경 로그인 왕복 (스펙 R-10) | ✅ | 10/5. 브라우저로 GitHub 로그인 왕복 성공 |
| Spring ↔ AI 토큰 경로 내부 전용 | ✅ (구성) | AI 포트 비공개를 외부에서 확인. 실제 수집 호출 로그 확인은 남음 (5절) |
| 자동 배포 (GitHub Actions) | ⬜ | 워크플로 PR + 서버에 runner 설치 (6절) |

## 3. 구성

```
브라우저
   │  https://<도메인>          ← 공개 포트는 80/443 뿐
   ▼
web (Caddy) ──  /       → 프론트 빌드 산출물 (SPA)
             └─ /api/*  → backend (Spring :8080)
                              │  내부 Docker 네트워크
                              ├─→ ai (FastAPI :8000)      ← 공개 포트 없음
                              └─→ db (PostgreSQL 17)      ← 공개 포트 없음
```

| 파일 | 역할 |
|---|---|
| `backend/Dockerfile` | JDK 로 bootJar 빌드 → JRE 이미지. 테스트는 CI 가 돌린다(`-x test`) |
| `ai/Dockerfile` | python 3.11-slim + uvicorn. `AI_DOCS_ENABLED=false` |
| `frontend/Dockerfile` | `VITE_API_MOCK=false` 로 빌드 → Caddy 이미지에 담는다 |
| `deploy/compose.yaml` | db · ai · backend · web. backend 는 db 가 healthy 여야 뜨고, web 은 backend 가 healthy 여야 뜬다. ai 가 안 떠도 backend·web 은 뜬다 (분석 Job 만 실패) |
| `deploy/Caddyfile` | 경로 분기, HTTPS, 보안 헤더. `/actuator` 는 프록시하지 않는다 |
| `deploy/.env.example` | 필요한 비밀값 목록과 생성 방법. 서버의 `deploy/.env` 는 gitignore 대상 |

### 로그인 때문에 HTTPS · 같은 오리진이 필수다

`application.yml` 의 세션 쿠키가 `secure: true`, `same-site: lax` 다.

- http 로 배포하면 브라우저가 쿠키를 저장하지 않아 로그인이 안 된다.
- 프론트와 API 를 같은 오리진에 두면 `SameSite=Lax` 쿠키가 그대로 실리고 CORS 설정도 필요 없다.
- Caddy 가 `X-Forwarded-Proto/Host` 를 붙이고, Spring 의 `forward-headers-strategy: framework` 가 이걸 읽어 OAuth `redirect_uri` 를 `https://<도메인>/api/auth/github/callback` 으로 만든다.

### nginx 대신 Caddy 를 쓴 이유

경로 분기는 nginx 와 같다. 차이는 Let's Encrypt 인증서 발급·갱신이 자동이라 certbot·cron 구성이 필요 없다는 점이다. 인증서는 `caddy-data` 볼륨에 보관한다 — 지우면 재발급하는데 발급 횟수 제한에 걸릴 수 있다.

## 4. 처음 띄우기 (서버에서 한 번)

Session Manager 로 접속한 뒤:

```bash
bash
sudo -iu ubuntu            # docker 그룹은 ubuntu 에만 있다 (Session Manager 기본 사용자는 ssm-user)
git clone https://github.com/kakaotechcampus-4/ktc4-kyungpook-1.git ~/gitory
cd ~/gitory/deploy && cp .env.example .env && chmod 600 .env
nano .env                  # 값 채우기 — 생성 방법은 .env.example 주석
docker compose up -d --build --wait && docker compose ps
echo "== 끝"
```

- `TOKEN_ENC_KEY`/`TOKEN_ENC_SALT` 는 한 번 정하면 바꾸지 않는다. 바꾸면 저장된 GitHub 토큰을 복호화할 수 없다.
- `DB_PASSWORD` 는 DB 볼륨이 처음 만들어질 때 고정된다. 나중에 `.env` 만 바꾸면 접속이 실패한다.
- GitHub OAuth App 의 Authorization callback URL 은 `https://<도메인>/api/auth/github/callback` 과 정확히 같아야 한다.

## 5. Spring ↔ AI 토큰 경로 (멘토 확인 완료)

Spring 이 복호화한 GitHub 토큰을 `X-GitHub-Token` 헤더로 AI 서버에 넘긴다. Spring·AI 를 별도 서버로 나누면 이 구간이 공개망을 지날 수 있다는 지적이 PR #65 리뷰에서 나왔다.

- 같은 EC2 의 Docker 네트워크 안에서만 통신하면 토큰이 공개망을 지나지 않는다. **멘토가 이 방향이 맞다고 확인했다** (PR #74 리뷰).
- 구성 후 확인: 외부에서 AI 8000 포트에 접근할 수 없다 (10/5).
- 남은 확인: 실제 분석 실행 시 AI 로그에 `POST /internal/collect` 가 찍히고, 토큰 값이 어느 로그에도 남지 않는지.

## 6. 자동 배포

`develop` 에 머지된 커밋의 CI(backend CI · AI CI · frontend)가 **모두 성공하면** `.github/workflows/deploy.yml` 이 서버에서 돈다. 이미지 빌드는 테스트를 건너뛰므로(`-x test`) CI 가 실패한 커밋은 배포하지 않는다.

0. gate job 이 그 커밋의 CI 를 모두 확인한다. 아직 도는 CI 가 있으면 넘기고 마지막 CI 가 끝날 때 배포한다. 실패한 CI 가 있으면 배포 워크플로도 실패로 표시된다
1. 서버의 `~/gitory` 를 그 커밋으로 맞춘다 (`git checkout --force --detach <sha>` — `deploy/.env` 는 gitignore 라 남는다). 서버에 이미 같거나 더 새 커밋이 있으면 되돌리지 않고 넘긴다
2. `docker compose up -d --build --wait` — 모든 서비스가 healthy 가 될 때까지 기다린다. 5분 안에 안 되면 실패
3. 컨테이너 상태를 출력하고, 성공하면 오래된 이미지·빌드 캐시를 정리한다

Actions 탭 → deploy → **Run workflow** 로 수동 실행도 된다 (develop 에서만, CI 확인 없이 develop 최신 커밋). `deploy/` 만 바뀐 커밋은 CI 가 없어 자동 배포되지 않으므로 수동 실행한다.

### 왜 self-hosted runner 인가

22 포트를 열지 않고 IAM 액세스 키도 만들 수 없어서, GitHub 에서 서버로 **들어가는** 방법이 없다. runner 는 서버가 GitHub 에 **바깥으로** 접속해 작업을 받아 오므로 포트·키가 필요 없다.

### runner 설치 (서버에서 한 번)

1. GitHub 레포 → Settings → Actions → Runners → **New self-hosted runner** → Linux / x64. 화면에 나오는 다운로드 명령을 `ubuntu` 사용자로 `~/actions-runner` 에서 실행한다.
2. 설정 명령은 화면의 토큰을 쓰되 라벨을 붙인다:
   ```bash
   ./config.sh --url https://github.com/kakaotechcampus-4/ktc4-kyungpook-1 --token <화면의 토큰> \
     --name gitory-ec2 --labels gitory-deploy --unattended
   ```
3. 서비스로 등록해 재부팅 후에도 돌게 한다:
   ```bash
   sudo ./svc.sh install ubuntu && sudo ./svc.sh start && sudo ./svc.sh status
   ```
4. Settings → Actions → Runners 에서 `gitory-ec2` 가 **Idle** 이면 된다.

### 🚨 public 레포 + self-hosted runner 주의

fork PR 이 워크플로를 바꿔 `runs-on: self-hosted` 로 실행하면 그 코드가 우리 서버에서 돈다. runner 사용자는 docker 그룹이라 사실상 서버 root 권한이다.

- `deploy.yml` 의 트리거는 `workflow_run`(CI 완료) 과 `workflow_dispatch` 뿐이다. **`pull_request` 를 추가하지 않는다.** `workflow_run` 도 같은 레포 develop 에 push 된 커밋의 CI 만 받는다 (gate job 조건).
- Settings → Actions → General → Fork pull request workflows 를 **"Require approval for all external contributors"** 로 둔다. 외부 PR 의 워크플로는 메인테이너가 승인해야 돈다.
- Actions 로그는 공개다. 워크플로에서 `docker compose logs` 나 `.env` 를 출력하지 않는다. 실패 원인은 서버에서 본다.

## 7. 운영

```bash
cd ~/gitory/deploy
docker compose ps                              # 상태
docker compose logs backend --tail 100         # 로그 (ai · web · db 도 같은 방식)
docker compose restart backend                 # 서비스 하나 재시작
docker compose up -d --build --wait            # 수동 재배포 (자동 배포가 안 될 때)
```

- Session Manager 기본 셸이 `sh` 라서 접속하면 `bash` → `sudo -iu ubuntu` 를 먼저 실행한다.
- 여러 줄을 붙여넣으면 마지막 줄이 Enter 를 쳐야 실행돼 출력이 잘려 보인다. 블록 끝에 `echo "== 끝"` 을 두면 끝을 알 수 있다.
- 메모리: 4개 합계 약 650MB (backend 약 535MB). 빌드 중에는 더 쓰지만 swap 4GB 가 있다.
- 인스턴스를 stop/start 해도 EIP 라 주소가 유지된다. 컨테이너는 `restart: unless-stopped` 라 Docker 가 뜨면 같이 뜬다.

## 8. 팀과 정한 것

- 배포는 곽태훈이 맡고, 나머지 작업(수집 연결·후보·카드·평가·프론트)은 파트별로 나눴다.
- 서비스 간 계약 변경은 2인 승인, 워크플로 변경은 전용 PR 이다. 워크플로 PR 은 올린 사람이 PR checks 초록까지 확인한다 (멘토 규칙).

## 참고

- 호스팅 비교와 R-10 설명: 별도 문서 `HOSTING.md` (이 레포에는 없고 팀원 로컬에 있다).
- 테크스펙의 리스크 R-7(서비스 3개·저장소 3개·언어 3개)·R-10(교차 출처 쿠키).
