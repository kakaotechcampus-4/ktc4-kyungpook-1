"""모델 출력이 입력 근거를 벗어났는지 코드로 확인한다."""

from __future__ import annotations

import re

#: 숫자. 천 단위 쉼표(1,000)와 소수(2.5)를 하나의 숫자로 읽는다.
_NUMBER = r"\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?"
#: 영문·숫자에 붙은 숫자(OAuth2, v2.0, sha256)는 수치가 아니다. 한글 뒤(최대30%)는 수치로 본다.
_NUMBER_START = r"(?<![A-Za-z0-9_.,])"
_UNITS = "ms|밀리초|초|분|%|퍼센트|배|건|회|MB|GB|KB"

#: 성능·결과 수치처럼 보이는 표현.
METRIC_PATTERN = re.compile(rf"{_NUMBER_START}({_NUMBER})(\s*)({_UNITS})(?![A-Za-z])")

#: 입력에 독립된 숫자로 나온 값. 앞뒤가 영문·숫자에 붙어 있으면(v2, 15의 5) 근거로 치지 않는다.
_STANDALONE_NUMBER = re.compile(rf"{_NUMBER_START}({_NUMBER})(?![0-9])")
#: diff hunk 헤더(@@ -30,7 +30,9 @@)의 줄 번호는 수치 근거로 보지 않는다.
_HUNK_HEADER = re.compile(r"^[+\- ]?@@[^\n]*@@", re.MULTILINE)
_HANGUL = re.compile(r"[가-힣]")


def unsupported_metrics(text: str, source: str) -> list[str]:
    """입력에서 확인되지 않는 수치 표현을 반환한다.

    수치의 숫자가 입력에 독립된 숫자로 있으면 근거가 있다고 본다
    (``MAX_TURNS = 2`` → "2회", ``1,000`` → "1000건"). 숫자 일부만 겹치는 경우
    (원문 15% 안의 5%)나 줄 번호는 근거가 아니다.
    """
    source = _HUNK_HEADER.sub("", source)
    source_numbers = {_normalize(match.group(1)) for match in _STANDALONE_NUMBER.finditer(source)}
    unsupported = set()
    for match in iter_metrics(text):
        if _normalize(match.group(1)) not in source_numbers:
            unsupported.add(match.group(0))
    return sorted(unsupported)


def iter_metrics(text: str):
    """수치 표현을 찾는다. "2 배포"처럼 띄어 쓴 한글 단위 뒤에 글자가 이어지면 단어로 본다."""
    for match in METRIC_PATTERN.finditer(text):
        spaced, unit = match.group(2), match.group(3)
        following = text[match.end():match.end() + 1]
        if spaced and _HANGUL.fullmatch(unit[-1]) and _HANGUL.fullmatch(following or " "):
            continue
        yield match


def _normalize(number: str) -> str:
    return number.replace(",", "")
