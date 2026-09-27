"""기업 인재상 매칭(matching) 요청/응답 Pydantic 모델."""

from pydantic import BaseModel, Field


class MatchingRequest(BaseModel):
    """인재상 매칭 요청 모델."""

    candidate_id: str = Field(..., description="지원자 식별자")
    company: str = Field(..., description="대상 기업명")
    talent_profile: str | None = Field(
        None, description="기업 인재상 텍스트(미지정 시 저장소에서 조회)"
    )


class MatchingResponse(BaseModel):
    """인재상 매칭 결과 모델."""

    score: float = Field(..., description="매칭 점수 (0.0 ~ 1.0)")
    matched_keywords: list[str] = Field(
        default_factory=list, description="매칭된 인재상 키워드"
    )
    rationale: str = Field(..., description="매칭 판단 근거")
