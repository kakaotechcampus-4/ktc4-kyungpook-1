"""자소서 초안이 근거를 벗어났거나 글자 수를 넘거나 목표에 못 미치는지 코드로 찾는다(검사기). 문항에 꼭 있어야 하는 재료도 여기서 정한다.

자소서에는 '5명·6개월·1위' 처럼 단위가 제각각인 수치가 많아, 카드 초안 검사(evidence_checks)의 성능 단위 수치와 함께
따로 선 숫자가 근거에도 따로 선 숫자로 있는지 본다. 한글로 쓴 숫자·이름(다섯 명·레디스)은 코드로 잡지 못해 전체 검토가 본다.
같은 뜻의 다른 표기(k8s·Kubernetes)도 걸리므로, 고칠지 둘지는 다음 행동 판단이 정한다.
"""

from __future__ import annotations

import math
import re
from dataclasses import dataclass
from typing import Optional

from schemas.cover_letter import CardDetail, DraftProblemCode, KnownAnswer, MaterialItem
from services.evidence_checks import unsupported_metrics

#: 문항을 쓰려면 꼭 있어야 하는 항목으로, 지원 동기 문항은 지원 이유다.
#: 성장·실패 문항은 배운 점이 없으면 결과에서 끝나거나 지어낸 깨달음이 들어가서 배운 점까지 본다.
REQUIRED_ITEMS: dict[str, tuple[MaterialItem, ...]] = {
    "EXPERIENCE": ("SITUATION", "ACTION", "RESULT"),
    "LESSON": ("SITUATION", "ACTION", "RESULT", "LEARNING"),
    "MOTIVATION": ("REASON",),
}
_LESSON_THEMES = ("GROWTH", "FAILURE")
ITEM_OF_SLOT: dict[str, MaterialItem] = {"S": "SITUATION", "A": "ACTION", "R": "RESULT"}

ITEM_LABELS: dict[str, str] = {
    "SITUATION": "상황", "ACTION": "행동", "RESULT": "결과", "LEARNING": "배운 점",
    "TRIGGER": "계기", "JOB_LINK": "직무 연결", "REASON": "지원 이유",
}
_BLANK_HINTS: dict[str, str] = {
    "SITUATION": "언제, 어디서, 무슨 일이었는지 적어 주세요",
    "ACTION": "직접 한 일을 적어 주세요",
    "RESULT": "바뀐 점이나 숫자를 적어 주세요",
    "LEARNING": "배운 점을 적어 주세요",
    "TRIGGER": "시작한 계기를 적어 주세요",
    "JOB_LINK": "지원 직무와 이어지는 점을 적어 주세요",
    "REASON": "이 회사를 고른 이유를 적어 주세요",
}
#: 카드 고르기 답을 못 받아 유형을 모를 때 지원 동기 문항을 알아보는 말.
_MOTIVATION_WORDS = ("지원 동기", "지원한 동기", "지원 이유", "지원한 이유", "지원하게 된", "입사 후", "포부")
_LESSON_WORDS = ("성장", "실패", "배운")
#: 재료가 모자라 목표 글자 수에 못 미칠 때 모델이 넣는 빈칸으로, 무엇을 더 쓰면 되는지를 사용자가 화면에서 보고 채운다.
_MORE_BLANK = re.compile(r"\[더 쓸 거리: [^\[\]\n]{1,60}\]")

#: 영문으로 시작하는 낱말(React, Node.js, C++, k8s). 한 글자는 낱말로 보지 않는다.
_LATIN_TERM = re.compile(r"(?<![A-Za-z0-9])[A-Za-z][A-Za-z0-9+#.\-]*[A-Za-z0-9+#]")
#: 영문·숫자에 붙지 않은 숫자(v2·k8s·OAuth2 의 숫자는 제외). 뒤에 붙은 단위는 화면에 보일 값으로만 쓴다.
_NUMBER = re.compile(r"(?<![A-Za-z0-9_.,])(\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?)(?![0-9A-Za-z])(%p|%|[가-힣]{1,2})?")
#: 문장 끝(. ! ? …) 뒤 공백이나 줄바꿈에서 나눈다. 40.5% 의 점은 뒤에 공백이 없어 나누지 않는다.
_SENTENCE_BREAK = re.compile(r"(?<=[.!?…])\s+|\n+")


@dataclass(frozen=True)
class DraftIssue:
    code: DraftProblemCode
    value: Optional[str]
    sentence_seq: Optional[int]


def item_group(theme: Optional[str], question_text: str) -> str:
    """유형을 모르면(카드 고르기 실패) 문항 문장으로 지원 동기·성장과 실패 문항인지 보고, 아니면 경험 문항으로 본다."""
    if theme is not None:
        if theme == "MOTIVATION":
            return "MOTIVATION"
        return "LESSON" if theme in _LESSON_THEMES else "EXPERIENCE"
    if any(word in question_text for word in _MOTIVATION_WORDS):
        return "MOTIVATION"
    return "LESSON" if any(word in question_text for word in _LESSON_WORDS) else "EXPERIENCE"


def answers_about_primary(answers: list[KnownAnswer], primary: Optional[CardDetail]) -> list[KnownAnswer]:
    """중심 경험의 재료로 칠 답. 보조 카드에 대한 답은 중심 경험의 결과가 아니라서 빼고, 카드와 상관없는 답은 넣는다."""
    primary_id = primary.card_id if primary is not None else None
    return [answer for answer in answers if answer.card_id is None or answer.card_id == primary_id]


