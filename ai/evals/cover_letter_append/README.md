# 자소서 새 카드 덧붙이기 측정 (#1002)

- 무엇을: 중심 카드가 있는 문항에 새 카드를 더할 때, 지금 글의 문장을 그대로 두고 새 카드 내용을 한두 문장 덧붙이는지 잰다.
- 표본: `cover_letter/users.json` 의 가상 사용자 문항 5개(u1 Q3 · u3 Q3 · u4 Q2 · u5 Q3 · u4 Q1). 문항마다 정답 카드 둘 중 하나로 지금 글을 쓰고 다른 하나를 덧붙인다.
- 숫자: `kept_ratio`(원래 문장이 남은 비율) · `added_sentences`(더한 문장 수) · `over_limit`(글자 수 상한을 넘은 문항) · `reverted`(앞 글로 되돌린 문항).
- 돌리는 법(`ai/` 에서): `python evals/cover_letter_append/measure.py [--model M] [--label 이름] [--cap-won 100]`, 요청 검증만 보려면 `--dry-run`.
- 결과 원본(모델이 쓴 글)은 `ai/evals/results/cover_letter_append/` 에만 남고 git 이 무시한다. 요약은 `ai/evals/results/history/` 에 남는다.
