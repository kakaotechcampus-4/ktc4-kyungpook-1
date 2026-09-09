"""되묻기(interview) 요청/응답 Pydantic 모델."""

from pydantic import BaseModel, Field


class InterviewRequest(BaseModel):
    """되묻기 생성 요청 모델."""

    pr_number: int = Field(..., description="대상 PR 번호")
    case: str = Field(
        ...,
        description="되묻기 케이스 (revert / changes_requested / issue_feedback / fallback)",
    )
    context: str | None = Field(
        None, description="되묻기 생성에 참고할 추가 컨텍스트(리뷰 코멘트 등)"
    )


class InterviewResponse(BaseModel):
    """되묻기 생성 결과 모델."""

    question: str = Field(..., description="Agent가 생성한 되묻기 질문")
    answer: str | None = Field(None, description="예상 답변 또는 사용자 답변")
    rationale: str = Field(..., description="질문 생성 근거")
