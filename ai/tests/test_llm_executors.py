"""경험 그룹화와 카드 초안이 서로의 LLM 실행기 자리를 기다리지 않는지, 줄에서 기다리다
마감까지 남은 시간이 최소 호출 시간보다 짧아졌거나 취소된 호출은 LLM을 부르지 않는지 검증한다."""

from __future__ import annotations

import asyncio
import threading
from concurrent.futures import Executor, Future
from contextlib import contextmanager
from datetime import datetime, timezone
from typing import Any, Callable, Iterator

import pytest

import services.llm_executors as llm_executors
from schemas.analysis import StarAnalysisRequest
from schemas.collection import CandidateDetailResult
from services.diff_analyzer import CommitSummary, DiffAnalysisLimits, DiffAnalyzer, DiffSummaryOutput
from services.grouping_llm import EnvironmentGroupingModelClient, ExperienceGroupingOutput
from services.llm_executors import CARD_DRAFT_EXECUTOR, GROUPING_EXECUTOR, run_llm_call
from services.llm_structured_client import StructuredCallResult
from services.star_generator import StarDraftOutput, StarField, StarGenerationLimits, StarGenerator

SHA_A = "a" * 40


class _RecordingClient:
    """부른 스레드 이름과 남은 시간을 기록하고 미리 정한 출력을 돌려준다."""

    def __init__(self, output: Any) -> None:
        self._output = output
        self.calls: list[dict[str, Any]] = []

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        self.calls.append({"thread": threading.current_thread().name, "timeout_seconds": timeout_seconds})
        return StructuredCallResult(output=self._output, prompt_tokens=1, completion_tokens=1, latency_ms=1)


class _Clock:
    """실행기 모듈이 읽는 monotonic 시계. 처음 읽힌 순간이 호출이 실행기 줄에 들어간 때다."""

    def __init__(self) -> None:
        self.now = 0.0
        self.read = threading.Event()

    def monotonic(self) -> float:
        self.read.set()
        return self.now


class _PickedUpExecutor(Executor):
    """받은 작업을 곧바로 실행 중으로 표시해 asyncio 취소가 지우지 못하게 하고, 실행은 테스트가 정한 때에 한다."""

    def __init__(self) -> None:
        self.jobs: list[tuple[Future, Callable[..., Any], tuple[Any, ...]]] = []

    def submit(self, fn, /, *args, **kwargs):
        future: Future = Future()
        future.set_running_or_notify_cancel()
        self.jobs.append((future, fn, args))
        return future

    def run_pending(self) -> None:
        for future, fn, args in self.jobs:
            try:
                future.set_result(fn(*args))
            except BaseException as exc:
                future.set_exception(exc)


@contextmanager
def _occupied(executor: Executor, workers: int) -> Iterator[threading.Event]:
    """실행기 스레드를 모두 붙잡아 둔다. 돌려준 이벤트를 켜거나 블록을 나가면 놓아 준다."""
    ready = threading.Barrier(workers + 1)
    release = threading.Event()

    def hold() -> None:
        ready.wait()
        release.wait()

    futures = [executor.submit(hold) for _ in range(workers)]
    ready.wait(timeout=5)
    try:
        yield release
    finally:
        release.set()
        for future in futures:
            future.result(timeout=5)


async def _until(condition: Callable[[], Any]) -> None:
    for _ in range(10_000):
        if condition():
            return
        await asyncio.sleep(0)
    raise AssertionError("조건이 채워지지 않았습니다.")


def _detail() -> CandidateDetailResult:
    return CandidateDetailResult(user_repository_id=1, github_pr_number=12, commits=[{
        "sha": SHA_A,
        "message": "feat: 로그인 검증",
        "author_login": "octo",
        "authored_at": datetime(2026, 9, 1, tzinfo=timezone.utc),
        "html_url": f"https://github.com/o/r/commit/{SHA_A}",
        "additions": 1,
        "deletions": 0,
        "files": [{"path": "api/login.py", "status": "MODIFIED", "additions": 1, "deletions": 0, "patch": "+x"}],
    }])


def _diff_output() -> DiffSummaryOutput:
    return DiffSummaryOutput(commits=[CommitSummary(sha=SHA_A, summary="요청 검증을 추가했다.", technical_points=[])])


