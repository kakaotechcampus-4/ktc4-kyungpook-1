"""지어낸 수치 검사(unsupported_metrics)의 경계 사례를 고정한다."""

from __future__ import annotations

import pytest

from services.evidence_checks import unsupported_metrics


@pytest.mark.parametrize(
    ("text", "source"),
    [
        ("응답 시간을 5% 줄였다", "캐시로 응답 15% 단축"),  # 원문 숫자의 일부만 겹친다
        ("오류율을 30% 줄였다", "필터 추가"),
        ("지연이 100ms 줄었다", "@@ -100,7 +100,9 @@\n+cache()"),  # hunk 줄 번호는 근거가 아니다
        ("최대30% 개선", "캐시 추가"),  # 한글 뒤에 붙은 수치도 수치다
        ("3배 빨라졌다", "캐시 추가"),
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
    ],
)
def test_accepts_non_metrics_and_sourced_numbers(text: str, source: str) -> None:
    assert unsupported_metrics(text, source) == []
