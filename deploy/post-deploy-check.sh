#!/usr/bin/env bash
# 컨테이너가 healthy 여도 인증서, 화면 파일, 서버 연결, 로그인 주소 중 하나가 깨지면 사용자는 사이트를 쓰지 못한다
# 배포 서버 안에서 돌기 때문에 공개 DNS 를 거치지 않고 이 서버의 443 으로 바로 붙는다
set -uo pipefail

site="${1:?확인할 주소가 필요하다}"
target="${2:-127.0.0.1}"
attempts="${CHECK_ATTEMPTS:-24}"
interval="${CHECK_INTERVAL:-5}"
base="https://$site"
reason=""

get() {
  curl -sS -m 10 --resolve "$site:443:$target" "$@"
}

check() {
  local html asset code start
  html="$(get "$base/" 2>&1)" || { reason="홈을 읽지 못했다: $html"; return 1; }
  grep -q 'id="root"' <<<"$html" || { reason="홈에 앱 화면이 없다"; return 1; }

  asset="$(grep -oE '/assets/[^"]+\.js' <<<"$html" | head -1)"
  [ -n "$asset" ] || { reason="홈에서 화면 파일을 찾지 못했다"; return 1; }
  code="$(get -o /dev/null -w '%{http_code}' "$base$asset" 2>&1)"
  [ "$code" = "200" ] || { reason="화면 파일 $asset 이 $code 이다"; return 1; }

  code="$(get -o /dev/null -w '%{http_code}' "$base/api/me" 2>&1)"
  [ "$code" = "401" ] || { reason="/api/me 가 401 이 아니라 $code 이다"; return 1; }

  start="$(get -o /dev/null -w '%{http_code} %{redirect_url}' "$base/api/auth/github/start" 2>&1)"
  case "$start" in
    "302 https://github.com/login/oauth/authorize"*) ;;
    *) reason="GitHub 로그인으로 넘어가지 않는다: ${start:0:120}"; return 1 ;;
  esac
  grep -qE "redirect_uri=https(://|%3A%2F%2F)$site" <<<"$start" || { reason="로그인 뒤 돌아올 주소가 $site 가 아니다"; return 1; }
}

for ((i = 1; i <= attempts; i++)); do
  if check; then
    echo "배포 뒤 확인 통과: $site"
    exit 0
  fi
  echo "[$i/$attempts] $reason"
  [ "$i" -lt "$attempts" ] && sleep "$interval"
done

echo "::error::배포 뒤 확인 실패: $reason"
exit 1
