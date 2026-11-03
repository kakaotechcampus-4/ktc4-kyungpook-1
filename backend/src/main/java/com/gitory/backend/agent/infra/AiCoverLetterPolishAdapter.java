package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.CoverLetterDrafts;
import com.gitory.backend.agent.port.CoverLetterPolish;
import com.gitory.backend.agent.port.CoverLetterPolishPort;
import com.gitory.backend.agent.port.CoverLetterPolishRequest;
import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiClientProperties;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 자소서 첨삭 API 를 부르고, 진단과 고친 글이 저장해도 되는 모양인지 확인한 뒤 돌려준다
 * 고친 글의 모양·표시·과정은 초안 쓰기와 같은 허용 목록으로 보고, 포인트가 없는 문장을 가리키면 화면이 칠할 곳이 없어 이상 응답으로 끝낸다
 */
@Component
@RequiredArgsConstructor
public class AiCoverLetterPolishAdapter implements CoverLetterPolishPort {

    private static final String PATH = "/internal/cover-letter-polish";

    private static final Set<String> CATEGORIES = Set.of("FIT", "EVIDENCE", "READABILITY", "OVERLAP", "TECH_TERMS");
    private static final Map<String, Integer> MAX_POINTS = Map.of("WEAK", 4, "GOOD", 3);

    private final AiHttpClient ai;
    private final AiClientProperties properties;

    @Override
    public CoverLetterPolish polish(CoverLetterPolishRequest request) {

        JsonNode data;
        try {
            data = ai.post(PATH, request, properties.readTimeout());
        } catch (AiClientException failure) {
            throw unavailable(failure);
        }
        try {
            return response(data, request.target().questionId());
        } catch (RuntimeException malformed) {
            // 응답 원문이 든 예외는 원인으로 넘기지 않는다
            throw unavailable(AiClientException.invalidResponse());
        }

    }

    // 과정은 저장하지 않지만, 모르는 값이 오면 AI 와 계약이 어긋난 것이라 고친 글도 믿지 않는다
    private static CoverLetterPolish response(JsonNode data, int questionId) {

        CoverLetterDrafts.Draft draft = AiCoverLetterDraftsAdapter.draft(data.path("draft"));
        if (draft.questionId() != questionId) {
            throw AiClientException.invalidResponse();
        }
        for (JsonNode step : AiCoverLetterDraftsAdapter.array(data, "steps")) {
            AiCoverLetterDraftsAdapter.step(step, Set.of(questionId));
        }

        List<CoverLetterPolish.Point> points = new ArrayList<>();
        Set<Integer> seqs = new HashSet<>();
        for (JsonNode node : AiCoverLetterDraftsAdapter.array(data, "points")) {
            CoverLetterPolish.Point point = point(node, draft.sentences().size());
            if (!seqs.add(point.seq())) {
                throw AiClientException.invalidResponse();
            }
            points.add(point);
        }
        MAX_POINTS.forEach((kind, max) -> {
            if (points.stream().filter(point -> point.kind().equals(kind)).count() > max) {
                throw AiClientException.invalidResponse();
            }
        });

        return new CoverLetterPolish(AiCoverLetterDraftsAdapter.text(data, "summary"), List.copyOf(points), draft);

    }

    private static CoverLetterPolish.Point point(JsonNode node, int sentenceCount) {

        List<Integer> sentenceSeqs = new ArrayList<>();
        for (JsonNode seq : AiCoverLetterDraftsAdapter.array(node, "sentence_seqs")) {
            if (!seq.isIntegralNumber() || !seq.canConvertToInt() || seq.intValue() < 1
                    || seq.intValue() > sentenceCount) {
                throw AiClientException.invalidResponse();
            }
            sentenceSeqs.add(seq.intValue());
        }

        return new CoverLetterPolish.Point(AiCoverLetterDraftsAdapter.positiveInt(node, "seq"),
                AiCoverLetterDraftsAdapter.oneOf(node, "kind", MAX_POINTS.keySet()),
                AiCoverLetterDraftsAdapter.oneOf(node, "category", CATEGORIES),
                AiCoverLetterDraftsAdapter.text(node, "title"), AiCoverLetterDraftsAdapter.text(node, "detail"),
                List.copyOf(sentenceSeqs));

    }

    // AI 는 LLM 이 실패하면 뼈대로 대신하지 않고 503 을 돌려주므로, 그 실패는 다시 해 볼 만하다
    private static AgentUnavailableException unavailable(AiClientException failure) {

        boolean retryable = failure.temporary();
        return new AgentUnavailableException("AI 자소서 첨삭 실패 (" + failure.errorCode() + ")", retryable,
                !retryable, failure);

    }
}
