package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.CardThemePort;
import com.gitory.backend.agent.port.CardThemeRequest;
import com.gitory.backend.agent.port.ExperienceTheme;
import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AiCardThemeAdapter implements CardThemePort {

    private static final String PATH = "/internal/card-themes";
    // 확정 버튼을 누른 사용자가 기다리는 시간이라, 넘으면 빈 유형으로 확정하게 짧게 둔다
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(8);

    private final AiHttpClient ai;

    @Override
    public List<ExperienceTheme> themesFor(CardThemeRequest request) {

        JsonNode data;
        try {
            data = ai.post(PATH, request, READ_TIMEOUT);
        } catch (AiClientException failure) {
            throw new AgentUnavailableException("AI 문항 유형 실패 (" + failure.errorCode() + ")", true, false, failure);
        }

        JsonNode echoed = data.path("card_id");
        if (!echoed.isIntegralNumber() || echoed.longValue() != request.cardId()) {
            throw new AgentUnavailableException("AI 문항 유형 응답이 이상하다", false, true,
                    AiClientException.invalidResponse());
        }

        return ExperienceThemes.read(data.path("themes"));

    }
}
