"""검증된 diff 근거로 STAR 결과를 생성한다."""

from schemas.analysis import StarAnalysisRequest, StarAnalysisResponse


class StarGenerator:
    """구조화된 LLM 출력 생성과 근거 SHA 검증을 담당한다."""

    async def generate(self, request: StarAnalysisRequest) -> StarAnalysisResponse:
        """근거가 없는 영역은 비운 STAR 분석 결과를 반환한다."""
        # TODO: LLM 구조화 출력 호출
        # TODO: 입력에 없는 SHA와 뒷받침되지 않는 주장 제거
        # TODO: 빈 영역에 insufficient_reason 기록
        raise NotImplementedError
