"""모자란 조건 채우기: 조건마다 찾아볼지 정하고, 후보·작성 중 카드 → GitHub 커밋 순으로 찾아 제안하며, 사용자에게 묻지 않는다."""

from __future__ import annotations

import asyncio
import json
import threading
import time
from typing import Any, Optional

import httpx
import pytest
from fastapi.testclient import TestClient

import api.gap_fill as gap_fill_api
from main import create_app
from schemas.gap_fill import GapFillRequest
from services.gap_fill import GapFiller, GapFillLimits, GapFillUnavailable, PickOutput, PlanOutput, RepoPickOutput
from services.github_commit_search import CommitHit, CommitSearchResult, GithubCommitSearch
from services.llm_structured_client import StructuredCallResult

TOKEN = "gho_test_token"


def make_request(**overrides: Any) -> GapFillRequest:
    base: dict[str, Any] = {
        "company_name": "토스",
        "job_title": "Server Developer",
        "conditions": [
            {"condition_id": "c1", "criterion_type": "REQUIRED", "text": "메시지 큐 운영 경험"},
            {"condition_id": "c2", "criterion_type": "REQUIRED", "text": "대용량 트래픽 처리 경험"},
        ],
        "cards": [{"card_id": "k1", "title": "캐시로 응답 시간 단축", "action": "Redis 캐시를 붙였다", "techs": ["REDIS"]}],
        "candidates": [{"candidate_id": "p1", "repo_name": "grow22/gitory", "title": "주문 이벤트 비동기 처리",
                        "commit_messages": ["feat: RabbitMQ 로 주문 이벤트 발행"]}],
        "draft_cards": [{"card_id": "d1", "title": "부하 시험", "action": "k6 로 부하 시험을 돌렸다"}],
        "repos": [{"repo_id": "r1", "full_name": "grow22/gitory", "analyzed": True},
                  {"repo_id": "r2", "full_name": "grow22/side", "analyzed": False}],
        "github_login": "grow22",
    }
    base.update(overrides)
    return GapFillRequest.model_validate(base)


def plan(*decisions: tuple[str, str, list[str]]) -> PlanOutput:
    return PlanOutput.model_validate({"decisions": [
        {"condition_id": cid, "decision": decision, "reason": "카드에 비슷한 일이 있다", "queries": queries}
        for cid, decision, queries in decisions]})


def picks(*items: tuple[str, Optional[str], Optional[str]]) -> PickOutput:
    return PickOutput.model_validate({"picks": [
        {"condition_id": cid, "candidate_id": candidate, "card_id": card,
         "reason": "커밋에 그 일을 한 흔적이 있다" if candidate or card else None}
        for cid, candidate, card in items]})


def repo_picks(*items: tuple[str, Optional[str]]) -> RepoPickOutput:
    return RepoPickOutput.model_validate({"picks": [
        {"condition_id": cid, "repo_name": repo, "reason": "커밋에 그 일을 한 흔적이 있다" if repo else None}
        for cid, repo in items]})


class FakeClient:
    """출력 종류마다 정해 둔 답을 차례로 돌려주고, 받은 요청을 남긴다."""

    def __init__(self, answers: dict[type, list[Any]], delay: float = 0.0) -> None:
        self._answers = {kind: list(items) for kind, items in answers.items()}
        self._delay = delay
        self._lock = threading.Lock()
        self.calls: list[tuple[type, dict[str, Any]]] = []

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None, on_partial=None):
        with self._lock:
            self.calls.append((output_model, payload))
            answer = self._answers[output_model].pop(0)
        if self._delay:
            time.sleep(self._delay)
        return StructuredCallResult(output=answer, prompt_tokens=1, completion_tokens=1, latency_ms=1)

    def kinds(self) -> list[type]:
        return [kind for kind, _ in self.calls]


class FakeSearcher:
    def __init__(self, results: Optional[dict[str, CommitSearchResult]] = None) -> None:
        self._results = results or {}
        self.queries: list[tuple[str, str, str]] = []

    async def search(self, github_token: str, login: str, query: str) -> CommitSearchResult:
        self.queries.append((github_token, login, query))
        return self._results.get(query, CommitSearchResult())


def fill(client: FakeClient, searcher: FakeSearcher, request: Optional[GapFillRequest] = None,
         limits: GapFillLimits = GapFillLimits()):
    return asyncio.run(GapFiller(client, searcher, limits).fill(request or make_request(), TOKEN))


def by_id(result) -> dict[str, Any]:
    return {condition.condition_id: condition for condition in result.conditions}


