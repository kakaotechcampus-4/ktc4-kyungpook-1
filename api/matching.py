"""기업 인재상 매칭(matching) API 엔드포인트.

지원자의 경험/커밋 데이터와 기업 인재상을 비교해 매칭 결과를 반환한다.
"""

from fastapi import APIRouter

from schemas.matching import MatchingRequest, MatchingResponse

router = APIRouter(prefix="/matching", tags=["matching"])


@router.post("", response_model=MatchingResponse)
async def create_matching(request: MatchingRequest) -> MatchingResponse:
    """기업 인재상 매칭 요청을 받아 매칭 결과를 반환한다.

    Args:
        request: 지원자 데이터와 대상 기업/인재상 정보.

    Returns:
        매칭 점수와 근거.
    """
    # TODO: TalentMatcher 호출 및 결과 매핑
    raise NotImplementedError
