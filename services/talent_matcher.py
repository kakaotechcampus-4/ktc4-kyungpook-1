"""기업 인재상 매칭 로직을 담당하는 서비스."""

from schemas.matching import MatchingRequest, MatchingResponse


class TalentMatcher:
    """지원자 데이터와 기업 인재상을 비교해 매칭 결과를 산출한다."""

    def match(self, request: MatchingRequest) -> MatchingResponse:
        """인재상 매칭을 수행한다.

        Args:
            request: 매칭 요청.

        Returns:
            매칭 점수와 근거.
        """
        # TODO: 지원자 경험 임베딩 조회 -> 인재상 임베딩과 유사도 계산 -> 근거 생성
        raise NotImplementedError
