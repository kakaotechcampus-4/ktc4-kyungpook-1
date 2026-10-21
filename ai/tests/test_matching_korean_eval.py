"""한국어 매칭 평가 fixture 와 점수 계산 테스트(모델을 내려받지 않고 네트워크도 쓰지 않는다)."""

from __future__ import annotations

import html
import json
from pathlib import Path
from typing import Any

import pytest

from scripts.evaluate_job_matching_korean import (
    extract_conditions,
    fetch_boards,
    hydrate_fixture,
    load_fixture,
    score_threshold,
    text_sha256,
)

FIXTURE = Path(__file__).parents[1] / "evals" / "job_matching_korean_cases.json"


def _board_json(content: str, job_id: int = 11) -> dict[str, Any]:
    return {"jobs": [{"id": job_id, "content": html.escape(content)}]}


def _fake_fixture(texts: list[str]) -> dict[str, Any]:
    key = "GREENHOUSE:acme-11"
    return {
        "sources": [{"company": "acme", "api": "https://boards-api.greenhouse.io/v1/boards/acme/jobs?content=true"}],
        "posting_language": {key: "KO"},
        "postings": [
            {
                "source": "GREENHOUSE",
                "external_posting_id": "acme-11",
                "criteria": [
                    {"index": index, "criterion_type": "REQUIRED", "text_sha256": text_sha256(text)}
                    for index, text in enumerate(texts)
                ],
            }
        ],
        "cases": [
            {"case_id": "a", "style": "KO_ONLY", "relevant": [{"posting_key": key, "index": 0}]},
            {"case_id": "b", "style": "KO_ONLY", "relevant": [{"posting_key": key, "index": 1}]},
            {"case_id": "c", "style": "KO_ONLY", "relevant": []},
        ],
    }


def test_korean_eval_fixture_has_no_posting_text_and_points_at_existing_criteria() -> None:
    fixture = load_fixture(FIXTURE)

    keys = {f"{p['source']}:{p['external_posting_id']}" for p in fixture["postings"]}
    case_ids = [case["case_id"] for case in fixture["cases"]]
    assert set(fixture["posting_language"]) == keys
    assert set(fixture["posting_language"].values()) == {"KO", "EN"}
    assert len(case_ids) == len(set(case_ids))
    assert {case["style"] for case in fixture["cases"]} == {"KO_ONLY", "KO_LOANWORD", "KO_WITH_EN"}
    for posting in fixture["postings"]:
        for criterion in posting["criteria"]:
            assert set(criterion) == {"index", "criterion_type", "text_sha256"}
    for case in fixture["cases"]:
        for item in case["relevant"]:
            assert set(item) == {"posting_key", "index"}


def test_fixture_file_does_not_carry_posting_text_columns() -> None:
    raw = json.loads(FIXTURE.read_text(encoding="utf-8"))

    def keys_of(node: Any) -> set[str]:
        if isinstance(node, dict):
            return set(node) | {key for value in node.values() for key in keys_of(value)}
        if isinstance(node, list):
            return {key for value in node for key in keys_of(value)}
        return set()

    assert not keys_of(raw) & {"text", "source_quote", "content"}


def test_load_fixture_rejects_criterion_with_text(tmp_path: Path) -> None:
    fixture = _fake_fixture(["파이썬으로 서버를 만든 경험이 있다", "도커로 배포해 본 경험이 있다"])
    fixture["postings"][0]["criteria"][0]["text"] = "원문"
    path = tmp_path / "fixture.json"
    path.write_text(json.dumps(fixture, ensure_ascii=False), encoding="utf-8")

    with pytest.raises(ValueError):
        load_fixture(path)


def test_hydrate_keeps_only_conditions_whose_hash_matches() -> None:
    kept = "파이썬으로 서버를 만든 경험이 있다"
    changed_before = "도커로 배포해 본 경험이 있다"
    fixture = _fake_fixture([kept, changed_before])
    content = f"<h3>이런 분을 찾고 있어요</h3><ul><li>{kept}</li><li>도커로 배포한 경험이 많으면 좋다</li></ul>"
    boards = {"acme": {11: _board_json(content)["jobs"][0]}}

    rows, cases, report = hydrate_fixture(fixture, boards)

    assert [(row["index"], row["text"]) for row in rows] == [(0, kept)]
    assert report["criteria_removed"] == 1
    assert report["relevant_pairs_removed"] == 1
    assert report["cases_skipped"] == ["b"]
    assert [case["case_id"] for case in cases] == ["a", "c"]


def test_hydrate_treats_missing_posting_as_all_conditions_removed() -> None:
    fixture = _fake_fixture(["파이썬으로 서버를 만든 경험이 있다"])

    rows, _, report = hydrate_fixture(fixture, {"acme": {}})

    assert rows == []
    assert report["postings_missing"] == ["GREENHOUSE:acme-11"]
    assert report["criteria_removed"] == 1


def test_text_hash_ignores_spacing_and_unicode_form() -> None:
    assert text_sha256("서버  개발 경험\n") == text_sha256(" 서버 개발 경험")
    assert text_sha256("가") == text_sha256("가")
    assert text_sha256("서버") != text_sha256("백엔드")


def test_extract_conditions_reads_list_items_under_known_headings_only() -> None:
    content = (
        "<h3>이런 일을 해요</h3><ul><li>대규모 트래픽을 처리하는 서버를 만들어요</li></ul>"
        "<h3>합류하면 함께해요 (전형 안내)</h3><ul><li>서류 전형 다음 면접을 봐요 안내입니다</li></ul>"
        "<h3>이런 분이면 더 좋아요</h3><ul><li>쿠버네티스 운영 경험이 있으신 분</li></ul>"
    )

    items = extract_conditions(html.escape(content))

    assert items == [
        {"criterion_type": "RESPONSIBILITY", "text": "대규모 트래픽을 처리하는 서버를 만들어요"},
        {"criterion_type": "PREFERRED", "text": "쿠버네티스 운영 경험이 있으신 분"},
    ]


def test_fetch_boards_reads_cache_and_refuses_network_with_cache_only(tmp_path: Path) -> None:
    sources = [{"company": "acme", "api": "https://boards-api.greenhouse.io/v1/boards/acme/jobs?content=true"}]
    with pytest.raises(FileNotFoundError):
        fetch_boards(sources, tmp_path, cache_only=True)

    (tmp_path / "acme.json").write_text(json.dumps(_board_json("<p>x</p>")), encoding="utf-8")

    assert set(fetch_boards(sources, tmp_path, cache_only=True)["acme"]) == {11}


def test_fetch_boards_refuses_unexpected_host(tmp_path: Path) -> None:
    sources = [{"company": "evil", "api": "https://example.com/v1/boards/evil/jobs?content=true"}]

    with pytest.raises(ValueError):
        fetch_boards(sources, tmp_path, cache_only=False)


def test_threshold_score_keeps_top_k_within_distance_and_counts_empty_answers() -> None:
    rankings = [
        {
            "relevant": {("A", 0), ("A", 1)},
            "hits": [
                {"ref": ("A", 0), "distance": 0.1},
                {"ref": ("B", 0), "distance": 0.2},
                {"ref": ("A", 1), "distance": 0.25},
            ],
        },
        {"relevant": set(), "hits": [{"ref": ("B", 1), "distance": 0.1}]},
    ]

    score = score_threshold(rankings, maximum_distance=0.3, top_k=2)

    assert score["precision"] == 1 / 3
    assert score["recall"] == 1 / 2
    assert score["posting_precision"] == 1 / 3
    assert score["posting_recall"] == 1.0
    assert score["no_answer_cases_with_result"] == 1
