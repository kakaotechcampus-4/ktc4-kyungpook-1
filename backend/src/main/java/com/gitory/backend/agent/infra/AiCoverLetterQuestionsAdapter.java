package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.CoverLetterQuestions;
import com.gitory.backend.agent.port.CoverLetterQuestionsPort;
import com.gitory.backend.agent.port.CoverLetterQuestionsRequest;
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

/**
 * AI 자소서 질문 만들기 API 를 부르고, 응답이 저장해도 되는 모양인지 확인한 뒤 돌려준다
 * 답은 항목으로 질문을 찾아 붙으므로, 한 문항에서 같은 항목을 두 번 묻거나 3개를 넘게 묻는 응답은 이상 응답으로 끝낸다
 */
@Component
@RequiredArgsConstructor
public class AiCoverLetterQuestionsAdapter implements CoverLetterQuestionsPort {

    private static final String PATH = "/internal/cover-letter-questions";

    private static final Set<String> ITEMS = Set.of("SITUATION", "ACTION", "RESULT", "LEARNING", "TRIGGER",
            "JOB_LINK", "REASON");
    // 화면 계약이 문항마다 AI 질문을 3개까지로 정했다
    private static final int MAX_ASKS = 3;

    private final AiHttpClient ai;
    private final AiClientProperties properties;

    @Override
    public CoverLetterQuestions ask(CoverLetterQuestionsRequest request) {

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

    private static CoverLetterQuestions response(JsonNode data, CoverLetterQuestionsRequest request) {

        List<CoverLetterQuestions.Question> questions = new ArrayList<>();
        for (JsonNode node : array(data, "questions")) {
            questions.add(question(node));
        }
        List<Integer> sent = request.questions().stream().map(CoverLetterQuestionsRequest.Question::questionId)
                .toList();
        if (!questions.stream().map(CoverLetterQuestions.Question::questionId).toList().equals(sent)) {
            throw AiClientException.invalidResponse();
        }

        return new CoverLetterQuestions(List.copyOf(questions));

    }

    private static CoverLetterQuestions.Question question(JsonNode node) {

        List<String> requiredMissing = new ArrayList<>();
        for (JsonNode item : array(node, "required_missing")) {
            requiredMissing.add(item(item));
        }

        List<CoverLetterQuestions.Ask> asks = new ArrayList<>();
        Set<String> asked = new HashSet<>();
        for (JsonNode ask : array(node, "asks")) {
            String item = item(ask.path("item"));
            if (!asked.add(item)) {
                throw AiClientException.invalidResponse();
            }
            asks.add(new CoverLetterQuestions.Ask(item, text(ask.path("question"))));
        }
        if (asks.size() > MAX_ASKS) {
            throw AiClientException.invalidResponse();
        }

        return new CoverLetterQuestions.Question(positiveInt(node, "question_id"), List.copyOf(requiredMissing),
                List.copyOf(asks));

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

    private static String item(JsonNode value) {

        if (!value.isString() || !ITEMS.contains(value.asString())) {
            throw AiClientException.invalidResponse();
        }
        return value.asString();

    }

    private static String text(JsonNode value) {

        if (!value.isString() || value.asString().isBlank()) {
            throw AiClientException.invalidResponse();
        }
        return value.asString().strip();

    }

    private static AgentUnavailableException unavailable(AiClientException failure) {

        boolean retryable = retryable(failure);
        return new AgentUnavailableException("AI 자소서 질문 만들기 실패 (" + failure.errorCode() + ")", retryable,
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
