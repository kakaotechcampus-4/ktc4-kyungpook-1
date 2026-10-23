#!/usr/bin/env bash
# BE·AI 코드에서 docs/openapi 스냅샷을 다시 만든다. 서버를 띄우거나 실제 인증 값을 넣지 않아도 된다.
# BE 는 스냅샷 비교 시험을 고쳐 쓰기 모드로 돌리므로 Docker 가 켜져 있어야 한다(시험용 PostgreSQL).
set -euo pipefail

repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
python_bin="${PYTHON_BIN:-python3}"

(cd "$repo_dir/backend" && UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiIntegrationTest')
"$python_bin" "$repo_dir/ai/scripts/export_openapi.py" --output "$repo_dir/docs/openapi/ai.json"
printf 'OpenAPI exported to %s\n' "$repo_dir/docs/openapi"
