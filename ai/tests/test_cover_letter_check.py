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


def test_tech_names_over_the_cap_are_flagged_except_known_names() -> None:
    """기술 이름이 상한보다 많으면 개수와 함께 OFF_TOPIC 으로 걸고, 문항·카드 출처 이름과 AI 같은 줄임말은 세지 않으며,
    띄어 쓴 두 낱말 이름(Spring Boot)은 하나로 센다."""
    text = "gitory 에서 AI 로 React 화면을 나눴습니다. Spring Boot 빌드와 React 배포를 바꿨습니다."

    source = f"{SOURCE}\ngitory AI Spring Boot"

    within = check_draft(text, source=source, char_limit=1_000, missing_items=[], tech_limit=2, known_names="gitory")
    over = check_draft(text, source=source, char_limit=1_000, missing_items=[], tech_limit=1, known_names="gitory")

    assert within == []
    assert [(i.code, i.value, i.sentence_seq) for i in over] == [("OFF_TOPIC", "기술 이름 2개 (상한 1)", 2)]


@pytest.mark.parametrize(
    ("phrase", "name"),
    [("@BatchSize 를", "@BatchSize"), ("Page<Order> 로", "Page<Order>"), ("findByUserId 로", "findByUserId"),
     ("user_id 를", "user_id"), ("orderRepository.save( 를", "orderRepository.save("), ("`DISTINCT` 를", "`DISTINCT`"),
     ("Menu 도메인을", "Menu 도메인"), ("Product 커머스 규격을", "Product 커머스 규격"), ("@Valid DTO 를", "@Valid")],
    ids=["애너테이션", "제네릭", "camelCase", "snake_case", "메서드 호출", "백틱", "도메인", "규격", "애너테이션 DTO"],
)
def test_code_names_are_flagged_even_when_they_are_in_the_sources(phrase: str, name: str) -> None:
    """코드에 쓰는 이름은 카드에 있어도 OFF_TOPIC 으로 걸고, 그 이름이 든 문장을 가리킨다."""
    text = f"조회를 나눴습니다. {phrase} 바꿨습니다."

    issues = check_draft(text, source=f"{SOURCE}\n{phrase}", char_limit=1_000, missing_items=[])

    assert [(i.code, i.value, i.sentence_seq, i.rule) for i in issues] == [("OFF_TOPIC", name, 2, "CODE_NAME")]


@pytest.mark.parametrize(
    "phrase",
    ["API 규격", "AI 모듈", "JavaScript", "iOS", "my_shop"],
    ids=["줄임말 규격", "줄임말 모듈", "기술 이름", "제품 이름", "카드 출처 이름"],
)
def test_plain_words_and_card_source_names_are_not_code_names(phrase: str) -> None:
    """줄임말을 부르는 말·기술 이름·카드 출처 이름은 코드 이름이 아니다."""
    text = f"{phrase} 에 맞춰 화면을 나눴습니다."

    assert check_draft(text, source=f"{SOURCE}\n{phrase}", char_limit=1_000, missing_items=[], known_names="my_shop") == []


def test_repository_words_over_the_cap_are_flagged() -> None:
    """저장소 작업 말이 상한보다 많으면 개수와 함께 OFF_TOPIC 으로 걸고, 그 말이 가장 많은 문장을 가리킨다."""
    text = "배포를 나눴습니다. PR 을 머지했습니다. 이슈로 남겼습니다."

    def flagged(limit: int) -> list[tuple]:
        return [(i.code, i.value, i.sentence_seq, i.rule) for i in check_draft(
            text, source=f"{SOURCE}\nPR", char_limit=1_000, missing_items=[], repo_word_limit=limit)]

    assert flagged(3) == []
    assert flagged(2) == [("OFF_TOPIC", "저장소 작업 말 3번 (상한 2)", 2, "REPO_WORDS")]


def test_lookalike_words_are_not_counted_as_repository_words() -> None:
    """'나머지'·'머지않아'·'레포트' 와 사용자가 채울 빈칸 속 말은 저장소 작업 말로 세지 않는다."""
    text = "나머지 일은 머지않아 끝냈고 레포트를 냈습니다. [더 쓸 거리: PR 에서 맡은 일]"

    assert check_draft(text, source=f"{SOURCE}\nPR", char_limit=1_000, missing_items=[], repo_word_limit=0) == []


@pytest.mark.parametrize(
    ("text", "flagged"),
    [("결제에 관심이 생겼습니다. 정산 시간을 줄였습니다. 입사 후 결제 안정성에 기여하고 싶습니다.", False),
     ("결제에 관심이 생겼습니다. 결제 흐름을 다듬겠습니다. 정산 시간을 줄였습니다.", False),
     ("결제 흐름을 다듬겠습니다. 정산 시간을 줄였습니다.\n[더 쓸 거리: 지원 이유]", False),
     ("결제에 관심이 생겼습니다. 정산 시간을 줄였습니다.", True)],
    ids=["마지막 문장", "끝에서 두 번째 문장", "빈칸 줄은 건너뜀", "포부 없음"],
)
def test_motivation_draft_must_end_with_what_to_do_after_joining(text: str, flagged: bool) -> None:
    """지원 동기 글은 빈칸 줄을 뺀 마지막 두 문장에 입사 후 하고 싶은 일('겠습니다'·'싶습니다'·'기여')이 없으면 걸린다."""
    issues = check_draft(text, source=SOURCE, char_limit=1_000, missing_items=[], ambition=True)

    expected = [("OFF_TOPIC", "지원 동기 문항의 포부 문장 없음", None, "NO_AMBITION")] if flagged else []
    assert [(i.code, i.value, i.sentence_seq, i.rule) for i in issues] == expected
