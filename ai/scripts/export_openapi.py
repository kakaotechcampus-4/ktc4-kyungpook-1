"""실행 앱에서 OpenAPI JSON을 생성한다. 서버·GitHub·LLM 호출은 하지 않는다."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

AI_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(AI_ROOT))

from main import create_app


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="저장할 JSON 경로. 생략하면 stdout")
    args = parser.parse_args()
    document = json.dumps(
        create_app(docs_enabled=True).openapi(), ensure_ascii=False, indent=2,
        sort_keys=True,
    ) + "\n"
    if args.output is None:
        sys.stdout.write(document)
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(document, encoding="utf-8")


if __name__ == "__main__":
    main()
