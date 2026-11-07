"""모자란 조건 채우기가 쓰는 도구 — 사용자가 쓴 공개 커밋을 검색어로 GitHub 에서 찾는다.

레포마다 따로 묻지 않고 `author:<로그인>` 으로 한 번에 찾아서, 레포 수와 상관없이 검색어 수만큼만 부른다.
실패는 예외로 올리지 않고 결과에 담아, 검색 하나가 막혀도 다른 검색과 판단은 이어 간다.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Optional

import httpx

from services.github_collector import (
    DEFAULT_GITHUB_API_BASE_URL,
    GITHUB_ACCEPT,
    GITHUB_API_BASE_URL_ENV,
    GITHUB_API_VERSION,
)
from services.secret_redaction import mask_emails, redact_secrets

# 커밋 메시지 첫 줄만 판단에 쓴다. 본문은 길고 판단에 덧붙이는 것이 적다
MESSAGE_MAX_CHARS = 200


@dataclass(frozen=True)
class CommitHit:
    repo_full_name: str
    message: str


@dataclass(frozen=True)
class CommitSearchResult:
    hits: list[CommitHit] = field(default_factory=list)
    failure: Optional[str] = None  # UNAUTHORIZED · RATE_LIMITED · FAILED


class GithubCommitSearch:
    def __init__(
        self,
        *,
        base_url: Optional[str] = None,
        transport: Optional[httpx.AsyncBaseTransport] = None,
        timeout_seconds: float = 10.0,
        per_page: int = 20,
    ) -> None:
        # E2E 시험이 가짜 GitHub 으로 바꿀 수 있게 환경변수를 생성 시점에 읽는다
        configured = os.getenv(GITHUB_API_BASE_URL_ENV, "").strip()
        self._base_url = (base_url or configured or DEFAULT_GITHUB_API_BASE_URL).rstrip("/")
        self._transport = transport
        self._timeout_seconds = timeout_seconds
        self._per_page = per_page

    async def search(self, github_token: str, login: str, query: str) -> CommitSearchResult:
        """토큰은 이 호출의 HTTP 클라이언트에만 싣는다."""
        headers = {
            "Authorization": f"Bearer {github_token.strip()}",
            "Accept": GITHUB_ACCEPT,
            "X-GitHub-Api-Version": GITHUB_API_VERSION,
            "User-Agent": "Gitory/1.0",
        }
        params = {"q": f"{query} author:{login}", "per_page": self._per_page, "sort": "author-date"}
        try:
            async with httpx.AsyncClient(base_url=self._base_url, headers=headers, transport=self._transport,
                                         timeout=self._timeout_seconds) as client:
                response = await client.get("/search/commits", params=params)
        except httpx.HTTPError:
            return CommitSearchResult(failure="FAILED")
        if response.status_code == 401:
            return CommitSearchResult(failure="UNAUTHORIZED")
        if response.status_code in (403, 429):
            return CommitSearchResult(failure="RATE_LIMITED")
        if response.status_code != 200:
            return CommitSearchResult(failure="FAILED")
        try:
            items = response.json().get("items")
        except ValueError:
            return CommitSearchResult(failure="FAILED")
        return CommitSearchResult(hits=[hit for hit in map(_hit, items if isinstance(items, list) else []) if hit])


def _hit(item: object) -> Optional[CommitHit]:
    if not isinstance(item, dict):
        return None
    repository = item.get("repository")
    commit = item.get("commit")
    name = repository.get("full_name") if isinstance(repository, dict) else None
    message = commit.get("message") if isinstance(commit, dict) else None
    if not isinstance(name, str) or not isinstance(message, str) or not message.strip():
        return None
    first_line = message.strip().splitlines()[0]
    return CommitHit(repo_full_name=name, message=mask_emails(redact_secrets(first_line))[:MESSAGE_MAX_CHARS])
