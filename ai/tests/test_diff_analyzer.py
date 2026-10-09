"""DiffAnalyzer의 전처리·후처리 계약을 LLM 없이 검증한다."""

from __future__ import annotations

import asyncio
from datetime import datetime, timezone
from typing import Any

import pytest

from schemas.collection import CandidateDetailResult
from services.diff_analyzer import (
    SYSTEM_PROMPT,
    CommitSummary,
    DiffAnalysisError,
    DiffAnalysisLimits,
    DiffAnalyzer,
    DiffSummaryOutput,
)
from services.llm_structured_client import (
    LLMInvalidOutputError,
    LLMStructuredCallError,
    LLMStructuredOutputTooLongError,
    StructuredCallResult,
)

SHA_A = "a" * 40
SHA_B = "b" * 40
FAKE_TOKEN = "ghp_" + "x" * 30


class _FakeClient:
    """입력 payload를 기록하고 미리 정한 응답(또는 오류)을 순서대로 돌려준다."""

    def __init__(self, *responses: DiffSummaryOutput | Exception) -> None:
        self._responses = list(responses)
        self.calls: list[dict[str, Any]] = []

    def call(self, system_prompt, payload, output_model, max_completion_tokens):
        self.calls.append({
            "system_prompt": system_prompt,
            "payload": payload,
            "output_model": output_model,
            "max_completion_tokens": max_completion_tokens,
        })
        response = self._responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return StructuredCallResult(output=response, prompt_tokens=10, completion_tokens=5, latency_ms=1)


def _commit(sha: str, message: str, files: list[dict[str, Any]], **extra: Any) -> dict[str, Any]:
    return {
        "sha": sha,
        "message": message,
        "author_login": "octo",
        "authored_at": datetime(2026, 9, 1, tzinfo=timezone.utc),
        "html_url": f"https://github.com/o/r/commit/{sha}",
        "additions": sum(f["additions"] for f in files),
        "deletions": sum(f["deletions"] for f in files),
        "files": files,
        **extra,
    }


def _file(path: str, patch: str | None, additions: int = 1, deletions: int = 0, **extra: Any) -> dict[str, Any]:
    return {"path": path, "status": "MODIFIED", "additions": additions, "deletions": deletions, "patch": patch, **extra}


def _detail(*commits: dict[str, Any]) -> CandidateDetailResult:
    return CandidateDetailResult(user_repository_id=1, github_pr_number=12, commits=list(commits))


def _output(*items: tuple[str, str, list[str]]) -> DiffSummaryOutput:
    return DiffSummaryOutput(commits=[
        CommitSummary(sha=sha, summary=summary, technical_points=points) for sha, summary, points in items
    ])


def _run(analyzer: DiffAnalyzer, detail: CandidateDetailResult, title: str = "로그인 개선"):
    return asyncio.run(analyzer.analyze(detail, title))


def test_returns_evidence_in_input_order_with_churn_and_points() -> None:
    client = _FakeClient(_output(
        (SHA_B, "토큰 갱신 실패 시 401 JSON을 반환하도록 필터를 추가했다.", ["OncePerRequestFilter로 인증 실패 처리"]),
        (SHA_A, "로그인 요청을 Pydantic 스키마로 검증하도록 바꿨다.", ["요청 스키마 검증", "요청 스키마 검증"]),
    ))
    detail = _detail(
        _commit(SHA_A, "feat: 로그인 요청 검증\n\n본문", [_file("api/login.py", "+x", 3, 1)]),
        _commit(SHA_B, "fix: 401 응답", [_file("Filter.java", "+y", 10, 0)]),
    )

    evidence = _run(DiffAnalyzer(client), detail)

    assert [item.sha for item in evidence] == [SHA_A, SHA_B]
    assert evidence[0].churn == "+3/-1"
    assert evidence[0].technical_points == ["요청 스키마 검증"]
    assert evidence[0].url == f"https://github.com/o/r/commit/{SHA_A}"
    assert evidence[1].summary.startswith("토큰 갱신 실패 시")
    assert [item.summary_source for item in evidence] == ["DIFF", "DIFF"]
    call = client.calls[0]
    assert call["system_prompt"] == SYSTEM_PROMPT
    assert call["output_model"] is DiffSummaryOutput
    assert call["max_completion_tokens"] == 2_000
    assert call["payload"]["pr_number"] == 12
    assert call["payload"]["title"] == "로그인 개선"


