#!/usr/bin/env bash
# 운영 작업을 5.6 Luna 와 6 Luna 로(되묻기는 Haiku 5.5 도) 같은 입력으로 돌려 견주는 순서다. 사용: cd ai && bash evals/model_compare/run_compare.sh
# 입력은 이 저장소의 PR 에서 만든 것이라 따로 복사해 두지 않고 처음에 한 번 만든다(evals/fixtures/generated/, git 이 무시한다).
# 모델마다 주소가 다르면 evals/model_endpoints.json(예: model_endpoints.example.json)을 채우고, 키는 GITORY_LLM_* 환경변수나 ~/.gitory-llm.env 에 둔다.
# 결과 원본은 evals/results/ 아래(이 작업은 model_compare/)에만 쓰고 저장소에는 숫자만 RESULTS.md 에 옮긴다.
set -u

OUT=evals/results/model_compare
CASES="PR46 PR11 PR35 PR15 PR12 PR21 PR58 PR50 PR17 PR52"
export GITORY_EVAL_TAG=compare
mkdir -p "$OUT"

# 환경변수로 모델을 고르는 스크립트에 모델별 주소·비용 장부·상한을 건다
with_harness() {
  python -X utf8 -c "
import runpy, sys
sys.path.insert(0, '.')
from evals.common import llm_harness
llm_harness.install()
script, *rest = sys.argv[1:]
sys.argv = [script, *rest]
runpy.run_path(script, run_name='__main__')
" "$@"
}

[ -f evals/fixtures/generated/diff_summary.json ] || python -X utf8 scripts/build_repo_eval_fixtures.py

interview() {
  for pair in "baseline gpt-5.6-luna" "m6 openai/gpt-6-luna" "haiku anthropic/claude-haiku-5-5"; do
    set -- $pair
    python -X utf8 evals/interview_reason/measure.py run --label "$1" --model "$2" > "$OUT/interview-$1.log" 2>&1
  done
}

tasks() {
  python -X utf8 scripts/evaluate_llm_tasks.py --task diff_summary --model-names "GPT-5.6 Luna" "GPT-6 Luna" \
    --case-ids $CASES --repeats 2 --workers 3 --output "$OUT/diff.json" > "$OUT/diff.log" 2>&1
  python -X utf8 scripts/evaluate_llm_tasks.py --task star_draft --model-names "GPT-5.6 Luna" "GPT-6 Luna" \
    --case-ids $CASES --repeats 3 --workers 3 --output "$OUT/star.json" > "$OUT/star.log" 2>&1
}

grouping_and_injection() {
  for pair in "m56 gpt-5.6-luna" "m6 openai/gpt-6-luna"; do
    set -- $pair
    # 경험 묶기는 --login·--email 이 필요하다(내 GitHub 로그인과 커밋 이메일)
    GITORY_GROUPING_MODEL="$2" with_harness evals/grouping/measure_cluster.py --login "${GITORY_EVAL_LOGIN:?}" \
      --email "${GITORY_EVAL_EMAIL:?}" --prs 60 --output "$OUT/grouping-$1.json" > "$OUT/grouping-$1.log" 2>&1
    GITORY_STAR_MODEL="$2" GITORY_DIFF_MODEL="$2" GITORY_GROUPING_MODEL="$2" GITORY_JOB_POSTING_MODEL="$2" GITORY_ANSWER_MODEL="$2" \
      with_harness scripts/evaluate_prompt_injection.py --output "$OUT/injection-$1.json" > "$OUT/injection-$1.log" 2>&1
  done
}

interview & tasks & grouping_and_injection & wait
echo "done"