def answer_about(card_id: Optional[int], primary: Optional[CardDetail], supporting: Optional[CardDetail]) -> Optional[str]:
    """답이 중심·보조 카드 중 어느 경험에 대한 것인지 모델에 알린다. 카드와 상관없거나 이번에 안 쓰는 카드의 답은 None."""
    for role, card in (("PRIMARY", primary), ("SUPPORTING", supporting)):
        if card is not None and card.card_id == card_id:
            return role
    return None


def required_missing(
    group: str, primary: Optional[CardDetail], answers: list[KnownAnswer], memo: Optional[str],
) -> list[MaterialItem]:
    """꼭 있어야 하는데 중심 카드 문장·중심 경험의 답·메모 어디에도 없는 항목."""
    present: set[str] = {answer.item for answer in answers_about_primary(answers, primary)}
    if primary is not None:
        present.update(ITEM_OF_SLOT[s.star_slot] for s in primary.statements if s.star_slot in ITEM_OF_SLOT)
    if group == "MOTIVATION" and memo and memo.strip():
        present.add("REASON")
    return [item for item in REQUIRED_ITEMS[group] if item not in present]


def blank(item: MaterialItem) -> str:
    """재료가 없는 자리에 넣는 빈칸으로, 사용자가 화면에서 채운다."""
    return f"[{ITEM_LABELS[item]}: {_BLANK_HINTS[item]}]"


def has_blank(text: str) -> bool:
    return has_more_blank(text) or any(blank(item) in text for item in ITEM_LABELS)  # type: ignore[arg-type]


def has_more_blank(text: str) -> bool:
    return _MORE_BLANK.search(text) is not None


def target_length(char_limit: int, ratio: float) -> int:
    return math.ceil(char_limit * ratio)


def countable_length(text: str) -> int:
    """공백을 포함한 글자 수로, 사용자가 채울 빈칸은 세지 않는다."""
    text = _MORE_BLANK.sub("", text)
    for item in ITEM_LABELS:
        text = text.replace(blank(item), "")  # type: ignore[arg-type]
    return len(text)


def split_sentences(text: str) -> list[str]:
    return [part.strip() for part in _SENTENCE_BREAK.split(text) if part.strip()]


def unsourced_terms(text: str, source: str) -> list[str]:
    known = {term.casefold() for term in _LATIN_TERM.findall(source)}
    found: list[str] = []
    for term in _LATIN_TERM.findall(text):
        if term.casefold() not in known and term not in found:
            found.append(term)
    return found


def unsourced_numbers(text: str, source: str) -> list[str]:
    """근거에 따로 선 숫자로 없는 숫자를 단위와 함께 돌려준다. 단위는 보지 않는다."""
    known = {_normalize(match.group(1)) for match in _NUMBER.finditer(source)}
    found: list[str] = []
    for match in _NUMBER.finditer(text):
        value = match.group(0)
        if _normalize(match.group(1)) not in known and value not in found:
            found.append(value)
    return found


def invented_numbers(text: str, source: str) -> list[str]:
    """근거에 없는 수치. 성능 단위(ms·%·배 등)는 같은 단위로, 그 밖의 숫자는 숫자만 근거에 있는지 본다."""
    numbers = unsupported_metrics(text, source)
    flagged = {_normalize(match.group(1)) for metric in numbers if (match := _NUMBER.search(metric))}
    numbers += [value for value in unsourced_numbers(text, source)
                if _normalize(_NUMBER.match(value).group(1)) not in flagged]
    return numbers


def check_draft(
    text: str, *, source: str, char_limit: int, missing_items: list[MaterialItem], target: int = 0,
) -> list[DraftIssue]:
    """초안에서 걸린 곳을 찾고, 수치·낱말은 그 값이 처음 나온 문장 번호를 함께 돌려준다."""
    issues = [DraftIssue("UNSOURCED_NUMBER", value, sentence_of(value, text)) for value in invented_numbers(text, source)]
    issues += [DraftIssue("UNSOURCED_TERM", term, sentence_of(term, text)) for term in unsourced_terms(text, source)]
    issues += [DraftIssue("MISSING_BLANK", item, None) for item in missing_items if blank(item) not in text]
    over = countable_length(text) - char_limit
    if over > 0:
        issues.append(DraftIssue("OVER_LIMIT", str(over), None))
    short = target - countable_length(text)
    if short > 0 and not has_more_blank(text):
        issues.append(DraftIssue("UNDER_LENGTH", str(short), None))
    return issues


def sentence_of(value: str, text: str) -> Optional[int]:
    """값이 낱말·숫자로 따로 선 첫 자리의 문장 번호. 'JavaScript' 안의 'Java', '130%' 안의 '30%' 는 건너뛴다."""
    match = re.search(rf"(?<![0-9A-Za-z.,]){re.escape(value)}(?![0-9A-Za-z])", text)
    if match is None:
        return None
    seq, start = 0, 0
    for end, next_start in [(brk.start(), brk.end()) for brk in _SENTENCE_BREAK.finditer(text)] + [(len(text), len(text))]:
        if text[start:end].strip():
            seq += 1
            if start <= match.start() < end:
                return seq
        start = next_start
    return None


def _normalize(number: str) -> str:
    return number.replace(",", "")
