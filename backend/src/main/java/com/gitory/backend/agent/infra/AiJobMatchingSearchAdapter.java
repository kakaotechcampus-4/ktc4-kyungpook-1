package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.CriterionMatch;
import com.gitory.backend.agent.port.JobMatchingSearchPort;
import com.gitory.backend.agent.port.MatchingSearchRequest;
import com.gitory.backend.agent.port.MatchingSearchResult;
import com.gitory.backend.agent.port.MatchingSearchUnavailableException;
import com.gitory.backend.agent.port.MatchingStatement;
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
 * AI 매칭 검색을 부르고, 응답이 보낸 문장에 대한 것인지 확인한 뒤 돌려준다
 * 실패는 다시 시도해도 되는 것과 응답이 이상한 것 둘 중 하나로만 던진다
 */
@Component
@RequiredArgsConstructor
public class AiJobMatchingSearchAdapter implements JobMatchingSearchPort {

    private static final String PATH = "/internal/matching/search";

    private final AiHttpClient ai;
    private final AiClientProperties properties;

    @Override
    public MatchingSearchResult search(MatchingSearchRequest request) {

        JsonNode data;
        try {
            data = ai.post(PATH, request, properties.matchingSearchReadTimeout());
        } catch (AiClientException failure) {
            throw unavailable(failure);
        }
        try {
            return result(data, request);
        } catch (RuntimeException malformed) {
            // 응답 원문이 든 예외는 원인으로 넘기지 않는다
            throw unavailable(AiClientException.invalidResponse());
        }

    }

    private static MatchingSearchResult result(JsonNode data, MatchingSearchRequest request) {

        Set<Long> sent = new HashSet<>();
        for (MatchingStatement statement : request.statements()) {
            sent.add(statement.statementId());
        }
        JsonNode nodes = data.path("matches");
        if (!nodes.isArray()) {
            throw AiClientException.invalidResponse();
        }
        List<CriterionMatch> matches = new ArrayList<>();
        for (JsonNode node : nodes) {
            long statementId = positiveId(node, "statement_id");
            if (!sent.contains(statementId)) {
                throw AiClientException.invalidResponse();
            }
            matches.add(new CriterionMatch(positiveId(node, "criterion_id"), statementId, distance(node)));
        }
        JsonNode collection = data.path("collection_criteria");
        if (!collection.isIntegralNumber() || collection.intValue() < 0) {
            throw AiClientException.invalidResponse();
        }
        return new MatchingSearchResult(List.copyOf(matches), collection.intValue());

    }

    private static long positiveId(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || value.longValue() <= 0) {
            throw AiClientException.invalidResponse();
        }
        return value.longValue();

    }

    private static double distance(JsonNode node) {

        JsonNode value = node.path("distance");
        if (!value.isNumber() || value.doubleValue() < 0 || !Double.isFinite(value.doubleValue())) {
            throw AiClientException.invalidResponse();
        }
        return value.doubleValue();

    }

    private static MatchingSearchUnavailableException unavailable(AiClientException failure) {

        boolean retryable = retryable(failure);
        return new MatchingSearchUnavailableException("AI 매칭 검색 실패 (" + failure.errorCode() + ")", retryable,
                !retryable, failure.errorCode(), failure);

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
