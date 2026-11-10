# 되묻기 판단 이유 측정 (#480)

모델이 쓴 질문·이유 원문은 저장소에 두지 않는다. 결과는 `ai/evals/results/interview_reason/`(git 이 무시)에 쓰고, 숫자만 `ai/evals/RESULTS.md` 에 적는다.

## 표본
- `cases.json` — 직접 쓴 가상의 개발 경험 카드 20장(다른 팀 글 없음). 모두 직접 작성 카드(DIRECT_CARD)라 커밋 없이 칸 문장과 앞 질문·답만 간다.
- SHORT 10장: 한 칸의 앞 답이 모자람(S 2·T 2·A 3·R 3). 그 칸을 다시 물어야 맞다.
- FULL 10장: 네 칸이 모두 구체적이다. 그만 물어야 맞다.
- 사용자가 쓴 답은 Spring 이 신뢰도 HIGH 로 보내므로 모자란 답도 HIGH 다(신뢰도가 아니라 내용으로 판단해야 한다).

## 성공 기준 (2026-10-28 합의)
- 기준선(이유 없는 프롬프트)과 같은 세트로 견줘 SHORT 다시 물음·FULL 끝냄 정확도가 떨어지지 않음.
- 이유가 고른 행동과 맞음 90% 이상(사람이 `grades.md` 에 O/X).
- 이유에 입력에 없는 숫자 0건(코드가 버리므로 보낸 이유는 0, '수치 때문에 버린 이유' 수를 함께 적는다).

## 순서 (`ai/` 에서, 키는 환경변수나 `GITORY_EVAL_KEY_FILE`)
1. `python evals/interview_reason/measure.py run --label baseline --dry-run` — 요청 검증만(LLM 없음)
2. 이유를 넣기 전 코드에서 `run --label baseline`, 이유를 넣은 코드에서 `run --label reason`
3. `python evals/interview_reason/measure.py report` — 요약을 찍고 결과 폴더에 `grades.md` 를 만든다. '맞음' 칸에 O/X 를 적고 report 를 다시 돌린다.
4. RESULTS.md 한 줄

- 기본 `--repeat 2`(카드마다 두 번) · `--model gpt-5.6-luna`(운영). 호출은 실행마다 약 40번이라 비용은 몇십 원이다.
