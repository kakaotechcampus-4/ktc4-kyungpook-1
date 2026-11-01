package com.gitory.backend.agent.port;

import java.util.List;

public interface CardThemePort {

    /**
     * 경험 카드의 제목·칸 문장으로 자소서 문항 유형을 잘 맞는 순서로 두 개까지 고른다
     * AI 를 부르지 못했거나 응답이 계약과 다르면 AgentUnavailableException 을 던진다
     */
    List<ExperienceTheme> themesFor(CardThemeRequest request);
}
