#!/usr/bin/env bash
# 커밋·푸시·PR 생성은 사람에게 먼저 묻는다.
#
# 팀 규칙: 원격에 나가는 동작과 이력을 바꾸는 동작은 승인을 받고 한다.
# 읽기(status·diff·log·show·fetch)는 막지 않는다.
set -euo pipefail

cmd=$(python3 -c '
import json, sys
d = json.load(sys.stdin)
print(d.get("tool_input", {}).get("command", ""))
' 2>/dev/null) || exit 0

[ -n "$cmd" ] || exit 0

if printf '%s' "$cmd" | grep -Eq '(^|[;&|[:space:]])(git[[:space:]]+(commit|push|merge|rebase|reset[[:space:]]+--hard|tag)|gh[[:space:]]+(pr[[:space:]]+(create|merge)|release[[:space:]]+create))'; then
  cat >&2 <<MSG
차단: 승인이 필요한 git 동작이다 — $cmd

커밋·푸시·머지·PR 생성은 먼저 사람에게 묻고 한다.
무엇을 왜 커밋할지 요약해서 확인을 받은 뒤 다시 실행해라.
(status·diff·log·show·fetch 는 막히지 않는다.)
MSG
  exit 2
fi
exit 0
