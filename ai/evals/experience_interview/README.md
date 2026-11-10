# 경험 카드 되묻기 측정 (#482)

모델이 쓴 문장 원문은 저장소에 두지 않는다. 결과는 `ai/evals/results/experience_interview/`(git 이 무시)에 쓰고, 숫자만 `ai/evals/RESULTS.md` 에 적는다.

## 표본 (모두 직접 쓴 가상의 경험, 다른 팀 글 없음)
- `polish.json` — 거친 답 20개. 체감 숫자(p05)·팀 주어(p06·p15)·'조금'(p07)·250자 넘는 답(p08)·이메일(p14)처럼 함정을 섞었고, `watch` 에 볼 점을 적었다.
- `themes.json` — 경험 카드 20장과 맞다고 볼 문항 유형(`expect`).
- `templates.json` — 활동 카드 10장(빈 카드 6 · 앞 답이 있는 카드 4).

## 성공 기준 (2026-10-28 합의)
- 다듬기: 지어낸 숫자(한 번 고친 뒤) 0 · 주어 바뀜·정도 키움 0 · 사실 더함/뺌 20개 중 1 이하 · 말투 '~했다' 전부.
- 문항 유형: 첫 유형이 `expect` 안 80% 이상.
- 빈칸 틀: 빈칸 없거나 지어낸 틀이 나간 것 0.

## 순서 (`ai/` 에서, 키는 환경변수나 `GITORY_EVAL_KEY_FILE`)
1. `python evals/experience_interview/measure.py run polish --dry-run` — 요청 검증만(LLM 없음)
2. `python evals/experience_interview/measure.py run polish` · `run themes` · `run templates`
3. `python evals/experience_interview/measure.py report` — 숫자를 찍고 `polish_grades.md`·`template_grades.md` 를 결과 폴더에 만든다. O/X 를 적고 report 를 다시 돌린다.
4. RESULTS.md 한 줄

- 기본 모델 `gpt-5.6-luna`(운영). 호출은 다듬기 20~40 · 유형 20 · 틀 10번이라 몇십 원이다. 다른 모델은 `--model` 과 `--label` 로 견준다.
