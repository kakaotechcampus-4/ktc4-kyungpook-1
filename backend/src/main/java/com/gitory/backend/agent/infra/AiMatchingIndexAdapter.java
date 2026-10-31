package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.IndexPosting;
import com.gitory.backend.agent.port.JobMatchingIndexPort;
import com.gitory.backend.agent.port.MatchingIndexResult;
import com.gitory.backend.agent.port.MatchingIndexUnavailableException;
import com.gitory.backend.agent.port.MatchingPruneResult;
import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiClientProperties;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * AI 공고 색인과 정리를 부르고, 응답의 개수가 정수인지 확인한 뒤 돌려준다
 * 색인 요청에는 지우기 칸이 없고, 지우기는 남길 공고 목록을 따로 보내는 정리 호출이 맡는다
 */
@Component
@RequiredArgsConstructor
public class AiMatchingIndexAdapter implements JobMatchingIndexPort {

    private static final String INDEX_PATH = "/internal/matching/postings/index";
    private static final String PRUNE_PATH = "/internal/matching/postings/prune";

    private final AiHttpClient ai;
    private final AiClientProperties properties;

    @Override
    public MatchingIndexResult index(List<IndexPosting> postings) {

        JsonNode data = call(INDEX_PATH, Map.of("postings", postings));
        try {
            return new MatchingIndexResult(count(data, "indexed_postings"), count(data, "indexed_criteria"),
                    count(data, "deactivated_postings"));
        } catch (RuntimeException malformed) {
            throw unavailable(AiClientException.invalidResponse());
        }

    }

    @Override
    public MatchingPruneResult prune(List<Long> keepPostingIds) {

        JsonNode data = call(PRUNE_PATH, Map.of("keep_posting_ids", keepPostingIds));
        try {
            return new MatchingPruneResult(count(data, "kept_postings"), count(data, "removed_postings"));
        } catch (RuntimeException malformed) {
            throw unavailable(AiClientException.invalidResponse());
        }

    }

    private JsonNode call(String path, Map<String, Object> request) {

        try {
            return ai.post(path, request, properties.matchingIndexReadTimeout());
        } catch (AiClientException failure) {
            throw unavailable(failure);
        }

    }

    private static int count(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (!value.isInt() || value.intValue() < 0) {
            throw AiClientException.invalidResponse();
        }
        return value.intValue();

    }

    private static MatchingIndexUnavailableException unavailable(AiClientException failure) {

        return new MatchingIndexUnavailableException("AI 공고 색인 실패 (" + failure.errorCode() + ")",
                failure.retryable(), "AI_INVALID_RESPONSE".equals(failure.errorCode()), failure.errorCode(), failure);

    }
}
