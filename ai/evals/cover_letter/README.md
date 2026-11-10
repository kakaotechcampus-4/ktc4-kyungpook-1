# 자소서 초안 측정 (#483, 규칙 12)

모델이 쓴 초안 원문은 `ai/evals/results/cover_letter/`(git 이 무시하는 폴더)에만 남는다. 저장소에는 숫자만(`ai/evals/RESULTS.md`).

## 표본 (`users.json`, 모두 직접 지어낸 가상의 경험)
- 가상 사용자 8명 × 문항 3개 = 24문항. 지원 동기 문항 8개(사용자마다 1개), 성장·실패 문항 4개.
- 재료가 적은 사용자 2명: u7(카드 1장, 상황·행동만, 메모 없음) · u8(카드 2장이 짧고 답이 거의 없음).
- 함정: 카드에 수치가 있어 다른 수치를 지어내기 쉬움(u1 40분→12분), 팀 성과(u5 '팀이 우수상')를 내 역할로 부풀리기, 줄임말(u4 'k8s'), 기술 카드와 경험 카드가 같은 프로젝트(u1 gitory).
- `expect`: 사람이 맞다고 본 중심 카드. 여럿이면 어느 것이든 정답, `null` 은 '맞는 카드 없음'도 정답. **10-28 Claude 가 초안을 적고 10-29 사용자가 확인해 7줄을 고쳤다**(지원 동기는 메모의 지원 이유와 이어지는 카드·경험 카드 우선이고 없으면 없음, 성장 과정은 없음, 도전 문항에 경험 카드 더함).
- 답: `cards[].answers` 는 그 카드의 경험 항목(상황·행동·결과·배운 점·계기)을 물으면 할 답, `questions[].answers` 는 직무 연결·지원 이유. 없는 항목은 넘긴다(초안에 빈칸이 남아야 함).
- 카드 요약은 Spring 이 만들 요약을 흉내 내 행동·결과 문장을 붙인 것이다.

## 성공 기준 (규칙 12)
- 틀린 게 없나: 지어낸 숫자·기술 이름·회사 사실 0 · 지어낸 역할·성과가 기준판보다 적음 · 문항에 답함 90% 이상 · 표시 없이 글자 수 넘김 0 · 재료 적은 문항 빈칸 100% · A1 카드 정답 80% 이상.
- 잘 썼나: 가린 짝 비교에서 새 구조가 이긴 비율 70% 이상(다른 모델이 순서를 바꿔 두 번 보고, 두 번 모두 같은 쪽을 고른 것만 이김으로 센다) · 사람이 8문항을 같은 방식으로 골라 AI 판정과 맞는지 본다.

## 순서
모두 `ai/` 에서 돌린다. 키는 `GITORY_LLM_BASE_URL`·`GITORY_LLM_API_KEY` 환경변수나 `~/.gitory-llm.env` 에 둔다.
1. `python evals/cover_letter/measure.py run new` · `run baseline` — 같은 쓰기 모델로. Luna medium vs high 는 `run new --tag new-high --write-effort high` 를 더 돌린다.
2. `python evals/cover_letter/measure.py judge new baseline --judge-model anthropic/claude-haiku-5-5` (쓴 모델과 다른 모델)
3. `python evals/cover_letter/measure.py report new baseline` — 숫자를 찍고 `results/cover_letter/grades.md`(X·Y 로 가린 채점표)를 만든다. 답함·지어낸 역할 O/X 와 처음 8문항의 고른 쪽을 적고 report 를 다시 돌린다(`blind_key.json` 은 보지 않는다).
4. RESULTS.md 한 줄

- 호출 수: 새 구조는 사용자당 9~15번(고르기 3 + 감독 0~1 + 질문 3 + 쓰기 3~9 + 판단·검토), 기준판 1번, 비교 48번.
- `--fake` 로 LLM 없이 모든 단계가 실패하는 길(카드 고르기 실패 → 틀 질문 → 뼈대)과 비교·채점표까지 끝까지 도는지 볼 수 있다. `--dry-run` 은 요청 검증만 한다.
- 쓰기·검토 모델 비교의 명령 묶음은 `measure.py` 맨 위 설명에 있다.

## 공고 반영 측정 (#635, 11-01)
- `postings.json`: 사용자마다 실제 공고 하나(회사·제목·주요 업무·기술 조건 줄)와 조건별 ✓·✕(Claude 정답), ✕ 조건 질문에 할 가상 답(`gap_answer`, null 은 넘김), 답 없이 쓰면 안 되는 조건 낱말(`gap_words`).
- `python evals/cover_letter/posting_measure.py run with` · `run without` → `judge`(지원 동기만, 공고를 보여 주고 Haiku 가 순서를 바꿔 두 번) → `report`.
- 결과는 `ai/evals/RESULTS.md` '자소서 — 지원 공고 반영'.

## 그 밖의 측정
| 스크립트 | 무엇을 | RESULTS.md |
|---|---|---|
| `polish_measure.py` | 문항 첨삭(#725) 앞뒤 검사기 문제 수 | 11-03 자소서 — 문항 첨삭 |
| `turns_measure.py` | 채팅으로 재료 채우기(#731) 턴·다시 쓰기 | 11-03 자소서 — 채팅으로 재료 채우기 |
| `memo_measure.py` | 지원 이유 판정(#737) 메모 20개 | 11-03 자소서 — 지원 이유 판정 |
| `criteria_judge.py` · `criteria_compare.py` | 평가 기준 K1~K7 채점기와 사람 판정 견주기 | 없음 |

첨삭·채팅 측정의 11-03 숫자는 실제 카드 세트(저장소에 없음)로 냈다. 저장소의 `users.json` 으로도 돌아가지만 숫자는 달라진다.
