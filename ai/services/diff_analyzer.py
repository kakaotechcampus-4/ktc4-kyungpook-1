"""확정된 경험의 diff를 LLM 입력용 근거로 압축한다."""

from schemas.analysis import DiffEvidence


class DiffAnalyzer:
    """원문 diff에서 변경 행동과 기술 근거만 추출한다."""

    async def analyze(self, evidence: list[DiffEvidence]) -> list[DiffEvidence]:
        """비밀값과 불필요한 원문을 제외한 근거 요약을 반환한다."""
        # TODO: diff 요약 및 토큰 상한 적용
        # TODO: 저장소 텍스트를 지시문이 아닌 데이터로 격리
        raise NotImplementedError
