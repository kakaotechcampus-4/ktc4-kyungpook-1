"""자소서 검사기: 근거에 없는 수치·영문 낱말, 빠진 빈칸, 글자 수를 코드로 찾는다."""

from __future__ import annotations

import pytest

from services.cover_letter_check import blank, check_draft, split_sentences

SOURCE = "배포 한 번에 40분이 걸렸다\nReact 와 JavaScript 화면의 빌드를 나눴다\n배포 시간을 12분으로 줄였다\n응답 130% 개선"


def _issues(text: str, *, limit: int = 1_000, missing: tuple = ()) -> list[tuple]:
    return [(issue.code, issue.value, issue.sentence_seq)
            for issue in check_draft(text, source=SOURCE, char_limit=limit, missing_items=list(missing))]


def test_number_not_in_sources_is_flagged_with_its_sentence() -> None:
    """근거에 없는 수치는 그 수치가 든 문장 번호와 함께 걸리고, 근거에 있는 수치는 걸리지 않는다."""
    assert _issues("배포를 40분에서 12분으로 줄였습니다. 응답도 30% 빨라졌습니다.") == [("UNSOURCED_NUMBER", "30%", 2)]


@pytest.mark.parametrize("phrase", ["팀원 5명과", "6개월 동안", "3천만 원을", "1위를"])
def test_number_with_any_unit_is_flagged_when_not_in_sources(phrase: str) -> None:
    """자소서에 흔한 사람 수·기간·돈·순위처럼 성능 단위가 아닌 수치도 근거에 없으면 걸린다."""
    assert [code for code, _, _ in _issues(f"{phrase} 빌드를 나눴습니다.")] == ["UNSOURCED_NUMBER"]


def test_latin_term_not_in_sources_is_flagged_ignoring_case() -> None:
    """근거에 없는 영문 낱말(기술 이름)은 걸리고, 근거에 있는 낱말은 대소문자가 달라도 걸리지 않는다."""
    assert _issues("react 화면에 Redis 캐시를 붙였습니다.") == [("UNSOURCED_TERM", "Redis", 1)]


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("JavaScript 화면을 나눴습니다. 서버는 Java 로 짰습니다.", ("UNSOURCED_TERM", "Java", 2)),
        ("응답을 130% 개선했습니다. 오류는 30% 줄었습니다.", ("UNSOURCED_NUMBER", "30%", 2)),
    ],
    ids=["JavaScript 안의 Java", "130% 안의 30%"],
)
def test_flagged_value_points_to_the_sentence_where_it_stands_alone(text: str, expected: tuple) -> None:
    """걸린 값의 문장 번호는 다른 낱말·숫자 속 글자가 아니라 따로 선 자리로 정한다."""
    assert _issues(text) == [expected]


def test_missing_blank_is_flagged_until_it_is_in_the_text() -> None:
    """끝내 답을 받지 못한 항목의 빈칸이 글에 없으면 걸리고, 글자 그대로 들어 있으면 걸리지 않는다."""
    assert _issues("빌드를 나눴습니다.", missing=("RESULT",)) == [("MISSING_BLANK", "RESULT", None)]
    assert _issues(f"빌드를 나눴습니다. {blank('RESULT')}", missing=("RESULT",)) == []


def test_over_limit_counts_spaces_but_not_blanks() -> None:
    """글자 수는 공백을 포함해 세되 사용자가 채울 빈칸은 세지 않고, 상한을 넘은 만큼을 값으로 돌려준다."""
    text = "가 " * 50 + "나"

    assert _issues(text, limit=101) == []
    assert _issues(text, limit=100) == [("OVER_LIMIT", "1", None)]
    assert _issues(f"{text}{blank('RESULT')}", limit=101, missing=("RESULT",)) == []


def test_sentences_split_at_sentence_end_not_at_decimal_point() -> None:
    """문장은 마침표·말줄임표 뒤 공백이나 줄바꿈에서 나누고, 40.5% 처럼 숫자 안의 점에서는 나누지 않는다."""
    assert split_sentences("응답을 40.5% 줄였습니다. 그런데… 다음 문장입니다!\n[결과: 적어 주세요]") == [
        "응답을 40.5% 줄였습니다.", "그런데…", "다음 문장입니다!", "[결과: 적어 주세요]",
    ]
