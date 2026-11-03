"""자소서 문항 유형마다 글이 보여 줄 행동증거 4개와, 그 유형에서 질문·쓰기가 기술 이야기를 얼마나 다룰지 정한다.

회사는 문항마다 역량을 정하고 그 행동증거가 몇 개 드러나는지로 채점하며, 문항과 무관한 내용·문항 사이 같은 내용은 깎는다.
그래서 쓰기는 증거가 드러나게 쓰고, 질문하기는 카드에 없는 증거를 묻고, 재료가 보여 줄 수 있는 증거 수로 쓸지를 정한다.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable, Literal, Optional

from schemas.cover_letter import MaterialItem

#: 재료가 보여 줄 수 있는 증거가 이만큼이면 그대로 쓰고, 하나라도 있으면 빠진 사실은 빈칸·해석은 추론으로 채워 쓰고,
#: 하나도 없으면 글을 쓰지 않는다
READY_EVIDENCE = 3
PARTIAL_EVIDENCE = 1
#: 인용으로 칠 가장 짧은 글자 수. '했다' 같은 짧은 말은 어느 문장에나 있어 증거가 되지 않는다
MIN_QUOTE_CHARS = 8

Readiness = Literal["READY", "PARTIAL", "NOT_READY"]


@dataclass(frozen=True)
class ThemeGuide:
    evidence: tuple[str, str, str, str]
    #: 증거마다 묻는 항목. 같은 카드에 같은 항목은 답 하나만 저장돼, 행동 쪽 증거 둘은 ACTION 하나를 같이 쓴다
    items: tuple[MaterialItem, MaterialItem, MaterialItem, MaterialItem]
    #: 증거가 해석(돌아본 점·아쉬운 점·직무 연결·포부)이라 재료에 없으면 AI 가 추론 문장으로 써도 되는지. 사실 증거는 빈칸으로 둔다
    inferable: tuple[bool, bool, bool, bool]
    angles: dict[str, str]
    tech: str
    deep_tech: bool

    def angles_for(self, items: Iterable[str]) -> dict[str, str]:
        return {item: self.angles[item] for item in items if item in self.angles}


_COMMON_ANGLES: dict[str, str] = {
    "LEARNING": "그 일을 겪고 일하는 방식이나 생각이 어떻게 달라졌는지",
    "TRIGGER": "그 일을 시작하거나 맡게 된 계기",
    "JOB_LINK": "그 경험이 지원 직무의 어떤 일과 이어지는지",
    "REASON": "이 직무·회사에 관심을 가진 계기와 지원한 이유",
}
_LIGHT_TECH = "문항이 기술을 묻지 않으므로 기술 세부(구현 방법·설정 이름)는 무슨 문제였는지 알 만큼 한두 문장으로만 씁니다."

_COLLABORATION = ThemeGuide(
    evidence=("함께 일한 상대와 상황(의견이 갈렸다면 어떻게 갈렸나)", "상대나 팀의 사정을 듣고 이해하려 한 행동",
              "내가 제안·조율하거나 이어받아 맞춘 방법", "합의와 팀에 달라진 점"),
    items=("SITUATION", "ACTION", "ACTION", "RESULT"),
    inferable=(False, False, False, False),
    angles={**_COMMON_ANGLES,
            "SITUATION": "누구와 어떤 일을 함께 했고, 의견이 갈렸다면 어떤 점에서 갈렸는지",
            "ACTION": "상대나 팀의 사정을 어떻게 듣고, 무엇을 제안·조율하거나 이어받은 일을 어떻게 맞춰 갔는지(그때 한 말·한 일)",
            "RESULT": "어떻게 합의했거나 일이 이어졌고, 그 뒤 팀이 일하는 방식이나 관계가 어떻게 달라졌는지"},
    tech=_LIGHT_TECH,
    deep_tech=False,
)
_LEADERSHIP = ThemeGuide(
    evidence=("방향·목표를 정한 것", "일을 나누고 사람을 움직인 방법", "막히거나 부딪힌 걸 푼 행동", "팀의 결과"),
    items=("SITUATION", "ACTION", "ACTION", "RESULT"),
    inferable=(False, False, False, False),
    angles={**_COMMON_ANGLES,
            "SITUATION": "팀이 어떤 상황이었고 무엇을 목표로 정했는지",
            "ACTION": "일을 어떻게 나누고 사람을 어떻게 움직였는지, 막히거나 부딪혔을 때 무엇을 했는지",
            "RESULT": "팀이 무엇을 해냈고 팀원에게 무엇이 달라졌는지"},
    tech=_LIGHT_TECH,
    deep_tech=False,
)
_PROBLEM_SOLVING = ThemeGuide(
    evidence=("문제를 구체적으로 정의한 것", "선택지와 그걸 고른 기술적 근거", "해결 과정에서 막힌 곳과 넘은 방법",
              "결과와 아쉬운 점·더 나은 방법"),
    items=("SITUATION", "ACTION", "ACTION", "RESULT"),
    inferable=(False, False, False, True),
    angles={**_COMMON_ANGLES,
            "SITUATION": "무엇이 문제였고 누구에게 어떤 영향을 줬는지",
            "ACTION": "어떤 방법들을 견줬고 왜 그 방법을 골랐는지, 하다가 어디서 막혔고 어떻게 넘었는지",
            "RESULT": "결과가 어땠고, 아쉬웠던 점이나 다시 한다면 다르게 할 점"},
    tech="기술 깊이를 살립니다. 견준 선택지와 고른 근거를 쓰고, 핵심 기술 이름은 처음 나올 때 무엇인지 쉬운 말로 풉니다.",
    deep_tech=True,
)
_FAILURE = ThemeGuide(
    evidence=("무엇이 잘못됐나", "원인을 돌아본 것", "그 뒤 바꾼 행동", "바꾼 뒤 달라진 점"),
    items=("SITUATION", "LEARNING", "ACTION", "RESULT"),
    inferable=(False, True, False, False),
    angles={**_COMMON_ANGLES,
            "SITUATION": "무엇이 어떻게 잘못됐는지",
            "LEARNING": "왜 그렇게 됐는지 돌아본 것과 그 뒤 달라진 생각",
            "ACTION": "그 뒤 무엇을 바꿔 다시 했는지",
            "RESULT": "바꾼 뒤 무엇이 달라졌는지"},
    tech="원인을 설명하는 데 필요한 만큼만 기술을 씁니다.",
    deep_tech=False,
)
_GROWTH = ThemeGuide(
    evidence=("부족함을 느낀 계기", "꾸준히 익힌 행동", "달라진 점", "입사 후 쓸 곳"),
    items=("TRIGGER", "ACTION", "RESULT", "JOB_LINK"),
    inferable=(False, False, False, True),
    angles={**_COMMON_ANGLES,
            "SITUATION": "부족함을 느낀 장면",
            "TRIGGER": "무엇이 부족하다고 느꼈고 그걸 느낀 장면",
            "ACTION": "그걸 익히려고 꾸준히 한 일(얼마나·어떻게)",
            "RESULT": "익힌 뒤 무엇을 할 수 있게 됐는지"},
    tech="배운 기술은 이름 정도만 쓰고 구현 세부는 쓰지 않습니다.",
    deep_tech=False,
)
_MOTIVATION = ThemeGuide(
    evidence=("관심을 가진 계기", "그동안 준비한 노력", "맞는 이유(경험 한 장면)", "입사 후 기여"),
    items=("REASON", "ACTION", "JOB_LINK", "JOB_LINK"),
    inferable=(False, False, False, True),
    angles={**_COMMON_ANGLES,
            "SITUATION": "이 직무에 관심을 갖게 된 장면",
            "ACTION": "이 직무를 위해 그동안 준비한 일",
            "RESULT": "그 준비로 할 수 있게 된 일"},
    tech="경험 한 장면 안에서만 기술을 짧게 씁니다.",
    deep_tech=False,
)
_DEFAULT = ThemeGuide(
    evidence=("어떤 상황이었나", "내가 한 행동과 그렇게 한 이유", "결과", "배운 점이나 직무와 이어지는 점"),
    items=("SITUATION", "ACTION", "RESULT", "LEARNING"),
    inferable=(False, False, False, True),
    angles={**_COMMON_ANGLES,
            "SITUATION": "언제 어떤 일이었고 무엇이 문제였는지",
            "ACTION": "직접 한 일과 그렇게 한 이유",
            "RESULT": "그 뒤 무엇이 달라졌는지"},
    tech="문항이 기술을 묻지 않으면 기술 세부는 한두 문장으로 줄입니다.",
    deep_tech=False,
)
_GUIDES: dict[str, ThemeGuide] = {
    "COLLABORATION": _COLLABORATION,
    "CONFLICT": _COLLABORATION,
    "LEADERSHIP": _LEADERSHIP,
    "PROBLEM_SOLVING": _PROBLEM_SOLVING,
    "CHALLENGE": _PROBLEM_SOLVING,
    "FAILURE": _FAILURE,
    "GROWTH": _GROWTH,
    "MOTIVATION": _MOTIVATION,
}


def guide_for(theme: Optional[str]) -> ThemeGuide:
    """카드 고르기를 못 받아 유형을 모르면 상황·행동·결과·배운 점을 보는 기본 증거를 쓴다."""
    return _GUIDES.get(theme or "", _DEFAULT)


def verified_evidence(quotes: Iterable[tuple[int, str]], material: Iterable[str]) -> set[int]:
    """인용이 재료 한 줄 안에 글자 그대로 있는 증거 번호만 남긴다. 모델이 지어낸 인용으로 증거가 있다고 치지 않게 한다."""
    lines = [_squash(text) for text in material if text and text.strip()]
    verified: set[int] = set()
    for number, quote in quotes:
        text = _squash(quote)
        if 1 <= number <= 4 and len(text) >= MIN_QUOTE_CHARS and any(text in line for line in lines):
            verified.add(number)
    return verified


def readiness(supported: int) -> Readiness:
    if supported >= READY_EVIDENCE:
        return "READY"
    return "PARTIAL" if supported >= PARTIAL_EVIDENCE else "NOT_READY"


def split_missing(guide: ThemeGuide, verified: set[int]) -> tuple[list[str], list[str]]:
    """재료로 확인되지 않은 증거를 사실(빈칸으로 둠)과 해석(추론 문장으로 씀)으로 나눈다."""
    facts: list[str] = []
    inferable: list[str] = []
    for number, (text, can_infer) in enumerate(zip(guide.evidence, guide.inferable), start=1):
        if number not in verified:
            (inferable if can_infer else facts).append(text)
    return facts, inferable


def covered_items(guide: ThemeGuide, verified: set[int]) -> set[str]:
    """그 항목으로 묻는 증거가 모두 재료로 확인된 항목. 행동 쪽 증거가 하나만 확인되면 행동은 아직 물을 거리가 있다."""
    by_item: dict[str, list[int]] = {}
    for number, item in enumerate(guide.items, start=1):
        by_item.setdefault(item, []).append(number)
    return {item for item, numbers in by_item.items() if all(number in verified for number in numbers)}


def _squash(text: str) -> str:
    return " ".join(text.split())
