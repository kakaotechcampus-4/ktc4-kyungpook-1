#!/usr/bin/env bash
# 이미 커밋된 Flyway 마이그레이션 수정을 막는다.
#
# 그 버전을 이미 적용한 환경과 체크섬이 어긋나 배포가 멈춘다.
# CI(migration-guard)가 PR 에서 잡지만, 여기서 막으면 PR 을 열기 전에 끝난다.
set -euo pipefail

path=$(python3 -c '
import json, sys
d = json.load(sys.stdin)
print(d.get("tool_input", {}).get("file_path", ""))
' 2>/dev/null) || exit 0

case "$path" in
  */backend/src/main/resources/db/migration/V*.sql) ;;
  *) exit 0 ;;
esac

# 아직 커밋되지 않은 파일이면 자유롭게 고쳐도 된다.
if git -C "$(dirname "$path")" ls-files --error-unmatch "$path" >/dev/null 2>&1; then
  cat >&2 <<MSG
차단: 이미 커밋된 마이그레이션이다 — $(basename "$path")

Flyway 는 적용된 마이그레이션의 체크섬을 저장한다. 파일을 고치면 이미 그 버전을
적용한 환경에서 체크섬이 어긋나 애플리케이션이 뜨지 않는다.

새 파일을 추가해라: backend/src/main/resources/db/migration/V<다음번호>__<설명>.sql
MSG
  exit 2
fi
exit 0
