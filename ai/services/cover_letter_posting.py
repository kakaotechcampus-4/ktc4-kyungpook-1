"""공고 상세에서 시작한 자소서가 공고 조건과 매칭 판단을 카드 고르기·질문·초안에 쓰는 규칙."""

from __future__ import annotations

from typing import Iterable, Optional

from schemas.cover_letter import Posting

#: 카드가 채운 공고 조건의 무게. 낮을수록 앞이다.
REQUIRED_RANK = 0
PREFERRED_RANK = 1
NO_CONDITION_RANK = 2

#: 재료 질문 하나가 짚는 ✕ 조건 수. 질문 한 줄에 조건을 여럿 늘어놓으면 무엇을 답할지 흐려진다.
MAX_GAPS = 2


def condition_ranks(posting: Optional[Posting]) -> dict[int, int]:
    """카드마다 채운 조건 중 가장 무거운 것. 판단이 없거나 ✕ 인 조건은 세지 않는다."""
    ranks: dict[int, int] = {}
    if posting is None:
        return ranks
    for condition in posting.conditions:
        if condition.status != "MET":
            continue
        rank = REQUIRED_RANK if condition.type == "REQUIRED" else PREFERRED_RANK
        for card_id in condition.met_by:
            ranks[card_id] = min(rank, ranks.get(card_id, NO_CONDITION_RANK))
    return ranks


def met_conditions(posting: Optional[Posting], card_ids: Iterable[Optional[int]]) -> list[dict[str, str]]:
    """그 카드들이 채운 조건. 초안·카드 고르기가 카드 사실을 공고 조건과 잇는 데만 쓴다."""
    if posting is None:
        return []
    cards = {card_id for card_id in card_ids if card_id is not None}
    return [{"type": condition.type, "text": condition.text} for condition in posting.conditions
            if condition.status == "MET" and cards.intersection(condition.met_by)]


def gaps(posting: Optional[Posting]) -> list[str]:
    """채운 카드가 없는 필수 조건. 우대 조건은 모자라도 지원에 걸리지 않아 묻지 않는다."""
    if posting is None:
        return []
    return [condition.text for condition in posting.conditions
            if condition.type == "REQUIRED" and condition.status == "NOT_MET"][:MAX_GAPS]


def company_facts(posting: Optional[Posting]) -> list[str]:
    """초안의 숫자·낱말 근거로 쳐도 되는 공고 글. 조건 문장은 내가 해 봤다는 근거가 아니어서 넣지 않는다."""
    if posting is None:
        return []
    return [posting.company, posting.title, *posting.responsibilities]
