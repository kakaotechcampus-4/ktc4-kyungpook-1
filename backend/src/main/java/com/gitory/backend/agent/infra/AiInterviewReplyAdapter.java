package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.InterviewReplyExtra;
import com.gitory.backend.agent.port.InterviewReplyPort;
import com.gitory.backend.agent.port.InterviewReplyRequest;
import com.gitory.backend.agent.port.InterviewReplyResult;
import com.gitory.backend.agent.port.InterviewReplyVerdict;
import com.gitory.backend.agent.port.InterviewStarSlot;
import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiClientProperties;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** AI 가 되묻기 답을 읽고 내린 판단을 받고, 모양이 계약과 다르면 응답이 이상한 실패로 던진다 */
@Component
@RequiredArgsConstructor
public class AiInterviewReplyAdapter implements InterviewReplyPort {

    private static final String PATH = "/internal/interview-replies";

    private final AiHttpClient ai;
    private final AiClientProperties properties;

    @Override
    public InterviewReplyResult reply(InterviewReplyRequest request) {

        JsonNode data;
        try {
            data = ai.post(PATH, request, properties.interviewReadTimeout());
        } catch (AiClientException failure) {
            throw new AgentUnavailableException("AI 되묻기 답 판단 실패 (" + failure.errorCode() + ")", true, false,
                    failure);
        }
        try {
            return result(data, request.cardId());
        } catch (RuntimeException malformed) {
            // 응답 원문이 든 예외는 원인으로 넘기지 않는다
            throw new AgentUnavailableException("AI 되묻기 답 판단 응답이 이상하다", false, true,
                    AiClientException.invalidResponse());
        }

    }

    private static InterviewReplyResult result(JsonNode data, long cardId) {

        JsonNode echoed = data.path("card_id");
        if (!echoed.isIntegralNumber() || echoed.longValue() != cardId) {
            throw AiClientException.invalidResponse();
        }

        return new InterviewReplyResult(InterviewReplyVerdict.valueOf(requiredString(data, "verdict")),
                optionalString(data, "sentence"), extras(data.path("extra")), optionalString(data, "follow_up"),
                optionalString(data, "reason"));

    }

    private static List<InterviewReplyExtra> extras(JsonNode values) {

        if (values.isMissingNode() || values.isNull()) {
            return List.of();
        }
        if (!values.isArray()) {
            throw AiClientException.invalidResponse();
        }

        List<InterviewReplyExtra> extras = new ArrayList<>();
        for (JsonNode value : values) {
            extras.add(new InterviewReplyExtra(InterviewStarSlot.valueOf(requiredString(value, "star_slot")),
                    requiredString(value, "sentence")));
        }

        return List.copyOf(extras);

    }

    private static String requiredString(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (!value.isString()) {
            throw AiClientException.invalidResponse();
        }

        return value.asString();

    }

    private static String optionalString(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw AiClientException.invalidResponse();
        }

        return value.asString().strip();

    }
}
