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
                              ├─→ db (PostgreSQL 17)      ← 공개 포트 없음
                              └─→ redis (Redis 7)         ← 공개 포트 없음. 호출 제한 횟수만 센다
```

| 파일 | 역할 |
|---|---|
| `backend/Dockerfile` | JDK 로 bootJar 빌드 → JRE 이미지. 테스트는 CI 가 돌린다(`-x test`) |
| `ai/Dockerfile` | python 3.11-slim + uvicorn. `AI_DOCS_ENABLED=false` |
| `frontend/Dockerfile` | `VITE_API_MOCK=false` 로 빌드 → Caddy 이미지에 담는다 |
| `deploy/compose.yaml` | db · db-backup · ai · redis · backend · web. backend 는 db 가 healthy 여야 뜨고, web 은 backend 가 healthy 여야 뜬다. ai 가 안 떠도 backend·web 은 뜬다 (분석 Job 만 실패). redis 가 안 떠도 backend 는 뜨고 호출 제한만 건너뛴다 (아래 호출 제한) |
| `deploy/db-backup.sh` | 매일 DB 백업 (7절 DB 백업·복구) |
| `deploy/Caddyfile` | 경로 분기, HTTPS, 보안 헤더. `/actuator` 는 프록시하지 않는다 |
| `deploy/.env.example` | 필요한 비밀값 목록과 생성 방법. 서버의 `deploy/.env` 는 gitignore 대상 |

### 로그인 때문에 HTTPS · 같은 오리진이 필수다

`application.yml` 의 세션 쿠키가 `secure: true`, `same-site: lax` 다.

- http 로 배포하면 브라우저가 쿠키를 저장하지 않아 로그인이 안 된다.
- 프론트와 API 를 같은 오리진에 두면 `SameSite=Lax` 쿠키가 그대로 실리고 CORS 설정도 필요 없다.
- Caddy 가 `X-Forwarded-Proto/Host` 를 붙이고, Spring 의 `forward-headers-strategy: framework` 가 이걸 읽어 OAuth `redirect_uri` 를 `https://<도메인>/api/auth/github/callback` 으로 만든다.

### nginx 대신 Caddy 를 쓴 이유

경로 분기는 nginx 와 같다. 차이는 Let's Encrypt 인증서 발급·갱신이 자동이라 certbot·cron 구성이 필요 없다는 점이다. 인증서는 `caddy-data` 볼륨에 보관한다 — 지우면 재발급하는데 발급 횟수 제한에 걸릴 수 있다.

### 호출 제한 (Redis)

되묻기 질문·답 평가·카드 초안은 버튼을 누를 때마다 LLM 을 불러, 사용자별 1분·하루 한도를 넘으면 429 `RATE_LIMITED` 로 거절한다. 한도 숫자는 `backend/src/main/resources/application.yml` 의 `gitory.rate-limit.*` 이고 하루는 서울 자정에 바뀐다.

