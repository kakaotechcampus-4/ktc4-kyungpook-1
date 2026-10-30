# 협업 규칙

백엔드·프론트엔드·AI 파트가 함께 지키는 규칙입니다. 저장소 설정·템플릿·CI 에서 확인한 것만 적었습니다.
확인하지 못한 것은 `[확인 필요]` 로 표시했습니다. 서비스 소개는 [프로젝트 README](README.md), 문서 목록은 [개발 문서 모음](docs/README.md)을 보세요.

## 1. 기본 작업 흐름

1. 이슈를 만듭니다.
2. `develop` 에서 브랜치를 땁니다.
3. 작은 단위로 커밋합니다.
4. 바꾼 파트의 시험을 로컬에서 돌립니다.
5. `develop` 으로 PR 을 열고 리뷰를 받습니다.
6. 필수 체크가 모두 초록이고 승인을 받으면 머지합니다.

`develop` 이 기본 브랜치입니다. `develop` 에는 PR 로만 들어갑니다(아래 3절).
`main` 은 멘토 리뷰용입니다. 정해진 때에 `develop` → `main` PR 을 엽니다.

## 2. 브랜치

형식은 `<종류>/<짧은 설명>` 입니다. 설명 앞에 파트를 붙이면 찾기 쉽습니다.

| 종류 | 쓰는 곳 | 실제 예 |
| --- | --- | --- |
| `feature/` | 기능 추가 | `feature/be-card-delete`, `feature/ai-card-tags`, `feature/fe-cover-letter` |
| `fix/` | 버그 수정 | `fix/fe-login-expired-notice`, `fix/ai-pin-fastapi` |
| `docs/` | 문서 | `docs/interview-loop-contract` |
| `refactor/` · `test/` · `chore/` | 구조 정리 · 시험 · 잡일 | `refactor/evidence-sha-matcher` |
| `ci/` · `infra/` | CI · 배포 | `ci/migration-order-check` |

- 파트 표기는 `be-` · `fe-` · `ai-` 를 씁니다. 지금 원격 브랜치 151개 중 56개에 붙어 있습니다.
- 이슈 번호는 브랜치 이름에 넣지 않아도 됩니다. PR 의 Development 칸으로 잇습니다.
- `codex/` 로 시작하는 브랜치도 있습니다. 도구가 만든 이름입니다. 새 브랜치에는 위 종류를 씁니다. [확인 필요]

## 3. 브랜치 보호와 머지 조건

`gh api` 로 2026-10-25 에 확인한 값입니다.

| 항목 | `develop` (branch protection) | `main` (ruleset `main-protection`) |
| --- | --- | --- |
| PR 없이 push | 막힘 (관리자도 막힘, `enforce_admins`) | 막힘 |
| 필요한 승인 | 1명 | 1명 + CODEOWNERS 승인 |
| 마지막 push 뒤 다시 승인 | 필요 (`require_last_push_approval`) | 필요 없음 |
| 새 push 뒤 기존 승인 | 그대로 남음 | 그대로 남음 |
| 필수 체크 | 5개 (아래) | 없음 |
| 강제 push · 브랜치 삭제 | 막힘 | 막힘 |
| 규칙을 건너뛸 수 있는 쪽 | 없음 | 조직 관리자, 운영 팀 1개 |

`develop` 의 필수 체크 5개입니다. PR 마다 항상 돌고, 그 파트를 안 바꾼 PR 에서는 건너뛰어 통과로 칩니다.

| 체크 이름 | 워크플로 |
| --- | --- |
| `빌드 · 테스트 · 경계 검사` | [backend CI](.github/workflows/backend-ci.yml) |
| `마이그레이션 검사` | [backend CI](.github/workflows/backend-ci.yml) |
| `마이그레이션 번호 순서 검사` | [backend CI](.github/workflows/backend-ci.yml) |
| `구문 검사 · 테스트` | [AI CI](.github/workflows/ai-ci.yml) |
| `verify (22)` | [frontend](.github/workflows/frontend-ci.yml) |

- `verify (24)` 는 다음 Node 버전을 미리 보는 체크라 막지 않습니다.
- [e2e](.github/workflows/e2e.yml) 는 필수 체크도 배포 조건도 아닙니다.
- 체크의 job 이름을 바꾸면 필수 체크 설정도 같이 바꿔야 합니다.
- 머지 방식은 merge commit 하나만 켜져 있습니다. squash·rebase 는 꺼져 있습니다.
- `develop` 에 머지되면 [deploy](.github/workflows/deploy.yml) 가 그 커밋의 CI 가 모두 성공했을 때 운영 서버에 배포합니다.

## 4. 커밋

```text
<종류>(<파트>): <요약>
```

