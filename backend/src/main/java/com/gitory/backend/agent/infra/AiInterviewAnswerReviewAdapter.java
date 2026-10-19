package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.InterviewAnswerOutcome;
import com.gitory.backend.agent.port.InterviewAnswerReviewPort;
import com.gitory.backend.agent.port.InterviewAnswerReviewRequest;
import com.gitory.backend.agent.port.InterviewAnswerReviewResult;
import com.gitory.backend.agent.port.InterviewFollowUp;
import com.gitory.backend.agent.port.InterviewNextAction;
import com.gitory.backend.agent.port.InterviewQuestionType;
import com.gitory.backend.agent.port.InterviewStarSlot;
import com.gitory.backend.agent.port.InterviewStatementTarget;
import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiClientProperties;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * AI 에 사용자의 되묻기 답이 충분한지 묻고, 모자라면 같은 칸에 다시 물을 질문을 받는다
 * 질문과 같은 대기 시간을 쓰고, 실패는 다시 시도해도 되는 것과 응답이 이상한 것 둘 중 하나로만 던진다
 */
@Component
@RequiredArgsConstructor
public class AiInterviewAnswerReviewAdapter implements InterviewAnswerReviewPort {

    private static final String PATH = "/internal/interview-answer-evaluations";

    private final AiHttpClient ai;
    private final AiClientProperties properties;

    @Override
    public InterviewAnswerReviewResult review(InterviewAnswerReviewRequest request) {

        JsonNode data;
        try {
            data = ai.post(PATH, request, properties.interviewReadTimeout());
        } catch (AiClientException failure) {
            throw unavailable(failure);
        }
        try {
            return result(data);
        } catch (RuntimeException malformed) {
            // 응답 원문이 든 예외는 원인으로 넘기지 않는다
            throw unavailable(AiClientException.invalidResponse());
        }

    }

    private static InterviewAnswerReviewResult result(JsonNode data) {

        InterviewAnswerOutcome outcome = InterviewAnswerOutcome.valueOf(string(data, "outcome"));
        JsonNode nextTurn = data.path("next_turn");

        return switch (InterviewNextAction.valueOf(string(data, "next_action"))) {
            case ASK_AGAIN -> new InterviewAnswerReviewResult(outcome, followUp(nextTurn));
            case COMPLETE -> {
                if (!nextTurn.isMissingNode() && !nextTurn.isNull()) {
                    throw AiClientException.invalidResponse();
                }
                yield new InterviewAnswerReviewResult(outcome, null);
            }
        };

    }

    private static InterviewFollowUp followUp(JsonNode nextTurn) {

        JsonNode target = nextTurn.path("target");
        String text = string(nextTurn, "question_text");
        if (text.isBlank()) {
            throw AiClientException.invalidResponse();
        }

        return new InterviewFollowUp(
                new InterviewStatementTarget(InterviewStarSlot.valueOf(string(target, "star_slot")),
                        positiveInt(target, "statement_seq")),
                InterviewQuestionType.valueOf(string(nextTurn, "question_type")), text);

    }

    private static String string(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (!value.isString()) {
            throw AiClientException.invalidResponse();
        }
        return value.asString();

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
        return new AgentUnavailableException("AI 되묻기 답 평가 실패 (" + failure.errorCode() + ")", retryable,
                !retryable, failure);

    }

    // 질문 어댑터와 같은 기준이다
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
