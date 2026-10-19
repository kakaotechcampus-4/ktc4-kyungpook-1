#!/usr/bin/env bash
# 스택을 띄운 상태에서 로그인 흐름과 토큰 암호화 저장, 저장소 목록을 curl 로 확인한다.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

curl -fsS -X POST "$FAKE/__control/reset" > /dev/null || fail "가짜 서버가 응답하지 않는다"
login

# 시험 스택은 Secure 를 끈 http 라서 SESSION 쿠키에 Secure 가 없어야 하고 HttpOnly 는 있어야 한다
SET_COOKIE="$(grep -i '^set-cookie: SESSION=' "$WORK/login-headers.txt" | head -1 | tr -d '\r')"
[ -n "$SET_COOKIE" ] || fail "로그인 응답에 SESSION 쿠키가 없다"
grep -qi 'HttpOnly' <<<"$SET_COOKIE" || fail "SESSION 쿠키에 HttpOnly 가 없다: $SET_COOKIE"
if grep -qi 'Secure' <<<"$SET_COOKIE"; then fail "SESSION 쿠키에 Secure 가 남아 있다: $SET_COOKIE"; fi
ok "SESSION 쿠키: $SET_COOKIE"

ME="$(curl -fsS -b "$JAR" -c "$JAR" "$BASE/api/me")"
[ "$(json_field login <<<"$ME")" = "e2e-user" ] || fail "/api/me 의 login 이 e2e-user 가 아니다: $ME"
ok "/api/me login = e2e-user"

# 토큰은 암호화해서 저장해야 하므로 원문(gho_E2E_...)이 DB 에 그대로 있으면 안 된다
TOKEN_ENC="$("${COMPOSE[@]}" exec -T db psql -U gitory -d gitory -Atc "select token_enc from github_connection where revoked_at is null limit 1")"
[ -n "$TOKEN_ENC" ] || fail "github_connection 에 연결 행이 없다"
case "$TOKEN_ENC" in *gho_E2E_*|*e2e-user*) fail "token_enc 에 토큰 원문이 들어 있다";; esac
ok "token_enc 는 원문과 다르다 (${#TOKEN_ENC}자)"

# 개수 세기가 실패해도 백엔드는 0 으로 목록을 주므로, 가짜 GraphQL·검색 응답대로 숫자가 나오는지까지 본다
REPOS="$(curl -fsS -b "$JAR" "$BASE/api/repos")"
for expected in '"name":"sample-app"' '"mine":10,"team":0' '"prCount":2' '"recommended":true'; do
  grep -qF "$expected" <<<"$REPOS" || fail "/api/repos 에 $expected 가 없다: $REPOS"
done
grep -qE '"countedAt":"[^"]' <<<"$REPOS" || fail "/api/repos 의 countedAt 이 비어 있다(개수 세기 실패): $REPOS"
ok "/api/repos 가 sample-app 과 가짜 응답대로 센 숫자를 돌려준다"

# 이 시점까지 가짜 GitHub 에 온 요청(/user·/graphql·/search)은 모두 로그인 때 준 토큰이어야 한다
REQUESTS="$(curl -fsS "$FAKE/__control/requests")"
BAD="$(grep -o '"path":"/\(user\|graphql\|search\|repos\)[^"]*","authorization":[^}]*' <<<"$REQUESTS" | grep -v 'Bearer gho_E2E_e2e-user"' || true)"
[ -z "$BAD" ] || fail "다른 토큰이나 빈 토큰으로 간 GitHub 요청이 있다: $BAD"
ok "가짜 GitHub 요청의 토큰이 모두 로그인 때 준 토큰이다"

echo "스모크 통과"
