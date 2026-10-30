package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.CoverLetterAssignment;
import com.gitory.backend.agent.port.CoverLetterAssignmentPort;
import com.gitory.backend.agent.port.CoverLetterAssignmentRequest;
import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiClientProperties;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI 자소서 카드 고르기 API 를 부르고, 응답이 저장해도 되는 모양인지 확인한 뒤 돌려준다
 * 보내지 않은 문항·카드를 가리키거나 문항이 빠진 응답은 이상 응답으로 끝내, 남의 카드나 없는 문항이 저장되지 않게 한다
 */
@Component
@RequiredArgsConstructor
public class AiCoverLetterAssignmentAdapter implements CoverLetterAssignmentPort {

    private static final String PATH = "/internal/cover-letter-assignments";

    private static final Set<String> THEMES = Set.of("COLLABORATION", "CONFLICT", "PROBLEM_SOLVING", "LEADERSHIP",
            "CHALLENGE", "FAILURE", "GROWTH", "MOTIVATION");
    private static final Set<String> DECIDERS = Set.of("SUPERVISOR", "SUB_AGENT", "CODE", "FAILED");
    private static final Set<String> ACTORS = Set.of("SUB_AGENT", "SUPERVISOR", "CODE");
    private static final Set<String> KINDS = Set.of("PICKED", "NO_FIT", "FAILED", "CHECKED", "SWITCHED", "RESOLVED");

    private final AiHttpClient ai;
    private final AiClientProperties properties;

    @Override
    public CoverLetterAssignment assign(CoverLetterAssignmentRequest request) {

        JsonNode data;
        try {
            data = ai.post(PATH, request, properties.readTimeout());
        } catch (AiClientException failure) {
            throw unavailable(failure);
        }
        try {
            return response(data, request);
        } catch (RuntimeException malformed) {
            // 응답 원문이 든 예외는 원인으로 넘기지 않는다
            throw unavailable(AiClientException.invalidResponse());
        }

    }

    private static CoverLetterAssignment response(JsonNode data, CoverLetterAssignmentRequest request) {

        List<Integer> sentQuestions = request.questions().stream().map(CoverLetterAssignmentRequest.Question::questionId)
                .toList();
        Set<Long> sentCards = request.cards().stream().map(CoverLetterAssignmentRequest.Card::cardId)
                .collect(Collectors.toSet());

        List<CoverLetterAssignment.Question> assignments = new ArrayList<>();
        for (JsonNode node : array(data, "assignments")) {
            assignments.add(question(node, sentCards));
        }
        if (!assignments.stream().map(CoverLetterAssignment.Question::questionId).toList().equals(sentQuestions)) {
            throw AiClientException.invalidResponse();
        }

        Set<Integer> questionIds = new HashSet<>(sentQuestions);
        List<CoverLetterAssignment.Step> steps = new ArrayList<>();
        for (JsonNode node : array(data, "steps")) {
            steps.add(step(node, questionIds, sentCards));
        }

        return new CoverLetterAssignment(List.copyOf(assignments), List.copyOf(steps));

    }

    private static CoverLetterAssignment.Question question(JsonNode node, Set<Long> sentCards) {

        List<CoverLetterAssignment.Considered> considered = new ArrayList<>();
        for (JsonNode card : array(node, "considered")) {
            considered.add(new CoverLetterAssignment.Considered(sentCard(card, "card_id", sentCards),
                    optionalString(card, "note")));
        }

        return new CoverLetterAssignment.Question(positiveInt(node, "question_id"),
                optionalOneOf(node, "question_theme", THEMES), optionalCard(node, "primary_card_id", sentCards),
                optionalCard(node, "supporting_card_id", sentCards), optionalString(node, "reason"),
                List.copyOf(considered), oneOf(node, "decided_by", DECIDERS));

    }

    private static CoverLetterAssignment.Step step(JsonNode node, Set<Integer> questionIds, Set<Long> sentCards) {

        Integer questionId = node.path("question_id").isNull() || node.path("question_id").isMissingNode()
                ? null : positiveInt(node, "question_id");
        if (questionId != null && !questionIds.contains(questionId)) {
            throw AiClientException.invalidResponse();
        }

        return new CoverLetterAssignment.Step(positiveInt(node, "seq"), oneOf(node, "actor", ACTORS),
                oneOf(node, "kind", KINDS), questionId, optionalCard(node, "card_id", sentCards),
                optionalString(node, "reason"));

    }

    private static JsonNode array(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (!value.isArray()) {
            throw AiClientException.invalidResponse();
        }
        return value;

    }

    private static int positiveInt(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
            throw AiClientException.invalidResponse();
        }
        return value.intValue();

    }

    private static long sentCard(JsonNode node, String field, Set<Long> sentCards) {

        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || !sentCards.contains(value.longValue())) {
            throw AiClientException.invalidResponse();
        }
        return value.longValue();

    }

    private static Long optionalCard(JsonNode node, String field, Set<Long> sentCards) {

        return node.path(field).isNull() || node.path(field).isMissingNode() ? null : sentCard(node, field, sentCards);

    }

    private static String oneOf(JsonNode node, String field, Set<String> allowed) {

        JsonNode value = node.path(field);
        if (!value.isString() || !allowed.contains(value.asString())) {
            throw AiClientException.invalidResponse();
        }
        return value.asString();

    }

    private static String optionalOneOf(JsonNode node, String field, Set<String> allowed) {

        return node.path(field).isNull() || node.path(field).isMissingNode() ? null : oneOf(node, field, allowed);

    }

    private static String optionalString(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (!value.isString()) {
            throw AiClientException.invalidResponse();
        }
        return value.asString().isBlank() ? null : value.asString().strip();

    }

    private static AgentUnavailableException unavailable(AiClientException failure) {

        boolean retryable = retryable(failure);
        return new AgentUnavailableException("AI 자소서 카드 고르기 실패 (" + failure.errorCode() + ")", retryable,
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
