package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.InterviewNextAction;
import com.gitory.backend.agent.port.InterviewQuestionPort;
import com.gitory.backend.agent.port.InterviewQuestionRequest;
import com.gitory.backend.agent.port.InterviewQuestionResult;
import com.gitory.backend.agent.port.InterviewQuestionType;
import com.gitory.backend.agent.port.InterviewStarSlot;
import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiClientProperties;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * AI 되묻기 질문을 부르고, 응답이 저장해도 되는 모양인지 확인한 뒤 돌려준다
 * 실패는 다시 시도해도 되는 것과 응답이 이상한 것 둘 중 하나로만 던진다
 */
@Component
@RequiredArgsConstructor
public class AiInterviewQuestionAdapter implements InterviewQuestionPort {

    private static final String PATH = "/internal/interview-turns";

    private static final List<String> QUESTION_FIELDS = List.of("target_star_slot", "target_statement_seq",
            "question_type", "question_text");

    private final AiHttpClient ai;
    private final AiClientProperties properties;

    @Override
    public InterviewQuestionResult nextQuestion(InterviewQuestionRequest request) {

        JsonNode data;
        try {
            data = ai.post(PATH, request, properties.interviewReadTimeout());
        } catch (AiClientException failure) {
            throw unavailable(failure);
        }
        try {
            return result(data, request.cardId());
        } catch (RuntimeException malformed) {
            // 응답 원문이 든 예외는 원인으로 넘기지 않는다
            throw unavailable(AiClientException.invalidResponse());
        }

    }

    private static InterviewQuestionResult result(JsonNode data, long cardId) {

        JsonNode echoed = data.path("card_id");
        if (!echoed.isIntegralNumber() || echoed.longValue() != cardId) {
            throw AiClientException.invalidResponse();
        }
        return switch (InterviewNextAction.valueOf(string(data, "next_action"))) {
            case ASK_AGAIN -> new InterviewQuestionResult(InterviewNextAction.ASK_AGAIN,
                    InterviewStarSlot.valueOf(string(data, "target_star_slot")),
                    positiveInt(data, "target_statement_seq"),
                    InterviewQuestionType.valueOf(string(data, "question_type")),
                    nonBlankString(data, "question_text"));
            case COMPLETE -> complete(data);
        };

    }

    private static InterviewQuestionResult complete(JsonNode data) {

        for (String field : QUESTION_FIELDS) {
            JsonNode value = data.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                throw AiClientException.invalidResponse();
            }
        }
        return new InterviewQuestionResult(InterviewNextAction.COMPLETE, null, null, null, null);

    }

    private static String string(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (!value.isString()) {
            throw AiClientException.invalidResponse();
        }
        return value.asString();

    }

    private static String nonBlankString(JsonNode node, String field) {

        String value = string(node, field);
        if (value.isBlank()) {
            throw AiClientException.invalidResponse();
        }
        return value;

    }

    private static int positiveInt(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || value.intValue() <= 0) {
            throw AiClientException.invalidResponse();
        }
        return value.intValue();

    }

    private static AgentUnavailableException unavailable(AiClientException failure) {

        boolean retryable = retryable(failure);
        return new AgentUnavailableException("AI 되묻기 질문 실패 (" + failure.errorCode() + ")", retryable,
                !retryable, failure);

    }

    // 연결이 안 되거나 시간이 지난 실패는 응답 상태와 상관없이 다시 해 볼 만하다
    // 400 은 우리 요청이 틀렸고 500 은 AI 가 고장 난 것이라 다시 불러도 같은 결과다
    private static boolean retryable(AiClientException failure) {

        Integer status = failure.httpStatus();
        if ("AI_UNAVAILABLE".equals(failure.errorCode())) {
            return true;
        }
        if (status != null && (status == 400 || status == 500)) {
            return false;
        }
        return failure.retryable();

    }
}
