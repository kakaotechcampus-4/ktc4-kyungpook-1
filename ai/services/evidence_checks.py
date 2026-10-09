"""모델 출력이 입력 근거를 벗어났는지 코드로 확인한다."""

from __future__ import annotations

import re

#: 성능·결과 수치처럼 보이는 표현.
METRIC_PATTERN = re.compile(r"\d+(?:\.\d+)?\s*(?:ms|밀리초|초|분|%|퍼센트|배|건|회|MB|GB|KB)")

_NUMBER = re.compile(r"\d+(?:\.\d+)?")
#: 식별자(v2, sha256)나 단어 속 숫자가 아닌 독립된 숫자.
_STANDALONE_NUMBER = re.compile(r"(?<![\w.])\d+(?:\.\d+)?(?!\w)")
#: diff hunk 헤더(@@ -30,7 +30,9 @@)의 줄 번호는 수치 근거로 보지 않는다.
_HUNK_HEADER = re.compile(r"^[+\- ]?@@[^\n]*@@", re.MULTILINE)


def unsupported_metrics(text: str, source: str) -> list[str]:
    """입력에서 확인되지 않는 수치 표현을 반환한다.

    수치 표현이 입력에 그대로 있거나(공백 무시), 그 숫자가 입력에 독립된 숫자로
    있으면(예: ``MAX_TURNS = 2`` → "2회") 근거가 있다고 본다.
    """
    source = _HUNK_HEADER.sub("", source)
    normalized_source = re.sub(r"\s+", "", source)
    source_numbers = set(_STANDALONE_NUMBER.findall(source))
    unsupported = set()
    for match in METRIC_PATTERN.finditer(text):
        metric = match.group(0)
        if re.sub(r"\s+", "", metric) in normalized_source:
            continue
        if _NUMBER.match(metric).group(0) in source_numbers:
            continue
        unsupported.add(metric)
    return sorted(unsupported)
