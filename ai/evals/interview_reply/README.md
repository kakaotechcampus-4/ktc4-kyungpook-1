# 되묻기 답 처리 평가

`POST /internal/interview-replies` 가 사용자 답을 읽고 APPLY·ASK_MORE·OFF_TOPIC·DONT_KNOW 를 제대로 고르고, 칸 문장을 사실대로 쓰는지 잰다.
`cases.json` 은 직접 쓴 사례 44개다(APPLY 12 · ASK_MORE 8 · 더 못 묻는 뭉뚱그린 답 4 · OFF_TOPIC 8 · DONT_KNOW 4 · 다른 칸 내용을 함께 말한 답 8).

실행: `cd ai && set -a; source .env; set +a; python evals/interview_reply/measure.py --repeat 2 --out evals/results/interview-reply.json`
키 없이 스크립트만 확인하려면 `--fake`, 일부만 돌리려면 `--limit N`.

- `verdict_match`: 기대한 판단과 같은 횟수. 기대 판단별로도 나눠 보여 준다.
- `keep_passed`·`must_not_passed`: 문장에 있어야 할 말(답의 수치·도구 이름)과 없어야 할 말(지어낸 수치)을 지킨 개수.
- `extra_passed`: 다른 빈 칸 문장을 낸 사례와, 글이 있는 칸을 건드리지 않은 사례.
- `unavailable`: 코드 규칙을 두 번 어겨 쓸 수 없는 결과로 끝난 호출 수. 결과 원본은 커밋하지 않고 숫자만 `RESULTS.md` 에 둔다.
