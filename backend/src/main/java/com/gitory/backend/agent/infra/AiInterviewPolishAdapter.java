package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.InterviewPolishPort;
import com.gitory.backend.agent.port.InterviewPolishRequest;
import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiClientProperties;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@RequiredArgsConstructor
public class AiInterviewPolishAdapter implements InterviewPolishPort {

    private static final String PATH = "/internal/interview-polished-sentence";

    private final AiHttpClient ai;
    private final AiClientProperties properties;

    @Override
    public String polish(InterviewPolishRequest request) {

        JsonNode data;
        try {
            data = ai.post(PATH, request, properties.interviewReadTimeout());
        } catch (AiClientException failure) {
            throw new AgentUnavailableException("AI 답 다듬기 실패 (" + failure.errorCode() + ")", true, false, failure);
        }

        JsonNode echoed = data.path("card_id");
        JsonNode sentence = data.path("sentence");
        if (!echoed.isIntegralNumber() || echoed.longValue() != request.cardId()
                || !sentence.isString() || sentence.asString().isBlank()) {
            throw new AgentUnavailableException("AI 답 다듬기 응답이 이상하다", false, true,
                    AiClientException.invalidResponse());
        }

        return sentence.asString().strip();

    }
}
