#!/usr/bin/env bash
# 턴이 끝날 때, 백엔드 소스가 바뀌었으면 모듈 경계 테스트를 돌린다.
#
# 경계를 주석으로만 적어 두면 두 달 뒤에 없는 경계가 된다.
# 전체 build 가 아니라 ArchUnit 테스트만 돌려서 몇 초 안에 끝낸다.
set -euo pipefail

# 훅이 스스로를 다시 부르는 것을 막는다.
active=$(python3 -c '
import json, sys
d = json.load(sys.stdin)
print("1" if d.get("stop_hook_active") else "0")
' 2>/dev/null) || exit 0
[ "$active" = "1" ] && exit 0

root=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
cd "$root"

git status --porcelain -- backend/src | grep -q . || exit 0
[ -x backend/gradlew ] || exit 0

out=$(cd backend && ./gradlew test --tests '*ModuleBoundaryTest' \
        --console=plain --quiet --offline 2>&1) && exit 0

cat >&2 <<MSG
모듈 경계 테스트가 실패했다. 고치고 끝내라.

$(printf '%s' "$out" | grep -E 'FAILED|Architecture Violation|was violated' | head -20)

전체 로그: cd backend && ./gradlew test --tests '*ModuleBoundaryTest'
경계 규칙은 각 모듈의 package-info.java 와 backend/ARCHITECTURE.md 에 있다.
MSG
exit 2
