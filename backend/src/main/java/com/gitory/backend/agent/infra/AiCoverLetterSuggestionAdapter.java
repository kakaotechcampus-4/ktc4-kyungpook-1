package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.CoverLetterSuggestionPort;
import com.gitory.backend.agent.port.CoverLetterSuggestionRequest;
import com.gitory.backend.agent.port.CoverLetterSuggestions;
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
 * AI 자소서 제안 API 를 부르고, 응답이 저장해도 되는 모양인지 확인한 뒤 돌려준다
 * 보내지 않은 카드·후보를 가리키거나 종류가 id 와 맞지 않거나 같은 것을 두 번 고른 응답은 이상 응답으로 끝내, 남의 카드가 제안으로 저장되지 않게 한다
 */
@Component
@RequiredArgsConstructor
public class AiCoverLetterSuggestionAdapter implements CoverLetterSuggestionPort {

    private static final String PATH = "/internal/cover-letter-suggestions";
    private static final String CARD = "CARD";
    private static final String CANDIDATE = "CANDIDATE";
    private static final int MAX_SUGGESTIONS = 3;
    private static final int ANGLE_MAX_LENGTH = 60;

    private final AiHttpClient ai;
    private final AiClientProperties properties;

    @Override
    public CoverLetterSuggestions suggest(CoverLetterSuggestionRequest request) {

        JsonNode data;
        try {
            data = ai.post(PATH, request, properties.suggestReadTimeout());
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

    private static CoverLetterSuggestions response(JsonNode data, CoverLetterSuggestionRequest request) {

        Set<String> cardIds = request.cards().stream()
                .map(card -> String.valueOf(card.cardId()))
                .collect(Collectors.toSet());
        Set<String> candidateIds = request.candidates().stream()
                .map(CoverLetterSuggestionRequest.Candidate::candidateId)
                .collect(Collectors.toSet());

        JsonNode array = data.path("suggestions");
        if (!array.isArray() || array.size() > MAX_SUGGESTIONS) {
            throw AiClientException.invalidResponse();
        }
        Set<String> seen = new HashSet<>();
        List<CoverLetterSuggestions.Item> items = new ArrayList<>();
        for (JsonNode node : array) {
            String source = string(node, "source");
            String id = string(node, "id");
            String angle = string(node, "angle").strip();
            boolean sent = switch (source) {
                case CARD -> cardIds.contains(id);
                case CANDIDATE -> candidateIds.contains(id);
                default -> false;
            };
            if (!sent || !seen.add(source + ":" + id) || angle.isEmpty() || angle.length() > ANGLE_MAX_LENGTH) {
                throw AiClientException.invalidResponse();
            }
            items.add(new CoverLetterSuggestions.Item(source, id, angle));
        }

        return new CoverLetterSuggestions(List.copyOf(items));

    }

    private static String string(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (!value.isString()) {
            throw AiClientException.invalidResponse();
        }
        return value.asString();

    }

    private static AgentUnavailableException unavailable(AiClientException failure) {

        boolean retryable = retryable(failure);
        return new AgentUnavailableException("AI 자소서 제안 실패 (" + failure.errorCode() + ")", retryable,
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
