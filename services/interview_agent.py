"""되묻기(interview) 로직을 담당하는 Agent 서비스.

리뷰 케이스별로 적절한 되묻기 질문을 생성한다.
"""

from schemas.interview import InterviewRequest, InterviewResponse


class InterviewAgent:
    """되묻기 질문 생성 Agent."""

    def generate(self, request: InterviewRequest) -> InterviewResponse:
        """케이스에 따라 되묻기 질문을 생성한다.

        Args:
            request: 되묻기 요청.

        Returns:
            생성된 되묻기 결과.
        """
        # TODO: request.case 값에 따라 아래 케이스별 함수로 분기
        raise NotImplementedError

    def _ask_for_revert(self, request: InterviewRequest) -> InterviewResponse:
        """revert 케이스 되묻기 생성."""
        # TODO: revert 사유를 묻는 질문 생성 로직
        raise NotImplementedError

    def _ask_for_changes_requested(self, request: InterviewRequest) -> InterviewResponse:
        """changes_requested 케이스 되묻기 생성."""
        # TODO: 변경 요청 반영 계획을 묻는 질문 생성 로직
        raise NotImplementedError

    def _ask_for_issue_feedback(self, request: InterviewRequest) -> InterviewResponse:
        """issue_feedback 케이스 되묻기 생성."""
        # TODO: 이슈 피드백에 대한 대응을 묻는 질문 생성 로직
        raise NotImplementedError

    def _ask_fallback(self, request: InterviewRequest) -> InterviewResponse:
        """분류되지 않은 경우의 기본(fallback) 되묻기 생성."""
        # TODO: 일반적인 되묻기 질문 생성 로직
        raise NotImplementedError