- 종류: `feat` · `fix` · `docs` · `test` · `refactor` · `chore` · `ci` · `infra`
- 파트(선택): `backend` · `fe` · `ai` · `deploy` 등. 최근 커밋 100개 중 30개만 파트를 붙였습니다.
- 요약은 한국어로, 무엇이 어떻게 바뀌는지 씁니다.

```text
feat(backend): 로켓펀치 Open API 로 개발 공고 가져오기
fix(ai): 답 길이 상한이 게이트웨이 한도를 넘는 호출은 스트리밍으로 받기
test: OpenAPI 스냅샷이 코드와 다르면 BE 시험이 실패하게 하기
```

- 한 커밋에는 한 가지 변경만 담습니다.
- `.env` 와 비밀값은 커밋하지 않습니다. `.gitignore` 가 `.env`·`.env.*` 를 막고 `.env.example` 만 올립니다.

## 5. 이슈

[기능](.github/ISSUE_TEMPLATE/feature.yml) · [버그](.github/ISSUE_TEMPLATE/bug.yml) 템플릿으로 씁니다.

- 기능: 어떤 문제를 푸는가 · 범위(한다/안 한다) · 계약 변경 · 제품 결정이 필요한가
- 버그: 무슨 일이 있었나 · 재현 방법 · 근거 · 어디
- 버그의 근거 칸에는 Job ID·시각·커밋 sha 처럼 쫓아갈 수 있는 값만 적습니다. 저장소 이름·카드 본문·답변 원문은 적지 않습니다.
- 스펙에 없거나 어긋나는 변경은 [gitory-docs](https://github.com/kakaotechcampus-gitory/gitory-docs) 의 ADR 이 먼저입니다.

제목은 `[파트] 할 일` 로 씁니다. 파트 라벨(`BE` · `FE` · `AI` · `Infra`)도 붙입니다.

```text
[BE] 매칭 목록 속도 재기와 개수 제한·필터를 서버에서 하기
[BE·FE] 되묻기 최대 질문 수를 3회에서 늘리기
[docs] 팀 그라운드 룰 문서로 남기기
```

## 6. PR

제목은 커밋과 같은 형식입니다. 파트를 꼭 붙입니다.

```text
feat(ai): 자소서에 쓸 수 없는 작업은 경험 후보로 만들지 않기
fix(frontend): 처음 방문한 사람에게도 로그인 만료 문구가 보이는 문제 수정
```

파트가 없으면 매일 저녁 Discord 로 가는 PR 요약([daily-pr-digest](.github/workflows/daily-pr-digest.yml))에서 `[기타]` 로 나옵니다.
요약은 `ai…` → AI, `be`·`backend…` → BE, `fe`·`frontend…` → FE 로 나눕니다.

본문은 [PR 템플릿](.github/pull_request_template.md)의 절을 채워 씁니다.

- 작업 요약 · 관련 이슈 · 리뷰 포인트 · 고민한 점 · 질문 · 참고문서/작성문서
- "새 라이브러리" 절과 "백엔드 변경" 절은 해당할 때만 남기고, 아니면 지웁니다.
- `gh pr create --fill` 은 쓰지 않습니다. 커밋 메시지만 들어가 템플릿 절이 빠집니다.
- 오른쪽의 Reviewers · Assignees(본인) · Development(해결한 이슈)를 채웁니다.

### 멘토 리뷰 PR (`develop` → `main`)

- base 가 `main` 인지 확인합니다. GitHub 이 `develop` 으로 미리 채웁니다.
- draft 가 아닌 `main` PR 을 열면 [assign-mentor](.github/workflows/assign-mentor.yml) 가 멘토를 지정하고, [notify-discord](.github/workflows/notify-discord.yml) 가 Discord 에 알리며, [convention-check](.github/workflows/convention-check.yml) 가 컨벤션 안내 댓글을 답니다.
- 이 PR 이 열려 있는 동안에는 `develop` 에 머지하지 않습니다. 머지하면 멘토가 보는 diff 가 바뀝니다.

## 7. PR 크기

- PR 하나에는 목적 하나만 담습니다.
- 파트를 넘는 기능은 파트별 PR 로 나눕니다. 되묻기 루프는 계약 문서 PR(`docs(contract): 되묻기 루프 화면·AI 계약 정하기`)을 먼저 머지하고 BE·AI·FE PR 을 따로 올렸습니다.
- 줄 수 기준은 정하지 않았습니다. [확인 필요]

## 8. 시험

PR 을 열기 전에 바꾼 파트의 시험을 돌립니다. CI 와 같은 명령입니다.

| 파트 | 폴더 | 명령 |
| --- | --- | --- |
| Backend | `backend/` | `./gradlew build` |
| AI | `ai/` | `python -m pip install -r requirements-dev.txt` 뒤 `python -m pytest -q` |
| Frontend | `frontend/` | `npm ci` 뒤 `npm run typecheck`, `npm test`, `npm run e2e:static`, `npm run e2e:mobile`, `npm run e2e:real` |
| E2E | 루트·`e2e/` | [E2E README](e2e/README.md)의 "돌리는 방법" (Docker 필요) |

자세한 내용은 [Frontend README](frontend/README.md), [AI README](ai/README.md), [E2E README](e2e/README.md)를 보세요.

새 라이브러리를 넣으면 우리가 쓰는 기능만 확인하는 시험을 같이 올립니다. 그 설정을 끄면 실패하는지도 한 번 봅니다(PR 템플릿).

## 9. 계약과 마이그레이션

- API 모양을 바꾸면 [BACKEND_CONTRACT.md](frontend/docs/BACKEND_CONTRACT.md)와 [OpenAPI 스냅샷](docs/openapi/README.md)을 같은 PR 에서 고칩니다. 스냅샷이 코드와 다르면 BE·AI 시험이 실패합니다.
- 계약의 코드 쪽 단일 출처는 [`frontend/src/api/schemas.ts`](frontend/src/api/schemas.ts)입니다.
- 파트 사이 요청·응답 예시는 [contracts](contracts/README.md)에 둡니다.
- DB 를 바꾸면 새 `V<n>__*.sql` 을 추가합니다. 이미 머지된 마이그레이션 파일은 고치지 않습니다. `마이그레이션 검사` 가 막습니다.
- 새 번호는 `develop` 의 가장 큰 번호보다 커야 합니다. `마이그레이션 번호 순서 검사` 가 막습니다.
- 다른 모듈의 `infra` 를 참조하거나 `evidence`·`recommend` 에서 `agent`(LLM)를 부르면 `ModuleBoundaryTest` 가 실패합니다. 모듈 구조는 [백엔드 아키텍처](backend/ARCHITECTURE.md)를 보세요.

## 10. 리뷰

리뷰어가 먼저 볼 것입니다. PR 템플릿과 각 문서에서 모았습니다.

- 필수 체크가 초록인가
- API 계약이 바뀌었다면 계약 문서와 스냅샷도 바뀌었나
- 이미 머지된 마이그레이션을 고치지 않았나
- LLM 을 부르는 곳이라면 [가드레일](docs/GUARDRAILS.md)의 장치를 지키나
- 로그·오류 응답·시험 자료에 토큰·원문이 남지 않나

`develop` 은 마지막 push 를 다른 사람이 승인해야 머지됩니다. 승인 뒤 새 커밋을 올리면 다시 승인을 받습니다.

### 9/1 테크스펙에서 합의한 규칙 [확인 필요]

9/1 테크스펙에서 정한 규칙입니다. 지금도 이대로 지키는지 팀이 확인해야 합니다.

1. 한 패키지를 두 사람이 동시에 만지지 않습니다.
2. 같은 파트 두 사람은 서로의 PR 을 리뷰합니다.
3. 서비스 사이 계약을 바꾸는 PR 은 AI 리드와 BE 리드가 둘 다 승인합니다.
4. 리드는 자기 파트의 통합을 책임집니다.
5. 평가 케이스를 만드는 사람과 판정하는 사람을 나눕니다.

[CODEOWNERS](.github/CODEOWNERS)의 팀 자유 영역에는 아직 파트별 리뷰어가 없습니다. 위 2·3번은 사람이 챙깁니다.

## 11. 회의와 소통

[확인 필요 — 팀이 채울 것] 정기 회의, 소통 채널, 막혔을 때 묻는 방법, 응답 기대 시간.

저장소에서 확인한 것은 하나입니다. 매일 한국시간 20:47 에 Discord 로 PR 요약이 갑니다. 새 PR, 24시간 넘게 승인 없이 기다리는 PR, 24시간 동안 머지된 PR 이 나옵니다.

## 12. 하지 않는 것

- `develop`·`main` 에 직접 push
- `.env`, 토큰, 키 파일 커밋
- 이미 머지된 Flyway 마이그레이션 수정
- 계약 문서·OpenAPI 스냅샷 없이 API 응답 칸 삭제·타입 변경
- OpenAPI JSON 을 손으로 고치기 (코드를 바꾸고 다시 만듭니다)
- 멘토 리뷰 PR 이 열려 있는 동안 `develop` 머지
- CODEOWNERS 의 운영진 영역 4줄과 `assign-mentor`·`notify-discord`·`convention-check` 워크플로 수정
- 이슈·로그·PR 에 사용자 저장소 이름, 카드 본문, 답변 원문 남기기
