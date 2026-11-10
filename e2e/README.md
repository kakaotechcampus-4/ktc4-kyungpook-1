# E2E 시험

db 는 운영과 같은 이미지, ai·backend·web 은 운영과 같은 Dockerfile 로 띄우고, 바깥 서비스(GitHub 와 LLM)만 `fakes/server.mjs` 가짜로 바꿔 브라우저로 한 흐름을 끝까지 돌린다.

## 무엇을 시험하나

시나리오 1 (`tests/scenario-1.spec.ts`): GitHub 로그인 → 저장소 고르기 → 안내 확인 → 정리 시작 → 정리가 끝나면 추천 후보로 카드 초안이 이어서 만들어지고 레포 폴더로 감 → 상황·행동 직접 입력과 자동 저장(상황과 행동이 차면 서버가 카드를 스스로 확정) → 확정된 카드의 비어 있는 과제 칸 안내에서 질문 받고 답하기.

화면에서 누르는 것 말고 이런 것도 같이 본다.

- 로그인한 `SESSION` 쿠키가 HttpOnly 이고 `/api/me` 의 로그인이 가짜 사용자와 같다.
- 저장소 목록의 내 커밋·PR 수·등급·추천 여부(화면 글자와 API 둘 다)·센 시각이 채워져 있다(개수 세기 성공).
- 후보 제목이 가짜 LLM 이 만든 문장이다. 경험 그룹화가 AI 와 LLM 호출 경로를 실제로 지났다는 뜻이다.
- 가짜 저장소의 한 커밋 메시지에 `#000` 이 들어 있다. 이슈 번호 0 으로 읽혀 수집이 실패했던 사고의 재발을 매번 확인한다.
- 상황·행동을 저장하면 카드 상태가 `CONFIRMED` 가 되고, 확정된 뒤에 받은 되묻기 답이 카드의 새 버전에 반영되며 상태는 `CONFIRMED` 그대로다.
- 마지막에 가짜 GitHub 가 받은 요청(로그인 주소·아바타·LLM 요청은 빼고)의 `Authorization` 이 모두 로그인 때 받은 토큰이다. 다른 토큰이나 빈 토큰이 한 번도 없어야 한다.

시나리오 2 (`tests/scenario-2-github-reconnect.spec.ts`): 가짜 GitHub 이 저장소 읽기를 401 로 거절하는 동안 정리를 시작한다 → 정리가 `GITHUB_UNAUTHORIZED` 로 끝나고 `/api/me` 의 GitHub 연결이 끊김으로 바뀐다 → 실패 화면의 `다시 연결` 로 OAuth 를 다시 하면 정리 화면으로 돌아오고 연결이 살아난다 → 같은 레포를 다시 정리하면 끝까지 간다. 가짜 GitHub 이 받은 토큰이 모두 그 사용자의 것인지도 본다.

시나리오 3 (`tests/scenario-3-interview-skip.spec.ts`): 정리 뒤 자동으로 만든 카드에서 과제 칸 질문을 받고 `건너뛰기` → 화면에 넘긴 질문으로 남고 과제 칸을 다시 고를 수 없으며 다음 질문(Q2)은 다른 칸이다 → 과제 칸을 다시 요청해도 서버는 새로 만들지 않고 답을 기다리는 Q2 를 돌려주며, 카드의 과제 칸은 비어 있다.

시나리오 2·3 이 함께 쓰는 로그인·처음 입력 창·정리 시작 단계는 `tests/support.ts` 에 있다.

시험마다 새 로그인(`e2e-<시각>`)으로 들어가서 이전 실행이 남긴 저장소·카드와 섞이지 않는다.

## 돌리는 방법

필요한 것: Docker, Node 22 이상. 포트 8088(web)과 9000(가짜 서버)을 쓴다.

```bash
# 저장소 루트에서
docker compose -f e2e/compose.yaml -p gitory-e2e up -d --build --wait

cd e2e
npm ci
npx playwright install chromium
npm test

# 끝나면 반드시 내린다 (내리지 않으면 컨테이너가 8088·9000 포트를 계속 잡는다. DB 는 tmpfs 라 띄울 때마다 비어 있다)
cd ..
docker compose -f e2e/compose.yaml -p gitory-e2e down -v
```

가짜 서버의 상태(로그인 대기열·실패 주입·요청 기록)가 프로세스 하나에 있어서 시험은 한 번에 하나씩만 돌린다(`workers: 1`). 스택을 띄워 둔 채 다시 돌려도 된다.

실패하면 `test-results/` 에 trace 가 남는다. `npx playwright show-trace <trace.zip>` 으로 본다.

`scripts/smoke.sh`·`scripts/analyze.sh` 는 브라우저 없이 curl 로 같은 스택을 확인하는 빠른 점검이다. DB 를 직접 읽어 토큰 암호화와 `#000` 처리까지 본다.

## 가짜가 대신하는 것

| 대신하는 것 | 가짜 | 
|---|---|
| GitHub OAuth 로그인 | `/login/oauth/authorize` 가 바로 `code` 를 붙여 돌려보내고, 토큰에 로그인을 넣어(`gho_E2E_<login>`) 사용자를 가른다 |
| GitHub REST·GraphQL | 손으로 만든 저장소 `sample-app` 하나(커밋 10·PR 2·이슈 1). `fakes/fixtures/sample-app.json` |
| LLM | OpenAI 호환 `/chat/completions`. 경험 그룹화 두 단계만 답한다 |

## 시험하지 못하는 것

- 진짜 GitHub 와의 호환. 필드 이름·페이지 나누기·한도 응답은 우리가 읽는 부분만 흉내 낸다. 실제 GitHub 응답 모양 일부는 AI 의 `test_github_collector_live.py`(손 시험)와 백엔드의 `MockRestServiceServer` 시험이 본다.
- 운영 쿠키 설정. 시험 스택은 http 라 `Secure` 를 끈다(`SERVER_SERVLET_SESSION_COOKIE_SECURE=false`). 운영의 Secure 쿠키와 HTTPS 는 확인하지 않는다.
- 진짜 LLM 의 답. 후보 문장·설명 품질은 보지 않는다.
- AI 카드 초안(`CARD_AI_DRAFT_ENABLED`)은 꺼져 있다. 카드는 빈 1번 버전으로 시작한다.
- 배포(Caddy 의 HTTPS, CloudWatch 로그, DB 백업).
