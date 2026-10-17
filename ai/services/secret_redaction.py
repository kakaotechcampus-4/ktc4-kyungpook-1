"""외부 모델 입출력에서 비밀값처럼 보이는 문자열을 찾고 가린다."""

from __future__ import annotations

import re

#: 형태만으로 비밀값임을 알 수 있는 토큰·키. 출력 검사(find_secrets)에도 쓴다.
SECRET_PATTERNS = (
    re.compile(r"AKIA[0-9A-Z]{16}"),
    re.compile(r"gh[pousr]_[A-Za-z0-9]{20,}"),
    re.compile(r"github_pat_[A-Za-z0-9_]{20,}"),
    re.compile(r"sk-[A-Za-z0-9_-]{20,}"),
    re.compile(r"xox[abprs]-[A-Za-z0-9-]{10,}"),
    re.compile(r"AIza[0-9A-Za-z_-]{35}"),
    # 개인 키는 첫 줄만이 아니라 본문 전체를 가린다. patch가 잘려 END가 없으면 끝까지 가린다.
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|\Z)"),
    re.compile(r"eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\."),
)

#: 이름 앞뒤 길이를 제한하고 단어 경계에서만 시작해, 긴 한 줄(minified 코드 등)에서도
#: 정규식 역추적이 줄 길이의 제곱으로 늘지 않게 한다.
_SECRET_NAME = (
    r"(?<![A-Za-z0-9_.-])[A-Za-z0-9_.-]{0,40}?(?:password|passwd|pwd|secret|token|api[_-]?key"
    r"|access[_-]?key|private[_-]?key|credential|client[_-]?secret)[A-Za-z0-9_.-]{0,40}"
)

#: 이름이 비밀값을 뜻하는 대입문의 값. ``${...}``·``$VAR`` 같은 참조는 근거가 되므로 남긴다.
#: 1) 따옴표 리터럴: password = "hunter2", "apiKey": "abcd"
_QUOTED_ASSIGNMENT = re.compile(
    rf"(?i)({_SECRET_NAME}[\"']?\s*[:=]\s*)([\"'])(?!\$)([^\"'\n]{{4,}})\2"
)
#: 2) 설정 파일의 따옴표 없는 줄: DB_PASSWORD=hunter2, client-secret: abcd
#:    코드의 ``token = request.headers.get(...)``까지 가리지 않도록 설정 파일에만 적용한다.
_BARE_ASSIGNMENT = re.compile(
    rf"(?im)^([+\- ]?[ \t]*(?:export[ \t]+)?{_SECRET_NAME}[ \t]*[:=][ \t]*)(?![\"'$\[{{]|REDACTED)(\S{{4,}})[ \t]*$"
)

#: 따옴표 없는 대입문을 값으로 봐야 하는 설정 파일.
_CONFIG_FILE = re.compile(r"(?i)(\.(properties|ya?ml|ini|cfg|conf|toml|env)$|(^|/)\.env(\.[^/]*)?$)")

REDACTED = "[REDACTED]"


def find_secrets(text: str) -> list[str]:
    """텍스트에 포함된 비밀값 패턴 이름을 반환한다."""
    return [pattern.pattern[:20] for pattern in SECRET_PATTERNS if pattern.search(text)]


def redact_secrets(text: str, path: str | None = None) -> str:
    """외부 모델로 보내기 전에 비밀값처럼 보이는 문자열을 가린다.

    ``path``가 설정 파일이면 따옴표 없는 ``KEY=value`` 줄의 값도 가린다.
    """
    for pattern in SECRET_PATTERNS:
        text = pattern.sub(REDACTED, text)
    text = _QUOTED_ASSIGNMENT.sub(rf"\1\2{REDACTED}\2", text)
    if path is not None and _CONFIG_FILE.search(path):
        text = _BARE_ASSIGNMENT.sub(rf"\1{REDACTED}", text)
    return text
