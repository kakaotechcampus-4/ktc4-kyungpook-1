"""저장소 README 요약 — Spring 이 저장소 이름·언어·최상위 파일 이름·README 앞부분을 보내면 AI 가 저장소 설명 한 문장을 돌려주는 계약.

summary 가 null 이면 재료가 모자라거나 검사에서 버린 것이며 실패가 아니다.
"""

from __future__ import annotations

from typing import Annotated, Optional

from pydantic import Field, PositiveInt, StringConstraints

from schemas.matching import StrictModel

FileName = Annotated[str, StringConstraints(min_length=1, max_length=200)]


class RepoSummaryRequest(StrictModel):
    repo_id: PositiveInt
    owner: str = Field(min_length=1, max_length=100)
    name: str = Field(min_length=1, max_length=100)
    language: Optional[str] = Field(None, max_length=100)
    top_level_files: list[FileName] = Field(default_factory=list, max_length=30)
    readme_head: str = Field("", description="3,000자를 넘으면 거절하지 않고 앞 3,000자만 쓴다")


class RepoSummaryResult(StrictModel):
    repo_id: PositiveInt
    summary: Optional[str] = Field(description="저장소 설명 한 문장. 하는 일을 알 수 없거나 검사에서 버렸으면 null")