def test_conditions_the_ai_skips_end_without_searching_anything() -> None:
    """분야가 멀어 찾지 않기로 한 조건은 이유와 함께 SKIPPED 로 끝나고, 후보도 GitHub 도 보지 않는다."""
    client = FakeClient({PlanOutput: [plan(("c1", "SKIP", []), ("c2", "SKIP", []))]})
    searcher = FakeSearcher()

    result = fill(client, searcher)

    assert [(c.decision.value, c.decision_reason, c.result.value, c.steps) for c in result.conditions] == [
        ("SKIP", "카드에 비슷한 일이 있다", "SKIPPED", []), ("SKIP", "카드에 비슷한 일이 있다", "SKIPPED", [])]
    assert client.kinds() == [PlanOutput]
    assert searcher.queries == []


def test_a_condition_the_user_forces_is_searched_and_keeps_the_ai_judgement() -> None:
    """사용자가 다시 찾게 한 조건은 AI 가 SKIP 이라 해도 그 판단·이유를 그대로 두고 찾으며, 고르지 않은 조건은 그대로 멈춘다."""
    client = FakeClient({PlanOutput: [plan(("c1", "SKIP", ["rabbitmq"]), ("c2", "SKIP", ["cache"]))],
                         PickOutput: [picks(("c1", "p1", None))]})
    searcher = FakeSearcher()

    result = by_id(fill(client, searcher, make_request(force_condition_ids=["c1"])))

    assert (result["c1"].decision.value, result["c1"].decision_reason, result["c1"].forced) == (
        "SKIP", "카드에 비슷한 일이 있다", True)
    assert (result["c1"].proposal.target_id, result["c1"].result.value) == ("p1", "PROPOSED")
    assert (result["c2"].forced, result["c2"].result.value, result["c2"].steps) == (False, "SKIPPED", [])
    assert client.kinds() == [PlanOutput, PickOutput]


def test_a_forced_condition_without_search_terms_is_asked_again() -> None:
    """다시 찾게 한 조건은 SKIP 이어도 검색어가 있어야 GitHub 를 찾을 수 있어, 검색어가 없으면 첫 판단을 한 번 더 묻는다."""
    client = FakeClient({PlanOutput: [plan(("c1", "SKIP", []), ("c2", "SKIP", [])),
                                      plan(("c1", "SKIP", ["mq"]), ("c2", "SKIP", []))]})
    searcher = FakeSearcher()

    fill(client, searcher, make_request(candidates=[], draft_cards=[], force_condition_ids=["c1"]))

    assert client.kinds() == [PlanOutput, PlanOutput]
    assert [query for _, _, query in searcher.queries] == ["mq"]


def test_conditions_found_among_candidates_or_draft_cards_are_proposed_without_github() -> None:
    """후보에서 찾으면 카드 만들기, 작성 중 카드에서 찾으면 마저 채우기를 제안하고, 그 조건은 GitHub 를 검색하지 않는다."""
    client = FakeClient({PlanOutput: [plan(("c1", "SEARCH", ["rabbitmq"]), ("c2", "SEARCH", ["k6"]))],
                         PickOutput: [picks(("c1", "p1", None), ("c2", None, "d1"))]})
    searcher = FakeSearcher()

    result = by_id(fill(client, searcher))

    assert (result["c1"].proposal.action.value, result["c1"].proposal.target_id, result["c1"].result.value) == (
        "CREATE_CARD", "p1", "PROPOSED")
    assert (result["c2"].proposal.action.value, result["c2"].proposal.target_id) == ("CONTINUE_CARD", "d1")
    assert [step.kind.value for step in result["c1"].steps] == ["CANDIDATES_CHECKED", "DRAFT_CARDS_CHECKED"]
    assert searcher.queries == []


def test_only_conditions_not_found_locally_search_github_and_analyzed_repos_are_left_out() -> None:
    """후보에서 못 찾은 조건만 내 커밋을 검색하고, 이미 분석한 레포의 커밋은 센 수와 고를 거리에서 뺀다."""
    client = FakeClient({PlanOutput: [plan(("c1", "SEARCH", ["rabbitmq"]), ("c2", "SEARCH", ["cache"]))],
                         PickOutput: [picks(("c1", "p1", None), ("c2", None, None))],
                         RepoPickOutput: [repo_picks(("c2", "grow22/side"))]})
    searcher = FakeSearcher({"cache": CommitSearchResult(hits=[
        CommitHit("grow22/gitory", "feat: 캐시 붙이기"), CommitHit("grow22/side", "feat: 조회 캐시로 부하 줄이기")])})

    result = by_id(fill(client, searcher))

    assert searcher.queries == [(TOKEN, "grow22", "cache")]
    assert [(step.kind.value, step.query, step.count) for step in result["c2"].steps][-1] == ("GITHUB_SEARCHED", "cache", 1)
    repo_payload = next(payload for kind, payload in client.calls if kind is RepoPickOutput)
    assert repo_payload["conditions"][0]["found"] == [{"repo_name": "grow22/side", "messages": ["feat: 조회 캐시로 부하 줄이기"]}]


