#!/usr/bin/env bash
# 실행 BE와 AI 코드에서 문서 스냅샷을 생성한다. 실제 인증 값은 필요하지 않다.
set -euo pipefail

repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
backend_url="${BACKEND_URL:-http://localhost:8080}"
python_bin="${PYTHON_BIN:-python3}"
output_dir="$repo_dir/docs/openapi"
mkdir -p "$output_dir"

# 두 문서 생성이 모두 성공해야 기존 스냅샷을 교체한다.
staging_dir="$(mktemp -d)"
download_file="$staging_dir/download.json"
trap 'rm -f -- "$staging_dir/download.json" "$staging_dir/backend.json" "$staging_dir/ai.json"; rmdir -- "$staging_dir"' EXIT
curl --fail --silent --show-error --connect-timeout 5 --max-time 30 \
  "${backend_url%/}/api/docs" --output "$download_file"
"$python_bin" - "$download_file" "$staging_dir/backend.json" <<'PY'
import json
import pathlib
import sys

document = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
if not isinstance(document, dict) or not isinstance(document.get("openapi"), str) or not isinstance(document.get("paths"), dict):
    raise SystemExit("BE 응답이 OpenAPI 문서가 아닙니다. API_DOCS_ENABLED 설정을 확인하세요.")
pathlib.Path(sys.argv[2]).write_text(
    json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
    encoding="utf-8",
)
PY
"$python_bin" "$repo_dir/ai/scripts/export_openapi.py" --output "$staging_dir/ai.json"
mv -- "$staging_dir/backend.json" "$output_dir/backend.json"
mv -- "$staging_dir/ai.json" "$output_dir/ai.json"
printf 'OpenAPI exported to %s\n' "$output_dir"
