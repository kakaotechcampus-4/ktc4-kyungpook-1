"""지어낸 수치 검사(unsupported_metrics)와 커밋 번호 맞춤(match_commit_sha)의 경계 사례를 고정한다."""

from __future__ import annotations

import logging

import pytest

from services.evidence_checks import match_commit_sha, unsupported_metrics

REAL_SHA = "0409013fb1905a33719fd42efbe7b03f07c61c9b"
OTHER_SHA = "7430348" + "0" * 33
SAME_PREFIX_SHA = REAL_SHA[:7] + "e" * 33


@pytest.mark.parametrize(
    "written",
    [
        REAL_SHA,
        REAL_SHA.upper(),
        f"  {REAL_SHA} ",
        REAL_SHA + "9",  # 끝에 글자를 하나 더 붙였다
        "0409013fb1905a33719fd42ef7be03f07c61c9b",  # 가운데 be7b 를 7be 로 적어 한 글자가 빠졌다(39자)
        REAL_SHA[:7],
    ],
)
def test_matches_written_sha_to_the_one_input_commit(written: str) -> None:
    assert match_commit_sha(written, [OTHER_SHA, REAL_SHA], "시험") == REAL_SHA


def test_returns_the_input_sha_as_written_in_the_input() -> None:
    assert match_commit_sha(REAL_SHA + "9", [REAL_SHA.upper()], "시험") == REAL_SHA.upper()


def test_exact_sha_wins_even_when_another_commit_shares_its_prefix() -> None:
    assert match_commit_sha(REAL_SHA, [SAME_PREFIX_SHA, REAL_SHA], "시험") == REAL_SHA


@pytest.mark.parametrize(
    ("written", "known"),
    [
        (REAL_SHA[:6], [REAL_SHA]),  # 7자보다 짧으면 지어낸 번호일 수 있다
        ("c" * 40, [REAL_SHA, OTHER_SHA]),
        (REAL_SHA[:7] + "f" * 33, [REAL_SHA, SAME_PREFIX_SHA]),  # 앞 7자가 두 커밋과 맞는다
    ],
)
def test_drops_written_sha_that_fits_no_single_input_commit(written: str, known: list[str]) -> None:
    assert match_commit_sha(written, known, "시험") is None


def test_logs_only_shas_matched_by_prefix(caplog: pytest.LogCaptureFixture) -> None:
    with caplog.at_level(logging.WARNING, logger="services.evidence_checks"):
        match_commit_sha(REAL_SHA, [REAL_SHA], "STAR 초안")
        match_commit_sha(REAL_SHA[:7] + "f" * 33, [REAL_SHA, SAME_PREFIX_SHA], "STAR 초안")
        match_commit_sha(REAL_SHA + "9", [REAL_SHA], "STAR 초안")

    assert [record.getMessage() for record in caplog.records] == [
        "STAR 초안 근거 커밋 번호 보정: 입력과 다른 41자 번호를 앞 7자로 0409013 에 맞춤"
    ]


@pytest.mark.parametrize(
    ("text", "source"),
    [
        ("응답 시간을 5% 줄였다", "캐시로 응답 15% 단축"),  # 원문 숫자의 일부만 겹친다
        ("오류율을 30% 줄였다", "필터 추가"),
        ("지연이 100ms 줄었다", "@@ -100,7 +100,9 @@\n+cache()"),  # hunk 줄 번호는 근거가 아니다
        ("최대30% 개선", "캐시 추가"),  # 한글 뒤에 붙은 수치도 수치다
        ("3배 빨라졌다", "캐시 추가"),
        ("30건을 처리했다", "fix: 결제 오류 수정 (#30)"),  # 이슈 번호는 근거가 아니다
        ("10건을 처리했다", "chore: 2026-10-10 배포"),  # 날짜의 숫자도 근거가 아니다
        ("지연이 200ms 줄었다", "200 응답 코드 처리"),  # 성능 단위는 같은 단위로 적혀 있어야 한다
        ("작업이 3시간 걸렸다", "캐시 추가"),
        ("5x 빨라졌다", "캐시 추가"),
        ("점유율이 2%p 올랐다", "점유율 2% 기준 추가"),  # %p 는 % 와 다른 단위다
        ("파일 5개를 정리했다", "리팩터링"),
        ("1만 명이 사용했다", "MAX_USERS = 1"),  # 만 단위는 근거에도 같은 단위로 있어야 한다
        ("배포 10:30 에 30건을 처리했다", "배포 10:30 시작"),  # 시각의 숫자도 근거가 아니다
    ],
)
def test_flags_numbers_not_in_source(text: str, source: str) -> None:
    assert unsupported_metrics(text, source)


@pytest.mark.parametrize(
    ("text", "source"),
    [
        ("OAuth2 배포를 추가했다", "로그인 흐름 추가"),  # 영문에 붙은 숫자는 수치가 아니다
        ("v2.0 배포", "릴리스 정리"),
        ("2 배포 단계로 나눴다", "단계 분리"),  # 띄어 쓴 "배" 뒤에 글자가 이어지면 단어다
        ("1,000건 처리", "LIMIT = 1000"),  # 천 단위 쉼표
        ("1000건 처리", "최대 1,000건"),
        ("질문을 2회로 제한", "MAX_TURNS = 2"),
        ("응답 15% 단축", "캐시로 응답 15% 단축"),
        ("지연이 200ms 줄었다", "응답 200ms 초과 시 재시도"),
        ("지연이 200ms 줄었다", "응답 200밀리초 초과 시 재시도"),  # 같은 뜻의 단위
        ("오류가 30% 줄었다", "오류 30퍼센트 감소"),
        ("5배 빨라졌다", "5x faster"),
        ("점유율이 2%p 올랐다", "점유율 2%p 상승"),
        ("작업이 3시간 걸렸다", "배치 3시간 소요"),
        ("모듈 12개를 정리했다", "12 modules 정리"),  # 개수 단위는 숫자만 맞으면 된다
        ("1만 명이 사용했다", "가입자 1만 명"),
        ("3개월 동안 운영했다", "운영 기간 정리"),
        ("5x5 격자를 그렸다", "격자 추가"),
        ("2 x 3 행렬로 나눴다", "행렬 추가"),  # 띄어 쓴 x 는 곱셈 기호로 본다
    ],
)
def test_accepts_non_metrics_and_sourced_numbers(text: str, source: str) -> None:
    assert unsupported_metrics(text, source) == []