def test_a_found_repo_is_proposed_for_analysis_only_when_it_is_in_my_list() -> None:
    """찾은 레포가 내 목록에서 분석하지 않은 레포면 분석을 제안하고, 목록에 없는 레포(남의 레포 기여)는 찾았다는 단계만 남긴다."""
    client = FakeClient({PlanOutput: [plan(("c1", "SEARCH", ["queue"]), ("c2", "SEARCH", ["cache"]))],
                         RepoPickOutput: [repo_picks(("c1", "grow22/side"), ("c2", "someone/oss"))]})
    searcher = FakeSearcher({"queue": CommitSearchResult(hits=[CommitHit("grow22/side", "feat: 작업 큐 소비자")]),
                             "cache": CommitSearchResult(hits=[CommitHit("someone/oss", "perf: 캐시 적중률 개선")])})

    result = by_id(fill(client, searcher, make_request(candidates=[], draft_cards=[])))

    assert (result["c1"].proposal.action.value, result["c1"].proposal.target_id, result["c1"].result.value) == (
        "ANALYZE_REPO", "r2", "PROPOSED")
    assert result["c2"].proposal is None and result["c2"].result.value == "NOT_FOUND"
    assert (result["c2"].steps[-1].kind.value, result["c2"].steps[-1].repo_name) == ("OUTSIDE_REPO_FOUND", "someone/oss")


def test_search_terms_cannot_change_the_search_and_are_capped_at_three() -> None:
    """검색어의 ':'·따옴표 같은 기호는 지워 author:·repo: 조건을 바꾸지 못하게 하고, 조건마다 세 개까지만 검색한다."""
    client = FakeClient({PlanOutput: [plan(("c1", "SEARCH", ['kafka repo:evil/x', '"queue"', "mq", "broker"]),
                                           ("c2", "SKIP", []))]})
    searcher = FakeSearcher()

    fill(client, searcher, make_request(candidates=[], draft_cards=[]))

    assert [query for _, _, query in searcher.queries] == ["kafka repo evil x", "queue", "mq"]


def test_an_answer_outside_the_request_is_asked_again_and_twice_wrong_first_decision_fails() -> None:
    """첫 판단이 보내지 않은 조건을 적으면 한 번 더 묻고, 두 번 다 틀리면 첫 판단을 못 받은 것으로 실패한다."""
    wrong = plan(("c1", "SEARCH", ["mq"]), ("c9", "SKIP", []))
    client = FakeClient({PlanOutput: [wrong, wrong]})

    with pytest.raises(GapFillUnavailable):
        fill(client, FakeSearcher())

    assert client.kinds() == [PlanOutput, PlanOutput]


def test_a_blocked_github_search_is_recorded_and_the_next_search_goes_on() -> None:
    """GitHub 검색이 막히면(토큰 거절·한도) 그 검색만 실패 단계로 남기고 다음 검색어로 이어 간다."""
    client = FakeClient({PlanOutput: [plan(("c1", "SEARCH", ["kafka", "queue"]), ("c2", "SKIP", []))],
                         RepoPickOutput: [repo_picks(("c1", "grow22/side"))]})
    searcher = FakeSearcher({"kafka": CommitSearchResult(failure="RATE_LIMITED"),
                             "queue": CommitSearchResult(hits=[CommitHit("grow22/side", "feat: 작업 큐 소비자")])})

    result = by_id(fill(client, searcher, make_request(candidates=[], draft_cards=[])))

    assert [(step.kind.value, step.query) for step in result["c1"].steps] == [
        ("GITHUB_FAILED", "kafka"), ("GITHUB_SEARCHED", "queue")]
    assert result["c1"].result.value == "PROPOSED"


def test_conditions_left_when_time_runs_out_end_as_timed_out() -> None:
    """마감이 가까워 다음 판단을 부를 시간이 없으면, 찾던 조건은 '못 찾음' 이 아니라 TIMED_OUT 으로 끝낸다."""
    client = FakeClient({PlanOutput: [plan(("c1", "SEARCH", ["mq"]), ("c2", "SKIP", []))]}, delay=1.2)
    limits = GapFillLimits(deadline_seconds=6.0, min_call_seconds=5.0)

    result = by_id(fill(client, FakeSearcher(), limits=limits))

    assert result["c1"].result.value == "TIMED_OUT"
    assert result["c2"].result.value == "SKIPPED"


