package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.CoverLetterAppend;
import com.gitory.backend.agent.port.CoverLetterAppendPort;
import com.gitory.backend.agent.port.CoverLetterAppendRequest;
import com.gitory.backend.agent.port.CoverLetterFill;
import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;

/**
 * AI 새 카드 덧붙이기 API 를 부르고, 응답이 다시 쓴 글과 같은 허용 목록·문항 확인을 통과하는지 본다
 */
@Component
@RequiredArgsConstructor
public class AiCoverLetterAppendAdapter implements CoverLetterAppendPort {

    private static final String APPEND_PATH = "/internal/cover-letter-append";

    // AI 가 60초 안에 답하므로 그 답을 놓치지 않게 조금 더 기다린다
    private static final Duration APPEND_READ_TIMEOUT = Duration.ofSeconds(70);

    private final AiHttpClient ai;

    @Override
    public CoverLetterAppend append(CoverLetterAppendRequest request) {

        JsonNode data;
        try {
            data = ai.post(APPEND_PATH, request, APPEND_READ_TIMEOUT);
        } catch (AiClientException failure) {
            throw AiCoverLetterTurnAdapter.unavailable("덧붙이기", failure);
        }
        try {
            CoverLetterFill filled = AiCoverLetterTurnAdapter.filled(data, request.target().questionId());
            return new CoverLetterAppend(filled.draft(), filled.reverted());
        } catch (RuntimeException malformed) {
            throw AiCoverLetterTurnAdapter.unavailable("덧붙이기", AiClientException.invalidResponse());
        }

    }
}
