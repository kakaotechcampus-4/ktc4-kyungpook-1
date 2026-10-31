#!/usr/bin/env bash
set -euo pipefail

# Keep GitHub's signed Ubuntu sources and fallback mirrors; only avoid a slow first mirror.
# Runner source: actions/runner-images/images/ubuntu/scripts/build/configure-apt-sources.sh
mirror_list=/etc/apt/apt-mirrors.txt
if [[ "${GITHUB_ACTIONS:-}" != "true" || ! -f "$mirror_list" ]]; then
  exit 0
fi

sudo sed -i \
  -e '/^http:\/\/azure\.archive\.ubuntu\.com\/ubuntu\//s/priority:1/priority:2/' \
  -e '/^https:\/\/archive\.ubuntu\.com\/ubuntu\//s/priority:2/priority:1/' \
  "$mirror_list"