def test_drops_unknown_and_duplicate_shas_and_falls_back_to_commit_headline() -> None:
    client = _FakeClient(_output(
        (SHA_A, "첫 요약", []),
        (SHA_A, "중복 요약", []),
        ("c" * 40, "입력에 없는 커밋", []),
    ))
    detail = _detail(
        _commit(SHA_A, "feat: a", [_file("a.py", "+a")]),
        _commit(SHA_B, "\n  refactor: 세션 저장소 분리  \n\n상세", [_file("b.py", "+b")]),
    )

    evidence = _run(DiffAnalyzer(client), detail)

    assert [item.summary for item in evidence] == ["첫 요약", "refactor: 세션 저장소 분리"]
    assert [item.summary_source for item in evidence] == ["DIFF", "COMMIT_MESSAGE"]
    assert evidence[1].technical_points == []


def test_matches_sha_case_insensitively() -> None:
    upper = "ABCDEF1" + "0" * 33
    client = _FakeClient(_output((upper.lower(), "요약", [])))

    evidence = _run(DiffAnalyzer(client), _detail(_commit(upper, "m", [])))

    assert evidence[0].sha == upper
    assert evidence[0].summary == "요약"


def test_redacts_secrets_in_payload_and_output() -> None:
    client = _FakeClient(_output((SHA_A, f"토큰 {FAKE_TOKEN} 을 설정했다.", [f"키 {FAKE_TOKEN}"])))
    detail = _detail(_commit(SHA_A, f"chore: {FAKE_TOKEN}", [_file("conf.py", f'+TOKEN = "{FAKE_TOKEN}"')]))

    evidence = _run(DiffAnalyzer(client), detail)

    sent = repr(client.calls[0]["payload"])
    assert FAKE_TOKEN not in sent
    assert "[REDACTED]" in sent
    assert FAKE_TOKEN not in evidence[0].model_dump_json()


def test_excludes_generated_file_patches_but_keeps_metadata() -> None:
    client = _FakeClient(_output((SHA_A, "요약", [])))
    detail = _detail(_commit(SHA_A, "m", [
        _file("frontend/package-lock.json", "+lock", 900, 300),
        _file("assets/logo.PNG", "binary"),
        _file("src/app.ts", "+code"),
    ]))

    _run(DiffAnalyzer(client), detail)

    files = client.calls[0]["payload"]["commits"][0]["files"]
    assert [f["patch"] for f in files] == [None, None, "+code"]
    assert files[0]["additions"] == 900


def test_shares_unused_patch_budget_and_marks_truncation() -> None:
    client = _FakeClient(_output((SHA_A, "a", []), (SHA_B, "b", [])))
    limits = DiffAnalysisLimits(max_input_patch_chars=20)
    detail = _detail(
        _commit(SHA_A, "a", [_file("a1.py", "1234567"), _file("a2.py", "abcdefg"), _file("a3.py", "zz")]),
        _commit(SHA_B, "b", [_file("b.py", "short", patch_truncated=True)]),
    )

    _run(DiffAnalyzer(client, limits), detail)

    first, second = client.calls[0]["payload"]["commits"]
    # b 커밋은 5자만 쓰므로 남은 몫(15자)을 a 커밋이 쓴다.
    assert [(f["patch"], f["truncated"]) for f in first["files"]] == [
        ("1234567", False), ("abcdefg", False), ("z", True),
    ]
    # 수집 단계에서 이미 잘린 patch는 상한 안이어도 truncated로 유지한다.
    assert second["files"] == [
        {"path": "b.py", "additions": 1, "deletions": 0, "patch": "short", "truncated": True}
    ]


def test_clips_long_summary_and_caps_points() -> None:
    client = _FakeClient(_output((SHA_A, "가" * 500, ["p1", "p2", "p3", "p4", "  "])))

    evidence = _run(DiffAnalyzer(client), _detail(_commit(SHA_A, "m", [])))

    assert len(evidence[0].summary) == 200
    assert evidence[0].summary.endswith("…")
    assert evidence[0].technical_points == ["p1", "p2", "p3"]


def test_retries_once_on_invalid_output() -> None:
    client = _FakeClient(LLMInvalidOutputError("형식 오류"), _output((SHA_A, "요약", [])))

    evidence = _run(DiffAnalyzer(client), _detail(_commit(SHA_A, "m", [])))

    assert len(client.calls) == 2
    assert evidence[0].summary == "요약"