- 스위치는 서버 `deploy/.env` 의 `RATE_LIMIT_ENABLED` 다(기본 `false`). 429 안내 문구를 보여주는 프론트(#335)가 배포된 뒤 `true` 로 바꾸고 `docker compose up -d` 로 다시 띄운다. 서버 `.env` 에는 `.env.example` 의 새 줄이 자동으로 들어가지 않으니 줄을 직접 더한다. 백엔드 시험·로컬·E2E 는 꺼진 채 Redis 없이 돈다.
- 켜졌는지는 backend 로그 첫머리의 `LLM 호출 제한이 켜져 있다` 한 줄로 확인한다.
- redis 는 디스크에 저장하지 않는다(`--save "" --appendonly no`, `--maxmemory 64mb`). 다시 뜨면 숫자가 0 부터 시작해도 괜찮은 값이다.
- Redis 에 붙지 못하면 막지 않고 통과시키며 backend 에 `Redis 에 붙지 못해 … 호출 제한을 건너뛴다` WARN 을 남긴다. Redis 상태는 health 에 넣지 않아(`management.health.redis.enabled: false`) Redis 때문에 배포 확인이 실패하지 않는다.

## 4. 처음 띄우기 (서버에서 한 번)

Session Manager 로 접속한 뒤:

```bash
bash
sudo -iu ubuntu            # docker 그룹은 ubuntu 에만 있다 (Session Manager 기본 사용자는 ssm-user)
git clone https://github.com/kakaotechcampus-4/ktc4-kyungpook-1.git ~/gitory
cd ~/gitory/deploy && cp .env.example .env && chmod 600 .env
nano .env                  # 값 채우기 — 생성 방법은 .env.example 주석
mkdir -p ~/gitory-backups  # DB 백업 폴더. 없으면 Docker 가 root 소유로 만든다 (7절 DB 백업·복구)
docker compose up -d --build --wait && docker compose ps
echo "== 끝"
```

- `TOKEN_ENC_KEY`/`TOKEN_ENC_SALT` 는 한 번 정하면 바꾸지 않는다. 바꾸면 저장된 GitHub 토큰을 복호화할 수 없다.
- `DB_PASSWORD` 는 DB 볼륨이 처음 만들어질 때 고정된다. 나중에 `.env` 만 바꾸면 접속이 실패한다.
- GitHub OAuth App 의 Authorization callback URL 은 `https://<도메인>/api/auth/github/callback` 과 정확히 같아야 한다.
- `GITORY_LLM_BASE_URL`/`GITORY_LLM_API_KEY` 가 비어 있으면 AI 의 경험 그룹화·STAR 초안만 503(LLM 설정 없음)이고, 수집·되묻기는 동작한다. 값을 넣은 뒤 `ANALYSIS_GROUPING_ENABLED=true` 로 바꾸고 `docker compose up -d` 로 다시 띄운다. 켜기 전에 넣지 않으면 모든 분석이 그룹화 단계에서 실패한다.
- `CARD_AI_DRAFT_ENABLED=true` 는 후보로 만든 카드의 초안 Job 이 AI diff 근거·STAR 초안으로 칸을 채우게 한다. LLM 값이 없으면 모든 초안이 실패하므로 위 LLM 값을 넣고 확인한 뒤 켠다. 서버 `deploy/.env` 는 처음 만든 뒤 `.env.example` 에 생긴 줄이 자동으로 들어가지 않으니, 줄이 있는지 먼저 확인하고 없으면 더한다.
- 카드 제목·기간 수정은 프론트 빌드 값 `VITE_CARD_METADATA_ENABLED`(`frontend/Dockerfile`)로 켠다. 서버 `.env` 가 아니라 이미지에 들어가므로 바꾸면 web 이미지를 다시 빌드해야 한다.

## 5. Spring ↔ AI 토큰 경로 (멘토 확인 완료)

Spring 이 복호화한 GitHub 토큰을 `X-GitHub-Token` 헤더로 AI 서버에 넘긴다. Spring·AI 를 별도 서버로 나누면 이 구간이 공개망을 지날 수 있다는 지적이 PR #65 리뷰에서 나왔다.

- 같은 EC2 의 Docker 네트워크 안에서만 통신하면 토큰이 공개망을 지나지 않는다. **멘토가 이 방향이 맞다고 확인했다** (PR #74 리뷰).
- 구성 후 확인: 외부에서 AI 8000 포트에 접근할 수 없다 (10/5).
- 남은 확인: 실제 분석 실행 시 AI 로그에 `POST /internal/collect` 가 찍히고, 토큰 값이 어느 로그에도 남지 않는지.

## 6. 자동 배포

`develop` 에 머지된 커밋의 CI(backend CI · AI CI · frontend)가 **모두 성공하면** `.github/workflows/deploy.yml` 이 서버에서 돈다. 이미지 빌드는 테스트를 건너뛰므로(`-x test`) CI 가 실패한 커밋은 배포하지 않는다.

0. gate job 이 그 커밋의 CI 를 모두 확인한다. 아직 도는 CI 가 있으면 넘기고 마지막 CI 가 끝날 때 배포한다. 실패한 CI 가 있으면 배포 워크플로도 실패로 표시된다
1. 서버의 `~/gitory` 를 그 커밋으로 맞춘다 (`git checkout --force --detach <sha>` — `deploy/.env` 는 gitignore 라 남는다). 바꾸기 전에 지금 돌던 커밋을 적어 둔다. 서버에 이미 같거나 더 새 커밋이 있으면 되돌리지 않고 넘긴다
2. `docker compose up -d --build --wait` — 모든 서비스가 healthy 가 될 때까지 기다린다. 5분 안에 안 되면 실패
3. 2 가 실패하면 1 에서 적어 둔 직전 커밋으로 되돌려 같은 방식으로 다시 올린다. 배포 워크플로는 실패로 남는다. 코드만 되돌리므로 이미 적용된 새 마이그레이션은 DB 에 그대로 남고, 칸을 지우거나 이름을 바꾼 마이그레이션이었다면 직전 코드도 뜨지 못할 수 있다
4. 컨테이너 상태를 출력하고, 성공하면 오래된 이미지·빌드 캐시를 정리한다

Actions 탭 → deploy → **Run workflow** 로 수동 실행도 된다 (develop 에서만, develop 최신 커밋). `deploy/` 만 바뀐 커밋은 CI 가 없어 자동 배포되지 않으므로 수동 실행한다. 수동 실행도 CI 를 확인한다 (멘토 리뷰 #108). 최신 커밋만 보면 CI 가 없는 `deploy/` 커밋이 바로 앞의 CI 실패 커밋을 덮어 함께 올라가므로, backend CI · AI CI · frontend 마다 develop 의 가장 최근 실행을 보고 모두 성공했을 때만 배포한다. 실패했거나 아직 도는 CI 가 있으면 배포하지 않는다.

마이그레이션에서 칸·표를 지우거나 이름을 바꿀 때는 두 번에 나눠 머지한다. 먼저 코드가 그 칸을 더 쓰지 않게 바꿔 배포하고, 그다음 PR 에서 지운다. 한 번에 넣으면 3 의 되돌리기로 올린 직전 코드가 없어진 칸을 찾다 뜨지 못한다.

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

### 로그 모아 보기 (CloudWatch)

컨테이너 5개의 로그는 Docker `awslogs` 드라이버로 CloudWatch 로그 그룹 `/gitory`(서울 리전)에 간다. 스트림은 컨테이너 이름(`gitory-backend-1` 등)이다. EC2 인스턴스 역할(`ktc-ec2-ssm-profile`, `CloudWatchAgentServerPolicy` 연결)로 보내므로 서버에 키를 두지 않는다. 서버의 `docker compose logs` 도 그대로 된다.

- 보기: CloudWatch 콘솔 → 로그 그룹 → `/gitory` → 스트림 선택. 여러 컨테이너를 한 번에 찾을 때는 Logs Insights 에서 `/gitory` 를 고르고 `fields @timestamp, @logStream, @message | filter @message like /ERROR|Traceback/ | sort @timestamp desc` 를 실행한다.
- 보관: 로그 그룹 `/gitory` → 작업 → 보존 설정 편집 → 7일 (한 번만).
- 에러 알람 (한 번만):
  1. `/gitory` → 지표 필터 생성을 아래 패턴 4개로 네 번 한다. 넷 다 지표 이름 `GitoryErrors`, 지표 값 1, 기본값 0 으로 같은 지표에 더한다. 만들 때마다 같은 화면의 "패턴 테스트"로 아래 예시 줄에 맞는지 확인한다.
     - `ERROR -"duplicate key"`: 에러 줄. 동시 요청을 DB 유니크 제약으로 막는 정상 흐름(분석 접수 등 4곳)에서 db 와 backend 가 남기는 `ERROR: duplicate key ...` 는 뺀다.
     - `Traceback`: ai 의 예외 스택.
     - `?"INTERNAL_ERROR" ?"GITHUB_UNAVAILABLE"`: Job 이 실패로 끝날 때 backend 가 WARN 으로 남기는 에러 코드.
     - `"DB 백업 실패"`: db-backup 의 실패 줄 (아래 DB 백업·복구).
     - CloudWatch 패턴은 `?`(또는)와 `-`(빼기)를 한 패턴에 같이 쓰면 `?` 항목이 무시되므로, 하나로 합치지 않고 나눈다. 한 줄이 여러 필터에 걸려 두 번 세어져도 경보는 1 이상이라 결과는 같다.
     - 패턴 테스트에 넣을 줄 모양 (앞 날짜는 생략). 걸려야 하는 줄: `ERROR 1 --- [gitory-backend] [   scheduling-1] c.g.b.job.domain.JobRunner : ...`, `Traceback (most recent call last):`, `카드 초안 Job 12 을 INTERNAL_ERROR 로 끝낸다`, `DB 백업 실패: pg_dump: error: connection to server at "db" (172.18.0.2), port 5432 failed: ...`
     - 걸리면 안 되는 줄: `UTC [123] ERROR:  duplicate key value violates unique constraint "uq_job_active"`(db), `ERROR 1 --- [gitory-backend] [nio-8080-exec-3] o.h.engine.jdbc.spi.SqlExceptionHelper : ERROR: duplicate key value violates unique constraint "uq_job_active"`(backend)
  2. CloudWatch → 경보 생성 → 지표 `GitoryErrors` 합계, 5분, 1 이상 → SNS 주제를 새로 만들어 팀 이메일을 구독시키고 메일의 확인 링크를 누른다.
- 알람을 받으면 Logs Insights 에서 그 시각 전후로 `@logStream` 별로 본다. backend 예외 스택은 날짜로 시작하지 않는 줄을 앞 줄에 묶어 한 이벤트로 보낸다.
- CloudWatch 가 느리거나 끊겨도 서비스는 멈추지 않는다(`mode: non-blocking`). 대신 버퍼(4MB)가 차면 그 사이 로그는 CloudWatch 에 빠진다.

### DB 백업·복구

운영 DB 는 Docker 볼륨 `db-data` 하나에만 있다. `db-backup` 컨테이너(`deploy/db-backup.sh`)가 매일 UTC 19:00(한국 04:00)에 `pg_dump -Fc` 로 백업한다.

- 위치: 서버 `~/gitory-backups/gitory-YYYY-MM-DD-HHMM.dump` (시각은 UTC). 리포 밖이라 배포가 건드리지 않는다. 폴더가 없으면 Docker 가 root 소유로 만드니 4절처럼 먼저 만든다.
- 보관: 백업이 성공할 때마다 7일 가까이(6일 23시간) 지난 `gitory-*.dump` 를 지워 7개를 남긴다 (손으로 만든 백업도 같다). 실패가 이어지는 동안은 지우지 않는다. 다른 이름의 파일은 건드리지 않는다.
- 실패: 임시 이름(`.gitory-….part`)으로 쓰고 성공해야 진짜 이름으로 바꾼다. 실패하면 임시 파일을 지우고 `DB 백업 실패: <원인>` 한 줄을 남긴 뒤 다음 날 다시 한다. 표 잠금은 10분까지만 기다린다. 성공은 `DB 백업 성공: <파일> (<크기>KB)`.
- 확인: `ls -lh ~/gitory-backups` 로 날마다 하나씩 쌓이는지, `docker compose logs db-backup --tail 20` 또는 CloudWatch 스트림 `gitory-db-backup-1` 로 성공 줄을 본다. 실패는 위 에러 알람(`"DB 백업 실패"` 패턴)으로 온다. 처음 배포한 뒤에는 아래 손 백업을 한 번 돌려 접속·폴더를 확인한다.
- 손으로 한 번 백업 (같은 스크립트로 지금 바로): `docker compose exec -T db-backup sh /usr/local/bin/db-backup.sh once </dev/null`. 결과 줄은 터미널에만 나오고 CloudWatch 에는 남지 않는다. 같은 분에 이미 백업이 있으면 덮어쓰지 않고 다음 분까지 기다린다.
- `db-backup.sh` 를 바꿔 배포하면 `docker compose up -d --force-recreate db-backup` 을 한 번 한다. 파일 하나를 연결한 컨테이너는 git checkout 뒤에도 옛 내용을 본다.
- 이 절의 `docker compose exec` 에는 `-T` 와 `</dev/null` 을 함께 붙인다. `-T` 가 없으면 서버 터미널에서 `cannot attach stdin to a TTY-enabled container` 로 실패하고, `</dev/null` 이 없으면 붙여넣은 뒤 줄을 입력으로 가져가 실행되지 않는다.

#### 복원 확인

백업이 실제로 되살아나는지 임시 DB 에 복원해 운영 DB 와 표마다 행 수를 비교한다. 운영 DB 는 건드리지 않는다.

```bash
cd ~/gitory/deploy
docker compose exec -T db-backup sh /usr/local/bin/db-backup.sh once </dev/null   # 비교가 맞도록 지금 상태로 백업
latest=$(ls -t ~/gitory-backups/gitory-*.dump | head -1) && echo "$latest"
docker rm -f -v gitory-restore-check 2>/dev/null                               # 지난번에 끊겨 남은 것
docker run -d --name gitory-restore-check -e POSTGRES_USER=gitory -e POSTGRES_DB=gitory -e POSTGRES_PASSWORD=check postgres:17-alpine
for i in $(seq 60); do docker exec gitory-restore-check pg_isready -q -h 127.0.0.1 -U gitory && break; sleep 1; done
docker exec -i gitory-restore-check pg_restore -U gitory -d gitory --exit-on-error < "$latest" && echo "== 복원 성공"
count_rows() {
  docker exec -i "$1" psql -U gitory -d gitory -At <<'SQL' | sort
SELECT string_agg(format('SELECT %L, count(*) FROM public.%I', tablename, tablename), ' UNION ALL ')
FROM pg_tables WHERE schemaname = 'public' \gexec
SQL
}
diff <(count_rows gitory-db-1) <(count_rows gitory-restore-check) && echo "== 행 수 모두 같음"
docker rm -f -v gitory-restore-check                                           # -v: 임시 DB 의 익명 볼륨까지 지운다
echo "== 끝"
```

- `== 복원 성공` 과 `== 행 수 모두 같음` 이 둘 다 나와야 한다. 백업과 비교 사이에 사용자가 쓰면 그 표만 다를 수 있다 (로그인 세션 `spring_session*` 이 흔하다).
- `pg_isready` 는 `-h 127.0.0.1` 로 본다. 첫 기동 때 잠깐 뜨는 초기화용 서버는 TCP 를 열지 않아 끝난 뒤에야 성공한다.

#### 사고 때 운영 DB 되살리기

되살리는 동안 develop 에 머지하지 않게 팀에 알린다 (자동 배포가 backend 를 다시 띄운다). 먼저 쓰기를 막고(backend·ai 멈춤, 그동안 화면은 에러) 지금 상태를 한 번 더 남긴 뒤 목록을 본다. 멈춘 뒤에 남겨야 멈추기 직전까지 저장된 것이 다 담긴다.

```bash
cd ~/gitory/deploy
docker compose stop backend ai \
  && docker compose exec -T db-backup sh /usr/local/bin/db-backup.sh once </dev/null \
  && ls -lt ~/gitory-backups
```

`DB 백업 성공` 이 나와야 다음으로 간다. 목록 맨 위는 방금 만든 백업이니 되살릴 파일로 고르지 않는다. 되살리지 않기로 했으면 `docker compose up -d --wait` 로 다시 띄운다. 아래 `f` 를 되살릴 파일로 고친 뒤 실행한다. DB 를 지우고 다시 만들어 복원 → 다시 시작한다(backend·ai 를 한 번 더 멈추는 줄은 이 블록만 따로 돌려도 안전하게 하려는 것이다). 한 단계라도 실패하면 거기서 멈춘다.

```bash
cd ~/gitory/deploy
f=~/gitory-backups/gitory-YYYY-MM-DD-HHMM.dump
[ -s "$f" ] && echo "되살릴 파일: $f" \
  && docker compose stop backend ai \
  && docker compose exec -T db dropdb -U gitory --force gitory </dev/null \
  && docker compose exec -T db createdb -U gitory gitory </dev/null \
  && docker compose exec -T db pg_restore -U gitory -d gitory --exit-on-error < "$f" \
  && docker compose up -d --wait && docker compose ps \
  && echo "== 끝" || echo "== 멈춤: 파일 이름이나 위 에러를 고친 뒤 이 블록을 다시 실행한다 (backend·ai 는 멈춘 채일 수 있다)"
```

- `pg_restore --clean` 대신 DB 를 지우고 다시 만든다. `--clean` 은 백업에 있는 표만 지워서, 백업 뒤 마이그레이션으로 생긴 표가 남고 Flyway 가 그 표를 다시 만들다 실패한다.
- 확인: `docker compose logs backend --tail 50` 에 Flyway 에러가 없고, 로그인해 저장소·카드가 보이면 된다. 백업 뒤 머지된 마이그레이션은 backend 가 뜰 때 다시 적용된다.
- 이번에 하지 않은 것: 서버 밖(S3) 복사는 인스턴스 역할에 S3 권한이 없어 하지 않았다 (서버 자체를 잃는 경우는 나중에 AWS 스냅샷으로). 배포 직전 백업은 CI 변경이라 따로 한다.

## 8. 팀과 정한 것

- 배포는 곽태훈이 맡고, 나머지 작업(수집 연결·후보·카드·평가·프론트)은 파트별로 나눴다.
- 서비스 간 계약 변경은 2인 승인, 워크플로 변경은 전용 PR 이다. 워크플로 PR 은 올린 사람이 PR checks 초록까지 확인한다 (멘토 규칙).

## 참고

- 호스팅 비교와 R-10 설명: 별도 문서 `HOSTING.md` (이 레포에는 없고 팀원 로컬에 있다).
- 테크스펙의 리스크 R-7(서비스 3개·저장소 3개·언어 3개)·R-10(교차 출처 쿠키).
