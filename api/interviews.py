"""되묻기(interview) API 엔드포인트.

PR 리뷰 과정에서 발생하는 되묻기 요청을 받아 Agent에게 전달하고
생성된 질문/답변/근거를 반환한다.
"""

from fastapi import APIRouter

from schemas.interview import InterviewRequest, InterviewResponse

router = APIRouter(prefix="/interviews", tags=["interviews"])


@router.post("", response_model=InterviewResponse)
async def create_interview(request: InterviewRequest) -> InterviewResponse:
    """되묻기 요청을 받아 Agent가 생성한 되묻기 결과를 반환한다.

    Args:
        request: 되묻기 대상 정보(PR, 커밋, 리뷰 케이스 등).

    Returns:
        생성된 되묻기 질문과 근거.
    """
    # TODO: InterviewAgent 호출 및 결과 매핑
    raise NotImplementedError