def test_raises_after_invalid_output_retries_are_exhausted() -> None:
    client = _FakeClient(LLMInvalidOutputError("1"), LLMInvalidOutputError("2"))

    with pytest.raises(DiffAnalysisError):
        _run(DiffAnalyzer(client), _detail(_commit(SHA_A, "m", [])))

    assert len(client.calls) == 2


def test_does_not_retry_errors_the_sdk_already_retried() -> None:
    # 호출 한도·연결 오류·출력 길이 초과는 다시 보내도 지연만 늘어난다.
    client = _FakeClient(LLMStructuredCallError("rate limit"), _output((SHA_A, "요약", [])))

    with pytest.raises(DiffAnalysisError):
        _run(DiffAnalyzer(client), _detail(_commit(SHA_A, "m", [])))

    assert len(client.calls) == 1


def test_empty_candidate_skips_llm_call() -> None:
    client = _FakeClient()

    assert _run(DiffAnalyzer(client), _detail()) == []
    assert client.calls == []


def test_dedupes_input_commits() -> None:
    client = _FakeClient(_output((SHA_A, "요약", [])))

    evidence = _run(DiffAnalyzer(client), _detail(_commit(SHA_A, "m", []), _commit(SHA_A, "m", [])))

    assert len(evidence) == 1
    assert len(client.calls[0]["payload"]["commits"]) == 1


def test_requires_llm_client() -> None:
    with pytest.raises(DiffAnalysisError):
        _run(DiffAnalyzer(), _detail(_commit(SHA_A, "m", [])))


def test_redacts_private_key_body_and_config_secrets() -> None:
    client = _FakeClient(_output((SHA_A, "요약", [])))
    key = "+-----BEGIN RSA PRIVATE KEY-----\n+MIIEpAIBAAKCAQEAkeybody\n+-----END RSA PRIVATE KEY-----"
    detail = _detail(_commit(SHA_A, "m", [
        _file("docs/setup.md", key),
        _file("src/main/resources/application.yml", "+  client-secret: s3cr3tvalue\n+  password: ${DB_PASSWORD}"),
        _file("app/auth.py", '+password = "hunter22"\n+token = request.headers.get("Authorization")'),
    ]))

    _run(DiffAnalyzer(client), detail)

    patches = [f["patch"] for f in client.calls[0]["payload"]["commits"][0]["files"]]
    assert "keybody" not in patches[0]
    assert "s3cr3tvalue" not in patches[1]
    # 환경변수 참조는 비밀값이 아니라 설정 방식의 근거이므로 남긴다.
    assert "${DB_PASSWORD}" in patches[1]
    assert "hunter22" not in patches[2]
    # 설정 파일이 아닌 코드의 따옴표 없는 대입은 가리지 않는다.
    assert 'request.headers.get("Authorization")' in patches[2]


def test_excludes_sensitive_file_patches() -> None:
    client = _FakeClient(_output((SHA_A, "요약", [])))
    paths = [".env", "backend/.env.prod", "certs/server.pem", "deploy/id_rsa", ".env.example", "pnpm-lock.yaml"]
    detail = _detail(_commit(SHA_A, "m", [_file(path, "+X=1") for path in paths]))

    _run(DiffAnalyzer(client), detail)

    files = client.calls[0]["payload"]["commits"][0]["files"]
    assert {f["path"]: f["patch"] for f in files} == {
        ".env": None, "backend/.env.prod": None, "certs/server.pem": None, "deploy/id_rsa": None,
        ".env.example": "+X=1", "pnpm-lock.yaml": None,
    }


def test_caps_commit_message_and_title() -> None:
    client = _FakeClient(_output((SHA_A, "요약", [])))
    detail = _detail(_commit(SHA_A, "m" * 50_000, []))

    evidence = _run(DiffAnalyzer(client), detail, title="t" * 1_000)

    payload = client.calls[0]["payload"]
    assert len(payload["commits"][0]["message"]) == 2_000
    assert len(payload["title"]) == 200
    assert len(evidence[0].message) == 2_000


