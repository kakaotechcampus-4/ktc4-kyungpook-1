# 자소서 재료 제안 평가

`POST /internal/cover-letter-suggestions` 가 카드가 정해지지 않은 문항에 맞는 확정 카드·경험 후보만 고르는지, 맞는 것이 없을 때 빈 목록을 내는지 잰다.
`cases.json` 은 직접 쓴 사례 12개다(카드와 후보가 모두 있는 것 9 · 후보만 1 · 카드만 1 · 맞는 것이 없는 것 2). 사례마다 맞는 재료 목록(`gold`)과 고르면 안 되는 재료(`forbid`)가 있다.

실행: `cd ai && python evals/cover_letter_suggest/measure.py --repeat 2 --out evals/results/cover-letter-suggest.json`
키 없이 스크립트만 확인하려면 `--fake`, 일부만 돌리려면 `--limit N`.

- `precision`·`recall`: 고른 것 가운데 gold 인 수, gold 가운데 고른 수.
- `invented_ids_before_code_filter`: 모델이 요청에 없는 id 를 낸 수(코드가 거르기 전 값, 0 이 좋다).
- `forbidden_picked`: 고르면 안 되는 재료를 고른 수.
- `empty_when_nothing_fits`: 맞는 것이 없는 사례에서 빈 목록을 낸 비율.
- `angle_max_chars`: 가장 긴 angle(60 이하여야 한다). `cost_won_*`: 쓴 돈.
- 결과 원본은 커밋하지 않고 숫자만 `ai/evals/RESULTS.md` 에 둔다.
