"""자소서 행동증거: 문항 유형마다 볼 증거 4개, 재료에 그대로 있는 인용만 증거로 치기, 증거가 다 확인된 항목, 추론으로 써도 되는 증거."""

from __future__ import annotations

import pytest

from services.cover_letter_evidence import covered_items, guide_for, split_missing, verified_evidence


@pytest.mark.parametrize(
    ("theme", "first", "deep"),
    [
        ("COLLABORATION", "함께 일한 상대와 상황(의견이 갈렸다면 어떻게 갈렸나)", False),
        ("CONFLICT", "함께 일한 상대와 상황(의견이 갈렸다면 어떻게 갈렸나)", False),
        ("CHALLENGE", "문제를 구체적으로 정의한 것", True),
        ("MOTIVATION", "관심을 가진 계기", False),
        (None, "어떤 상황이었나", False),
    ],
    ids=["협업", "갈등은 협업과 같음", "도전은 문제 해결과 같음", "지원 동기", "유형 모름은 기본"],
)
def test_guide_for_theme(theme, first: str, deep: bool) -> None:
    """유형마다 증거 4개를 정하고, 기술을 깊게 다루는 건 문제 해결·도전뿐이며, 유형을 모르면 상황·행동·결과·배운 점을 본다."""
    guide = guide_for(theme)

    assert (len(guide.evidence), guide.evidence[0], guide.deep_tech) == (4, first, deep)


def test_only_quotes_found_in_material_count_as_evidence() -> None:
    """인용은 띄어쓰기만 달라도 재료에 있으면 치고, 재료에 없거나 너무 짧거나 없는 번호의 인용은 치지 않는다."""
    material = ["API 응답 모양을 두고 의견이 갈렸다", "회의를 열어 두 안을 견줬다"]
    quotes = [(1, "API 응답 모양을  두고\n의견이 갈렸다"), (2, "상대 의견을 끝까지 들었다"), (3, "열어"), (5, "회의를 열어 두 안을 견줬다")]

    assert verified_evidence(quotes, material) == {1}


def test_item_is_covered_only_when_all_its_evidences_are_verified() -> None:
    """행동처럼 증거 둘이 한 항목을 같이 쓰면 둘 다 확인돼야 그 항목을 묻지 않는다."""
    guide = guide_for("COLLABORATION")

    assert covered_items(guide, {1, 2}) == {"SITUATION"}
    assert covered_items(guide, {1, 2, 3}) == {"SITUATION", "ACTION"}


@pytest.mark.parametrize(
    ("theme", "inferable"),
    [
        ("COLLABORATION", []),
        ("LEADERSHIP", []),
        ("PROBLEM_SOLVING", ["결과와 아쉬운 점·더 나은 방법"]),
        ("FAILURE", ["원인을 돌아본 것"]),
        ("GROWTH", ["입사 후 쓸 곳"]),
        ("MOTIVATION", ["입사 후 기여"]),
        (None, ["배운 점이나 직무와 이어지는 점"]),
    ],
    ids=["협업", "리더십", "문제 해결", "실패", "성장", "지원 동기", "유형 모름"],
)
def test_only_interpretation_evidence_can_be_inferred(theme, inferable: list[str]) -> None:
    """재료에 없는 증거 가운데 해석(돌아본 점·아쉬운 점·입사 후 쓸 곳·기여·배운 점)만 추론으로 쓰고, 사실 증거는 빈칸으로 둔다."""
    guide = guide_for(theme)

    facts, inferred = split_missing(guide, set())

    assert (inferred, facts) == (inferable, [text for text in guide.evidence if text not in inferable])
