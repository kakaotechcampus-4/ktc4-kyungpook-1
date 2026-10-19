# E2E 시험

db 는 운영과 같은 이미지, ai·backend·web 은 운영과 같은 Dockerfile 로 띄우고, 바깥 서비스(GitHub 와 LLM)만 `fakes/server.mjs` 가짜로 바꿔 브라우저로 한 흐름을 끝까지 돌린다.

## 무엇을 시험하나

시나리오 1 (`tests/scenario-1.spec.ts`): GitHub 로그인 → 저장소 고르기 → 안내 확인 → 정리 시작 → 후보 보드 → 카드 만들기 → 상황·행동 직접 입력과 자동 저장 → 비어 있는 과제 칸의 되묻기 질문 받고 답하기 → 카드 확정.

화면에서 누르는 것 말고 이런 것도 같이 본다.

- 로그인한 `SESSION` 쿠키가 HttpOnly 이고 `/api/me` 의 로그인이 가짜 사용자와 같다.
- 저장소 목록의 내 커밋·PR 수·등급·추천 여부(화면 글자와 API 둘 다)·센 시각이 채워져 있다(개수 세기 성공).
- 후보 제목이 가짜 LLM 이 만든 문장이다. 경험 그룹화가 AI 와 LLM 호출 경로를 실제로 지났다는 뜻이다.
- 가짜 저장소의 한 커밋 메시지에 `#000` 이 들어 있다. 이슈 번호 0 으로 읽혀 수집이 실패했던 사고의 재발을 매번 확인한다.
- 답변이 카드의 새 버전에 반영되고 카드 상태가 `CONFIRMED` 가 된다.
- 마지막에 가짜 GitHub 가 받은 요청(로그인 주소·아바타·LLM 요청은 빼고)의 `Authorization` 이 모두 로그인 때 받은 토큰이다. 다른 토큰이나 빈 토큰이 한 번도 없어야 한다.

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
