package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.RepoSummaryPort;
import com.gitory.backend.agent.port.RepoSummaryRequest;
import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class AiRepoSummaryAdapter implements RepoSummaryPort {

    private static final String PATH = "/internal/repo-summary";
    // 사용자가 화면에서 기다리는 호출이 아니라 분석 Job 안에서 부르므로 넉넉히 둔다
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    private final AiHttpClient ai;

    @Override
    public Optional<String> summarize(RepoSummaryRequest request) {

        JsonNode data;
        try {
            data = ai.post(PATH, request, READ_TIMEOUT);
        } catch (AiClientException failure) {
            throw new AgentUnavailableException("AI 저장소 소개 실패 (" + failure.errorCode() + ")", true, false, failure);
        }

        JsonNode echoed = data.path("repo_id");
        JsonNode summary = data.path("summary");
        if (!echoed.isIntegralNumber() || echoed.longValue() != request.repoId()
                || !(summary.isNull() || summary.isString())) {
            throw new AgentUnavailableException("AI 저장소 소개 응답이 이상하다", false, true,
                    AiClientException.invalidResponse());
        }

        String sentence = summary.isString() ? summary.asString().strip() : "";

        return sentence.isEmpty() ? Optional.empty() : Optional.of(sentence);

    }
}
