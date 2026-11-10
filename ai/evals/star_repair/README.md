# STAR 검토·고쳐 쓰기 측정 (#473)

다른 팀 PR 본문·커밋 메시지와 만든 카드 원문은 저장소에 두지 않는다. 저장소에는 PR 번호 목록(`prs_refs.json`·`thin_refs.json`)만 있고, 원문은 `select_prs.py --from-refs` 가 `ai/evals/results/star_repair/`(git 이 무시)에 받는다. 숫자만 `ai/evals/RESULTS.md` 에 적는다.

## 표본
- `prs_refs.json` — `select_prs.py` 로 고름(seed 20261028). 우리 14(#244 문서만·#296 build.gradle 만은 제품도 후보로 안 만들어 `rejected`) + 다른 팀 86(23개 리포 × 최대 5).
- 1차 50개 = 우리 14 + 다른 팀 앞쪽 36(`first_batch`). 검토 모델 '잡은 비율' 이 55~85% 면 나머지 50개를 더 돈다.
- `thin_refs.json` — 본문이 100자 미만이고 자료(연결 이슈·남의 리뷰·주인 답 댓글)가 있는 PR 12개(#478).
- `grading/first_draft_keys.json` — 첫 초안 200칸의 '뜻이 뒤집힘' 정답표(채점 기준 `grading/RUBRIC.md`).

## 성공 기준 (2026-10-28 합의)
- 사전 측정(검토 모델 고르기): 뒤집힌 칸 중 70% 이상 잡음 · 멀쩡한 칸 잘못 짚음 10% 이하 · 둘을 넘는 것 중 가장 싼 모델.
  채점자가 Claude 라 Opus 가 유리할 수 있어, GPT 쪽과 차이가 작으면 Opus 를 고르는 근거로 쓰지 않는다.
- 루프(같은 첫 초안 → 최종본, 짝지어 비교): ① 뒤집힌 칸 줄어듦 ② 고쳐 쓰다 새로 망가진 칸 0 ③ 충실도 첫 초안 이상
  ④ 빈 칸·근거 없는 숫자 안 늘어남 ⑤ 카드당 늘어난 호출·비용·시간 기록, 시간 +20초 이내.
- 예산: 팀 크레딧 월 120,000원(매월 29일 초기화). 100개 × 후보 4개 전부 약 1만~1만 5천 원 예상.

## 순서 (`ai/` 에서, 키는 환경변수나 `GITORY_EVAL_KEY_FILE`)
1. `python evals/star_repair/select_prs.py --from-refs evals/star_repair/prs_refs.json` — 표본 원문 받기(gh 로그인 필요)
2. `python evals/star_repair/measure.py smoke` — 후보 4개가 불리는지(추론 강도 거절이면 `GITORY_STAR_REVIEW_REASONING_EFFORT` 조정)
3. `python evals/star_repair/measure.py drafts` — diff 근거·첫 초안
4. 채점: `build_packets.py` 로 묶음을 만들고 `grading/RUBRIC.md` 기준으로 칸마다 뒤집힘 여부를 채점(원문 인용)
5. `python evals/star_repair/measure.py review` — 후보별 검토, 채점과 견줘 기준 판정
6. `python evals/star_repair/measure.py repair --review-model <고른 모델>` 후 `analyze_repair.py <이름>` — 바뀐 칸만 다시 채점
7. RESULTS.md 한 줄

## 자료 열기 (#478)
- `python evals/star_repair/measure.py materials` — 키 없이 gh api 로 자료를 모은다.
- `python evals/star_repair/measure.py repair --review-model none --with-materials` — 같은 첫 초안에서 자료를 열 수 있게 한 루프. 본문 짧은 표본은 `select_prs.py --from-refs evals/star_repair/thin_refs.json --output evals/results/star_repair/thin` 로 받고 `--set thin` 을 붙여 돌린 뒤 `analyze_thin.py` 로 견준다.
- 기준: 자료를 연 칸에서 빈 칸·근거 없는 문장이 줄어듦 · 남의 리뷰·이슈 목표가 A·R 성과로 옮겨진 칸 0 · 카드당 늘어난 호출·시간 기록.

## 그 밖의 측정
- `cap_run.py` — 출력 상한 4,000 으로 1차 50개를 두 번 만들어 실패 수·토큰을 잰다.
- `body_limit.py` — PR 본문 상한 1,000자 ↔ 3,000자 첫 초안 비교.
- 두 스크립트는 `measure.py drafts` 가 만든 diff 근거를 읽는다. `--dry-run` 으로 대상 수만 먼저 볼 수 있다.