def test_splits_commits_into_batches_and_keeps_order() -> None:
    shas = [f"{index:07x}" + "0" * 33 for index in range(1, 20)]

    class _EchoClient(_FakeClient):
        def call(self, system_prompt, payload, output_model, max_completion_tokens):
            self.calls.append({"payload": payload})
            # 다른 호출의 SHA를 섞어 보내도 그 호출의 결과로 쓰지 않아야 한다.
            items = [(c["sha"], f"요약 {c['sha'][:7]}", []) for c in payload["commits"]]
            return StructuredCallResult(output=_output(*items, (shas[0], "섞인 SHA", [])),
                                        prompt_tokens=1, completion_tokens=1, latency_ms=1)

    client = _EchoClient()
    detail = _detail(*(_commit(sha, "m", [_file("a.py", "+x" * 10_000)]) for sha in shas))

    evidence = _run(DiffAnalyzer(client), detail)

    assert sorted(len(call["payload"]["commits"]) for call in client.calls) == [3, 8, 8]
    assert [item.sha for item in evidence] == shas
    assert [item.summary for item in evidence] == [f"요약 {sha[:7]}" for sha in shas]
    for call in client.calls:
        patch_chars = sum(len(f["patch"]) for c in call["payload"]["commits"] for f in c["files"])
        assert patch_chars <= 24_000


def test_fails_whole_analysis_when_a_batch_fails() -> None:
    client = _FakeClient(
        _output(*((f"{i:07x}" + "0" * 33, "요약", []) for i in range(1, 9))),
        LLMStructuredCallError("1"),
    )
    detail = _detail(*(_commit(f"{i:07x}" + "0" * 33, "m", []) for i in range(1, 10)))

    with pytest.raises(DiffAnalysisError):
        _run(DiffAnalyzer(client, DiffAnalysisLimits(max_concurrent_calls=1)), detail)


def test_replaces_summary_with_invented_metric_and_drops_such_points() -> None:
    client = _FakeClient(_output(
        (SHA_A, "캐시를 추가해 응답 시간을 30% 줄였다.", ["Redis 캐시 적용", "지연 100ms 감소"]),
        (SHA_B, "인터뷰 질문을 최대 2회로 제한했다.", ["턴 상한 상수 2회"]),
    ))
    detail = _detail(
        _commit(SHA_A, "feat: 조회 캐시 추가", [_file("cache.py", "@@ -30,7 +30,9 @@\n+cache = Redis()")]),
        _commit(SHA_B, "feat: 질문 횟수 제한", [_file("agent.py", "+MAX_INTERVIEW_TURNS = 2")]),
    )

    evidence = _run(DiffAnalyzer(client), detail)

    # hunk 헤더의 30은 근거가 아니므로 "30%"는 지어낸 수치로 본다.
    assert evidence[0].summary == "feat: 조회 캐시 추가"
    assert evidence[0].summary_source == "COMMIT_MESSAGE"
    assert evidence[0].technical_points == ["Redis 캐시 적용"]
    # 입력의 상수 2에서 확인되는 "2회"는 유지한다.
    assert evidence[1].summary == "인터뷰 질문을 최대 2회로 제한했다."
    assert evidence[1].summary_source == "DIFF"
    assert evidence[1].technical_points == ["턴 상한 상수 2회"]


def test_splits_batch_in_half_when_output_is_too_long() -> None:
    shas = [f"{index:07x}" + "0" * 33 for index in range(1, 6)]

    class _LengthLimitedClient(_FakeClient):
        def call(self, system_prompt, payload, output_model, max_completion_tokens):
            batch = [c["sha"] for c in payload["commits"]]
            self.calls.append({"payload": payload})
            if len(batch) > 2:
                raise LLMStructuredOutputTooLongError("too long")
            return StructuredCallResult(output=_output(*((sha, f"요약 {sha[:7]}", []) for sha in batch)),
                                        prompt_tokens=1, completion_tokens=1, latency_ms=1)

    client = _LengthLimitedClient()
    detail = _detail(*(_commit(sha, "m", []) for sha in shas))

    evidence = _run(DiffAnalyzer(client, DiffAnalysisLimits(max_concurrent_calls=1)), detail)

    # 5 → 2 + 3 → 3은 다시 1 + 2로 나뉜다. 순서와 모든 커밋은 그대로 남는다.
    assert [len(call["payload"]["commits"]) for call in client.calls] == [5, 2, 3, 1, 2]
    assert [item.sha for item in evidence] == shas
    assert all(item.summary_source == "DIFF" for item in evidence)


def test_fails_when_single_commit_output_is_still_too_long() -> None:
    client = _FakeClient(LLMStructuredOutputTooLongError("too long"))

    with pytest.raises(DiffAnalysisError):
        _run(DiffAnalyzer(client), _detail(_commit(SHA_A, "m", [])))

    assert len(client.calls) == 1
