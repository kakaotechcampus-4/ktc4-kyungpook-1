# 스모크 스크립트가 같이 쓰는 도우미 (bash + curl + docker compose + node)
BASE="${E2E_BASE:-http://localhost:8088}"
FAKE="${E2E_FAKE:-http://localhost:9000}"
COMPOSE=(docker compose -f "$(dirname "${BASH_SOURCE[0]}")/../compose.yaml" -p gitory-e2e)
WORK="$(mktemp -d)"
JAR="$WORK/cookies.txt"
trap 'rm -rf "$WORK"' EXIT

fail() { echo "실패: $*" >&2; exit 1; }
ok() { echo "확인: $*"; }

# 로그인이 실패하면 Spring 이 /login?error 로 보내고 Caddy 가 200 으로 받아 주므로 마지막 주소를 본다
login() {
  local last
  last="$(curl -sS -L -o /dev/null -w '%{url_effective}' -D "$WORK/login-headers.txt" -c "$JAR" -b "$JAR" "$BASE/api/auth/github/start")" \
    || fail "로그인 흐름 요청 실패"
  case "$last" in *error*) fail "로그인이 거절됐다: $last";; esac
}

# Git Bash 에 jq 가 없어 grep 으로 문자열 칸 하나를 뽑는다
json_field() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }

# 칸 순서에 기대지 않고 data 바로 아래 칸을 읽는다
job_field() { node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{const v=JSON.parse(s).data?.['$1'];console.log(v===undefined?'':v)})"; }

csrf_token() { awk '$6=="XSRF-TOKEN"{print $7}' "$JAR" | tail -1; }

new_uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || node -e "console.log(crypto.randomUUID())"; }

api_post() {
  local path="$1" body="${2:-}"
  curl -sS -b "$JAR" -c "$JAR" -X POST "$BASE$path" \
    -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(csrf_token)" \
    -H "Idempotency-Key: $(new_uuid)" \
    ${body:+-d "$body"}
}
