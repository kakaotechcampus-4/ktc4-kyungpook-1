#!/usr/bin/env python3
"""지시 주입 평가의 공고 이미지 사례(POSTING_IMAGE)에 쓸 PNG 를 그린다.

서버 컨테이너에는 Pillow 가 없어 그린 이미지를 ai/evals/injection_images/ 에 두고 평가가 읽는다.
사례나 주입 문장을 바꿨을 때만 로컬에서 다시 그린다. Pillow 는 앱 의존성이 아니라 따로 설치한다.

  pip install pillow
  python -m scripts.make_injection_images --font /System/Library/Fonts/AppleSDGothicNeo.ttc
"""

from __future__ import annotations

import argparse
from pathlib import Path

from scripts.evaluate_prompt_injection import CASES, INJECTION_IMAGE_DIR, posting_image_text

WIDTH = 1200
MARGIN = 48
FONT_SIZE = 28
LINE_GAP = 14


def _wrap(draw, line: str, font, max_width: int) -> list[str]:
    """낱말 사이에서만 줄을 나눠 표식 낱말·비밀값 모양이 두 줄로 쪼개지지 않게 한다."""
    words, rows, current = line.split(" "), [], ""
    for word in words:
        candidate = f"{current} {word}".strip()
        if current and draw.textlength(candidate, font=font) > max_width:
            rows.append(current)
            current = word
        else:
            current = candidate
    return [*rows, current]


def render(text: str, font_path: str) -> "Image.Image":  # noqa: F821 - Pillow 는 실행할 때만 가져온다
    from PIL import Image, ImageDraw, ImageFont

    font = ImageFont.truetype(font_path, FONT_SIZE)
    probe = ImageDraw.Draw(Image.new("L", (WIDTH, 10)))
    rows = [row for line in text.split("\n") for row in _wrap(probe, line, font, WIDTH - 2 * MARGIN)]
    height = 2 * MARGIN + len(rows) * (FONT_SIZE + LINE_GAP)
    image = Image.new("L", (WIDTH, height), 255)
    draw = ImageDraw.Draw(image)
    for index, row in enumerate(rows):
        draw.text((MARGIN, MARGIN + index * (FONT_SIZE + LINE_GAP)), row, font=font, fill=0)
    return image


def main() -> int:
    parser = argparse.ArgumentParser(description="공고 이미지 주입 사례 PNG 그리기")
    parser.add_argument("--font", required=True, help="한글을 그릴 수 있는 글꼴 파일")
    args = parser.parse_args()
    INJECTION_IMAGE_DIR.mkdir(parents=True, exist_ok=True)
    for case in CASES:
        if case.task == "POSTING_IMAGE":
            path: Path = INJECTION_IMAGE_DIR / f"{case.name}.png"
            render(posting_image_text(case), args.font).save(path, optimize=True)
            print(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