def _star_request() -> StarAnalysisRequest:
    return StarAnalysisRequest.model_validate({
        "candidate_id": "c1",
        "target_login": "octo",
        "title": "JWT 로그인 구현",
        "source_type": "PR",
        "source_ref": "PR #12",
        "evidence": [{
            "sha": SHA_A,
            "message": "feat: 로그인 검증",
            "author_login": "octo",
            "churn": "+1/-0",
            "summary": "요청 검증을 추가했다.",
            "summary_source": "DIFF",
            "url": f"https://github.com/o/r/commit/{SHA_A}",
        }],
        "confirmed_answers": [],
    })


def _star_output() -> StarDraftOutput:
    empty = StarField(status="EMPTY", text=None, evidence_shas=[], insufficient_reason="근거 없음")
    return StarDraftOutput(S=empty, T=empty, A=empty, R=empty)


def test_grouping_call_runs_while_every_card_draft_thread_is_busy() -> None:
    client = _RecordingClient(ExperienceGroupingOutput(experiences=[]))
    model_client = EnvironmentGroupingModelClient()
    model_client._client = client

    with _occupied(CARD_DRAFT_EXECUTOR, 6):
        output = asyncio.run(asyncio.wait_for(
            model_client.structured_call(
                system_prompt="s", payload={}, output_model=ExperienceGroupingOutput, max_completion_tokens=10
            ),
            timeout=5,
        ))

    assert output.experiences == []
    assert client.calls[0]["thread"].startswith("llm-grouping")


def test_card_draft_calls_run_while_every_grouping_thread_is_busy() -> None:
    diff_client = _RecordingClient(_diff_output())
    star_client = _RecordingClient(_star_output())

    async def scenario():
        return await asyncio.wait_for(
            asyncio.gather(
                DiffAnalyzer(diff_client).analyze(_detail(), "로그인 개선"),
                StarGenerator(star_client).generate(_star_request()),
            ),
            timeout=5,
        )

    with _occupied(GROUPING_EXECUTOR, 3):
        evidence, star = asyncio.run(scenario())

    assert evidence[0].summary_source == "DIFF"
    assert star.missing_fields == ["S", "T", "A", "R"]
    assert diff_client.calls[0]["thread"].startswith("llm-card-draft")
    assert star_client.calls[0]["thread"].startswith("llm-card-draft")


def _run_queued_past_deadline(monkeypatch: pytest.MonkeyPatch, work: Callable[[], Any], waited: float) -> Any:
    """카드 초안 실행기가 꽉 찬 상태에서 호출을 줄에 세우고, ``waited``초가 흐른 뒤 자리를 낸다."""
    clock = _Clock()
    monkeypatch.setattr(llm_executors, "time", clock)

    async def scenario():
        with _occupied(CARD_DRAFT_EXECUTOR, 6) as release:
            task = asyncio.ensure_future(work())
            await _until(lambda: clock.read.is_set() or task.done())
            clock.now += waited
            release.set()
            return await asyncio.wait_for(task, timeout=5)

    return asyncio.run(scenario())


def test_diff_call_whose_deadline_passed_in_queue_does_not_reach_llm(monkeypatch: pytest.MonkeyPatch) -> None:
    client = _RecordingClient(_diff_output())
    analyzer = DiffAnalyzer(client, DiffAnalysisLimits(deadline_seconds=60))

    evidence = _run_queued_past_deadline(monkeypatch, lambda: analyzer.analyze(_detail(), "로그인 개선"), 61)

    assert client.calls == []
    assert evidence[0].summary_source == "COMMIT_MESSAGE"


def test_star_call_whose_deadline_passed_in_queue_does_not_reach_llm(monkeypatch: pytest.MonkeyPatch) -> None:
    client = _RecordingClient(_star_output())
    generator = StarGenerator(client, StarGenerationLimits(deadline_seconds=60))

    result = _run_queued_past_deadline(monkeypatch, lambda: generator.generate(_star_request()), 61)

    assert client.calls == []
    assert {field.insufficient_reason_code for field in result.star.values()} == {"TIMEOUT"}


