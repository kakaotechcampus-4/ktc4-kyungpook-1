package com.gitory.backend.agent.port;

import java.util.List;

/**
 * 모델에 보내는 것의 전부. 여기 없는 것은 모델이 보지 못한다.
 *
 * <p>토큰·키·환경 설정값이 들어갈 자리가 타입에 없다 — 비밀값 유출을 리뷰가 아니라
 * 구조로 막는다(security-privacy.md).
 *
 * @param analysisTargetLogin 분석 대상자 GitHub 로그인 (정규화된 소문자)
 * @param evidenceSummaries   근거 요약. 원문 diff 가 아니라 요약이다.
 * @param churnLines          커밋별 변경량 한 줄 요약. {@code "9d6424a582 taehun0208 +71/-0 :: ..."}
 * @param dependencyFiles     의존성·설정 파일 본문. 스택 적중의 전환점이다.
 * @param answers             지금까지 받은 인터뷰 답변
 */
public record DraftRequest(
        String analysisTargetLogin,
        List<String> evidenceSummaries,
        List<String> churnLines,
        List<String> dependencyFiles,
        List<String> answers
) {
}
