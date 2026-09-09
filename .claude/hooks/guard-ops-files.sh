#!/usr/bin/env bash
# 운영진 소유 파일 편집을 막는다.
#
# .github/CODEOWNERS 의 [운영진 영역] 4줄이 멘토 자동 지정과 Discord 알림을 돌린다.
# 건드리면 주간 develop→main PR 이 승인 대기에 걸리고, 승인자가 1명이라
# 일요일 23:59 머지의 단일 장애점이 된다. 실수로 한 번 하면 팀 전체가 막힌다.
set -euo pipefail

path=$(python3 -c '
import json, sys
d = json.load(sys.stdin)
print(d.get("tool_input", {}).get("file_path", ""))
' 2>/dev/null) || exit 0

[ -n "$path" ] || exit 0

case "$path" in
  */.github/workflows/assign-mentor.yml|\
  */.github/workflows/notify-discord.yml|\
  */.github/workflows/convention-check.yml|\
  */.github/CODEOWNERS)
    cat >&2 <<MSG
차단: 운영진 소유 파일이다 — $path

.github/CODEOWNERS 의 [운영진 영역] 4개 파일은 팀이 수정·삭제하면 안 된다.
멘토 자동 지정과 Discord 알림이 여기서 돈다. 건드리면 팀 PR 에 멘토가 안 붙는다.

새 워크플로 **추가**는 허용된다 (.github/workflows/backend-ci.yml 처럼).
이 4개를 정말 바꿔야 하면 담임매니저에게 요청한다.
MSG
    exit 2
    ;;
esac
exit 0