def test_stream_sends_each_condition_state_whenever_it_changes_and_ends_with_the_plain_response(monkeypatch) -> None:
    """스트리밍 API 는 조건 상태가 바뀔 때마다 그 조건 전체를 보내고, 마지막 줄은 보통 API 응답과 같다."""
    def filler() -> GapFiller:
        client = FakeClient({PlanOutput: [plan(("c1", "SEARCH", ["rabbitmq"]), ("c2", "SKIP", []))],
                             PickOutput: [picks(("c1", "p1", None))]})
        return GapFiller(client, FakeSearcher())

    body = make_request().model_dump(mode="json")
    headers = {"X-GitHub-Token": TOKEN}
    monkeypatch.setattr(gap_fill_api, "_filler", filler())
    lines = [json.loads(line) for line in TestClient(create_app()).post(
        "/internal/matching/gap-fill/stream", json=body, headers=headers).text.splitlines()]
    monkeypatch.setattr(gap_fill_api, "_filler", filler())
    plain = TestClient(create_app()).post("/internal/matching/gap-fill", json=body, headers=headers).json()

    progress = [line for line in lines if line["type"] == "progress"]
    assert all(line["step"] == "GAP_FILL" and line["item"]["kind"] == "GAP_CONDITION" for line in progress)
    last_by_condition = {line["item"]["condition"]["condition_id"]: line["item"]["condition"] for line in progress}
    assert lines[-1]["status"] == 200 and lines[-1]["body"]["data"] == plain["data"]
    assert list(last_by_condition.values()) == plain["data"]["conditions"]
    first = progress[0]["item"]["condition"]
    assert (first["condition_id"], first["decision"], first["result"]) == ("c1", "SEARCH", None)


def test_commit_search_asks_for_my_commits_and_reads_only_the_first_line() -> None:
    """커밋 검색은 author:<로그인> 으로 내 공개 커밋 전체를 한 번에 찾고, 메시지는 첫 줄만 비밀값을 가려 읽으며, 거절·한도는 실패 종류로 돌려준다."""
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        if "blocked" in request.url.params["q"]:
            return httpx.Response(403, json={"message": "rate limited"})
        if "denied" in request.url.params["q"]:
            return httpx.Response(401, json={"message": "bad token"})
        return httpx.Response(200, json={"items": [
            {"repository": {"full_name": "grow22/side"},
             "commit": {"message": "feat: 큐 소비자 token=ghp_abcdefghijklmnopqrstuvwxyz0123456789\n\n본문은 보지 않는다"}}]})

    search = GithubCommitSearch(base_url="https://github.test", transport=httpx.MockTransport(handler))

    found = asyncio.run(search.search(TOKEN, "grow22", "queue"))
    blocked = asyncio.run(search.search(TOKEN, "grow22", "blocked"))
    denied = asyncio.run(search.search(TOKEN, "grow22", "denied"))

    assert seen[0].url.params["q"] == "queue author:grow22"
    assert seen[0].headers["Authorization"] == f"Bearer {TOKEN}"
    assert [hit.repo_full_name for hit in found.hits] == ["grow22/side"]
    assert "ghp_" not in found.hits[0].message and "본문" not in found.hits[0].message
    assert (blocked.failure, denied.failure) == ("RATE_LIMITED", "UNAUTHORIZED")


def test_the_model_sees_short_names_and_answers_come_back_with_the_real_ids() -> None:
    """LLM 에는 긴 id 대신 c1·p1·d1 같은 짧은 이름을 보내고, 받은 답은 원래 id 로 되돌려 결과에 싣는다."""
    condition, candidate = "6f1c2a3e-0000-4000-8000-000000000001", "9a8b7c6d-0000-4000-8000-000000000002"
    request = make_request(
        conditions=[{"condition_id": condition, "criterion_type": "REQUIRED", "text": "메시지 큐 운영 경험"}],
        candidates=[{"candidate_id": candidate, "repo_name": "grow22/gitory", "title": "주문 이벤트 비동기 처리"}])
    client = FakeClient({PlanOutput: [plan(("c1", "SEARCH", ["rabbitmq"]))],
                         PickOutput: [picks(("c1", "p1", None))]})

    result = fill(client, FakeSearcher(), request)

    plan_payload = client.calls[0][1]
    assert plan_payload["conditions"][0]["condition_id"] == "c1"
    assert client.calls[1][1]["candidates"][0]["candidate_id"] == "p1"
    assert (result.conditions[0].condition_id, result.conditions[0].proposal.target_id) == (condition, candidate)


def test_request_takes_only_requirement_conditions_and_unique_ids() -> None:
    """담당 업무는 채울 조건이 아니라 받지 않고, 같은 id 가 두 번 오거나 보내지 않은 조건을 다시 찾게 하면 거절한다."""
    with pytest.raises(ValueError):
        make_request(force_condition_ids=["c9"])
    with pytest.raises(ValueError):
        make_request(conditions=[{"condition_id": "c1", "criterion_type": "RESPONSIBILITY", "text": "API 개발"}])
    with pytest.raises(ValueError):
        make_request(candidates=[{"candidate_id": "p1", "repo_name": "a/b", "title": "x"},
                                 {"candidate_id": "p1", "repo_name": "a/b", "title": "y"}])
