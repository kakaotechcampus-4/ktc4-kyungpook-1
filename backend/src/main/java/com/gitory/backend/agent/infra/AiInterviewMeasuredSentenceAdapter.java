package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.InterviewMeasuredSentencePort;
import com.gitory.backend.agent.port.InterviewMeasuredSentenceRequest;
import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiClientProperties;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** '재 봤어요' 에 쓴 측정 결과로 AI 가 R 문장을 다시 쓰게 하고, 문장 한 줄만 돌려준다 */
@Component
@RequiredArgsConstructor
public class AiInterviewMeasuredSentenceAdapter implements InterviewMeasuredSentencePort {

    private static final String PATH = "/internal/interview-measured-sentence";

    private final AiHttpClient ai;
    private final AiClientProperties properties;

    @Override
    public String write(InterviewMeasuredSentenceRequest request) {

        JsonNode data;
        try {
            data = ai.post(PATH, request, properties.interviewReadTimeout());
        } catch (AiClientException failure) {
            throw new AgentUnavailableException("AI 수치 문장 실패 (" + failure.errorCode() + ")", true, false, failure);
        }

        JsonNode echoed = data.path("card_id");
        JsonNode sentence = data.path("sentence");
        if (!echoed.isIntegralNumber() || echoed.longValue() != request.cardId()
                || !sentence.isString() || sentence.asString().isBlank()) {
            throw new AgentUnavailableException("AI 수치 문장 응답이 이상하다", false, true,
                    AiClientException.invalidResponse());
        }

        return sentence.asString().strip();

    }
}
