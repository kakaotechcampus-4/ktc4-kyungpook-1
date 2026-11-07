package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.JudgeCard;
import com.gitory.backend.agent.port.JudgeCondition;
import com.gitory.backend.agent.port.JudgedCondition;
import com.gitory.backend.agent.port.JudgedCondition.ConditionSupport;
import com.gitory.backend.agent.port.MatchJudgementPort;
import com.gitory.backend.agent.port.MatchJudgementRequest;
import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AI 조건 판단을 부르고, 응답이 보낸 조건을 하나씩 모두 판단했는지와 보낸 카드만 골랐는지 확인한다
 * 판단은 저장돼 세 화면이 같이 쓰므로, 조건 하나라도 빠지거나 모르는 카드가 오면 전체를 받지 않는다
 */
@Component
@RequiredArgsConstructor
public class AiMatchJudgementAdapter implements MatchJudgementPort {

    private static final String PATH = "/internal/matching/judgements";
    // AI 가 공고 하나를 90초 안에 끝내고, 그보다 늦으면 이 공고만 빼고 추천을 계산한다
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(100);

    private final AiHttpClient ai;

    @Override
    public List<JudgedCondition> judge(MatchJudgementRequest request) {

        JsonNode data;
        try {
            data = ai.post(PATH, request, READ_TIMEOUT);
        } catch (AiClientException failure) {
            throw new AgentUnavailableException("AI 조건 판단 실패 (" + failure.errorCode() + ")", true, false, failure);
        }
        try {
            return judgements(data, request);
        } catch (RuntimeException malformed) {
            // 응답 원문이 든 예외는 원인으로 넘기지 않는다
            throw new AgentUnavailableException("AI 조건 판단 응답이 이상하다", false, true,
                    AiClientException.invalidResponse());
        }

    }

    private static List<JudgedCondition> judgements(JsonNode data, MatchJudgementRequest request) {

        Set<String> conditions = new HashSet<>(request.conditions().stream().map(JudgeCondition::conditionId).toList());
        Set<String> cards = new HashSet<>(request.cards().stream().map(JudgeCard::cardId).toList());
        JsonNode nodes = data.path("judgements");
        if (!nodes.isArray() || nodes.size() != conditions.size()) {
            throw AiClientException.invalidResponse();
        }

        List<JudgedCondition> judged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode node : nodes) {
            String conditionId = node.path("condition_id").asString();
            if (!conditions.contains(conditionId) || !seen.add(conditionId)) {
                throw AiClientException.invalidResponse();
            }
            judged.add(new JudgedCondition(conditionId, supports(node.path("met_by"), cards)));
        }
        return List.copyOf(judged);

    }

    private static List<ConditionSupport> supports(JsonNode nodes, Set<String> cards) {

        if (!nodes.isArray()) {
            throw AiClientException.invalidResponse();
        }
        List<ConditionSupport> supports = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode node : nodes) {
            String cardId = node.path("card_id").asString();
            String reason = node.path("reason").asString();
            if (!cards.contains(cardId) || !seen.add(cardId) || reason.isBlank()) {
                throw AiClientException.invalidResponse();
            }
            supports.add(new ConditionSupport(cardId, reason));
        }
        return List.copyOf(supports);

    }
}