@pytest.mark.parametrize(("min_call_seconds", "waited"), [(10, 55), (20, 45)])
def test_diff_call_left_with_less_than_min_call_seconds_does_not_reach_llm(
    monkeypatch: pytest.MonkeyPatch, min_call_seconds: float, waited: float
) -> None:
    client = _RecordingClient(_diff_output())
    analyzer = DiffAnalyzer(client, DiffAnalysisLimits(deadline_seconds=60, min_call_seconds=min_call_seconds))

    evidence = _run_queued_past_deadline(monkeypatch, lambda: analyzer.analyze(_detail(), "로그인 개선"), waited)

    assert client.calls == []
    assert evidence[0].summary_source == "COMMIT_MESSAGE"


@pytest.mark.parametrize(("min_call_seconds", "waited"), [(10, 55), (20, 45)])
def test_star_call_left_with_less_than_min_call_seconds_does_not_reach_llm(
    monkeypatch: pytest.MonkeyPatch, min_call_seconds: float, waited: float
) -> None:
    client = _RecordingClient(_star_output())
    generator = StarGenerator(client, StarGenerationLimits(deadline_seconds=60, min_call_seconds=min_call_seconds))

    result = _run_queued_past_deadline(monkeypatch, lambda: generator.generate(_star_request()), waited)

    assert client.calls == []
    assert {field.insufficient_reason_code for field in result.star.values()} == {"TIMEOUT"}


def test_diff_call_left_with_min_call_seconds_or_more_reaches_llm(monkeypatch: pytest.MonkeyPatch) -> None:
    client = _RecordingClient(_diff_output())
    analyzer = DiffAnalyzer(client, DiffAnalysisLimits(deadline_seconds=60, min_call_seconds=10))

    evidence = _run_queued_past_deadline(monkeypatch, lambda: analyzer.analyze(_detail(), "로그인 개선"), 45)

    assert evidence[0].summary_source == "DIFF"
    assert 10 < client.calls[0]["timeout_seconds"] <= 15


def test_star_call_left_with_min_call_seconds_or_more_reaches_llm(monkeypatch: pytest.MonkeyPatch) -> None:
    client = _RecordingClient(_star_output())
    generator = StarGenerator(client, StarGenerationLimits(deadline_seconds=60, min_call_seconds=10))

    _run_queued_past_deadline(monkeypatch, lambda: generator.generate(_star_request()), 45)

    assert 10 < client.calls[0]["timeout_seconds"] <= 15


def test_call_started_late_gets_only_the_time_left_until_deadline(monkeypatch: pytest.MonkeyPatch) -> None:
    client = _RecordingClient(_diff_output())
    analyzer = DiffAnalyzer(client, DiffAnalysisLimits(deadline_seconds=60))

    _run_queued_past_deadline(monkeypatch, lambda: analyzer.analyze(_detail(), "로그인 개선"), 40)

    assert 15 < client.calls[0]["timeout_seconds"] <= 20


def test_cancelled_grouping_call_waiting_in_queue_does_not_reach_llm(monkeypatch: pytest.MonkeyPatch) -> None:
    client = _RecordingClient(ExperienceGroupingOutput(experiences=[]))
    model_client = EnvironmentGroupingModelClient()
    model_client._client = client
    submitted = threading.Event()
    submit = GROUPING_EXECUTOR.submit

    def spy(fn, /, *args, **kwargs):
        submitted.set()
        return submit(fn, *args, **kwargs)

    async def scenario() -> None:
        with _occupied(GROUPING_EXECUTOR, 3) as release:
            # 스레드를 붙잡는 submit 은 세지 않도록 다 붙잡은 뒤에 감시를 건다.
            monkeypatch.setattr(GROUPING_EXECUTOR, "submit", spy)
            task = asyncio.ensure_future(model_client.structured_call(
                system_prompt="s", payload={}, output_model=ExperienceGroupingOutput, max_completion_tokens=10
            ))
            await _until(submitted.is_set)
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task
            release.set()

    asyncio.run(scenario())
    # 세 스레드를 다시 모두 붙잡을 수 있으면 줄에 남았던 호출도 처리가 끝난 것이다.
    with _occupied(GROUPING_EXECUTOR, 3):
        pass

    assert client.calls == []


def test_call_picked_up_just_as_request_was_cancelled_does_not_reach_llm() -> None:
    executor = _PickedUpExecutor()
    called: list[str] = []

    async def scenario() -> None:
        task = asyncio.ensure_future(run_llm_call(executor, called.append, "x"))
        await _until(lambda: executor.jobs)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        executor.run_pending()

    asyncio.run(scenario())

    assert called == []
