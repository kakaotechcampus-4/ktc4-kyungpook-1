#!/usr/bin/env bash
# 분석 Job 을 하나 돌려 SUCCEEDED 와 후보가 생기는지 curl 로 확인한다 (smoke.sh 와 같은 스택).
set -euo pipefail
source "$(dirname "$0")/lib.sh"

curl -fsS -X POST "$FAKE/__control/reset" > /dev/null
login
curl -fsS -b "$JAR" -c "$JAR" "$BASE/api/me" > /dev/null
REPO_ID="$(curl -fsS -b "$JAR" -c "$JAR" "$BASE/api/repos" | json_field id)"
[ -n "$REPO_ID" ] || fail "저장소 id 를 못 읽었다"

JOB="$(api_post "/api/repos/$REPO_ID/analyze")"
JOB_ID="$(json_field jobId <<<"$JOB")"
[ -n "$JOB_ID" ] || fail "분석 시작 응답에 jobId 가 없다: $JOB"
ok "분석 시작 jobId=$JOB_ID"

STATE=""
for _ in $(seq 1 90); do
  BODY="$(curl -fsS -b "$JAR" "$BASE/api/jobs/$JOB_ID")"
  STATE="$(job_field state <<<"$BODY")"
  case "$STATE" in SUCCEEDED|FAILED|CANCELED) break;; esac
  sleep 2
done
[ "$STATE" = "SUCCEEDED" ] || fail "Job 이 SUCCEEDED 가 아니다 ($STATE): $BODY"
[ "$(job_field partial <<<"$BODY")" = "false" ] || fail "부분 결과로 끝났다: $BODY"
ok "Job SUCCEEDED (partial=false)"

CANDIDATES="$(curl -fsS -b "$JAR" "$BASE/api/repos/$REPO_ID/candidates")"
grep -q 'E2E 후보' <<<"$CANDIDATES" || fail "후보에 가짜 LLM 이 만든 제목이 없다: $CANDIDATES"
ok "후보 보드에 가짜 LLM 제목이 있다"
head -c 600 <<<"$CANDIDATES"; echo

# c06 커밋 메시지의 '#000' 은 이슈 번호 0 으로 읽혀 수집이 500 으로 끝났던 사고의 회귀 확인 데이터다
# 스택을 다시 돌려도 다른 사용자의 같은 커밋이 쌓이므로 이 스크립트가 로그인한 e2e-user 의 저장소로만 센다
ZERO_ROWS="$("${COMPOSE[@]}" exec -T db psql -U gitory -d gitory -Atc "select count(*) from git_commit c join repository r on r.id = c.repository_id where r.owner_login = 'e2e-user' and c.message like '%#000%' and c.issue_numbers is not null and not (0 = any(c.issue_numbers))")"
[ "$ZERO_ROWS" = "1" ] || fail "c06 #000 커밋 — #230 회귀 확인 데이터가 저장되지 않았거나 이슈 번호 0 이 남았다 (행 $ZERO_ROWS)"
ok "#000 커밋이 저장됐고 이슈 번호 0 은 버려졌다"

# AI 가 가짜 GitHub 에 보낸 /repos 요청의 토큰도 헤더로 그대로 와야 한다
REPO_AUTH="$(curl -fsS "$FAKE/__control/requests" | node -e "
let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{
  const r=JSON.parse(s).filter(x=>x.path.startsWith('/repos/'));
  const bad=r.filter(x=>x.authorization!=='Bearer gho_E2E_e2e-user');
  console.log(r.length+' '+bad.length);
})")"
[ "${REPO_AUTH% *}" -ge 1 ] || fail "AI 가 가짜 GitHub 의 /repos 를 부르지 않았다"
[ "${REPO_AUTH#* }" = "0" ] || fail "AI 의 /repos 요청 중 ${REPO_AUTH#* }건이 로그인 때 준 토큰이 아니다"
ok "AI 의 GitHub 요청 ${REPO_AUTH% *}건이 모두 로그인 때 준 토큰이다"

echo "분석 스모크 통과"
