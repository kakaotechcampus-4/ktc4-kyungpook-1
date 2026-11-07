package com.gitory.backend.matching.infra;

import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.matching.domain.GapFillAction;
import com.gitory.backend.matching.domain.GapFillCondition;
import com.gitory.backend.matching.domain.GapFillDecision;
import com.gitory.backend.matching.domain.GapFillPort;
import com.gitory.backend.matching.domain.GapFillProposal;
import com.gitory.backend.matching.domain.GapFillRequest;
import com.gitory.backend.matching.domain.GapFillResult;
import com.gitory.backend.matching.domain.GapFillStep;
import com.gitory.backend.matching.domain.GapFillStepKind;
import com.gitory.backend.matching.domain.MatchingAiUnavailableException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * AI 모자란 조건 채우기 API 를 consent 를 거쳐 사용자의 GitHub 토큰과 함께 부르고, 보낸 id 만 가리키는 조건 상태만 받아들인다
 * 제안은 종류마다 맞는 id(후보·작성 중 카드·분석하지 않은 저장소)만 가리켜야 화면이 지금 있는 API 로 넘길 수 있다
 */
@Component
@RequiredArgsConstructor
public class AiGapFillAdapter implements GapFillPort {

    private static final String GAP_CONDITION = "GAP_CONDITION";

    private final GithubCollectionAccessPort github;

    @Override
    public List<GapFillCondition> fill(Long userId, GapFillRequest request, Consumer<GapFillCondition> onCondition) {

        Targets targets = Targets.of(request);
        JsonNode data;
        try {
            data = github.gapFill(userId, AiGapFillRequest.of(request),
                    progress -> forward(progress.liveItem(), targets, onCondition));
        } catch (AiClientException failure) {
            throw unavailable(failure);
        }
        try {
            return conditions(data, targets);
        } catch (RuntimeException malformed) {
            // 응답 원문이 든 예외는 원인으로 넘기지 않는다
            throw unavailable(AiClientException.invalidResponse());
        }

    }

    // 진행 줄은 화면에 보여 줄 값이라 모양이 틀린 줄만 버리고 찾기는 계속 받는다
    private static void forward(JsonNode item, Targets targets, Consumer<GapFillCondition> onCondition) {

        if (item == null || !GAP_CONDITION.equals(item.path("kind").asString(""))) {
            return;
        }
        liveCondition(item.path("condition"), targets).ifPresent(onCondition);

    }

    private static Optional<GapFillCondition> liveCondition(JsonNode node, Targets targets) {

        try {
            return Optional.of(condition(node, targets));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }

    }

    private static List<GapFillCondition> conditions(JsonNode data, Targets targets) {

        List<GapFillCondition> conditions = new ArrayList<>();
        for (JsonNode node : array(data, "conditions")) {
            conditions.add(condition(node, targets));
        }
        if (!conditions.stream().map(GapFillCondition::conditionId).toList().equals(targets.conditionIds())) {
            throw AiClientException.invalidResponse();
        }
        return List.copyOf(conditions);

    }

    private static GapFillCondition condition(JsonNode node, Targets targets) {

        UUID conditionId = uuid(node, "condition_id");
        if (!targets.conditionIds().contains(conditionId)) {
            throw AiClientException.invalidResponse();
        }
        List<GapFillStep> steps = new ArrayList<>();
        for (JsonNode step : array(node, "steps")) {
            steps.add(new GapFillStep(oneOf(step, "kind", GapFillStepKind.class), optionalCount(step),
                    optionalString(step, "query"), optionalString(step, "repo_name")));
        }
        JsonNode proposal = node.path("proposal");
        JsonNode forced = node.path("forced");
        return new GapFillCondition(conditionId, optionalOneOf(node, "decision", GapFillDecision.class),
                optionalString(node, "decision_reason"), forced.isBoolean() && forced.asBoolean(), List.copyOf(steps),
                absent(proposal) ? null : proposal(proposal, targets),
                optionalOneOf(node, "result", GapFillResult.class));

    }

    private static GapFillProposal proposal(JsonNode node, Targets targets) {

        GapFillAction action = oneOf(node, "action", GapFillAction.class);
        UUID targetId = uuid(node, "target_id");
        if (!targets.allowed(action).contains(targetId)) {
            throw AiClientException.invalidResponse();
        }
        JsonNode reason = node.path("reason");
        if (!reason.isString()) {
            throw AiClientException.invalidResponse();
        }
        return new GapFillProposal(action, targetId, null, null, null, reason.asString());

    }

    private static JsonNode array(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (!value.isArray()) {
            throw AiClientException.invalidResponse();
        }
        return value;

    }

    private static UUID uuid(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (!value.isString()) {
            throw AiClientException.invalidResponse();
        }
        return UUID.fromString(value.asString());

    }

    private static <E extends Enum<E>> E oneOf(JsonNode node, String field, Class<E> type) {

        JsonNode value = node.path(field);
        if (!value.isString()) {
            throw AiClientException.invalidResponse();
        }
        return Enum.valueOf(type, value.asString());

    }

    private static <E extends Enum<E>> E optionalOneOf(JsonNode node, String field, Class<E> type) {

        return absent(node.path(field)) ? null : oneOf(node, field, type);

    }

    private static Integer optionalCount(JsonNode node) {

        JsonNode value = node.path("count");
        if (absent(value)) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw AiClientException.invalidResponse();
        }
        return value.intValue();

    }

    private static String optionalString(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (absent(value)) {
            return null;
        }
        if (!value.isString()) {
            throw AiClientException.invalidResponse();
        }
        return value.asString();

    }

    private static boolean absent(JsonNode value) {

        return value.isNull() || value.isMissingNode();

    }

    private static MatchingAiUnavailableException unavailable(AiClientException failure) {

        return new MatchingAiUnavailableException("모자란 조건 채우기를 AI 에게서 받지 못했다 (" + failure.errorCode() + ")");

    }

    private record Targets(List<UUID> conditionIds, Map<GapFillAction, Set<UUID>> byAction) {

        static Targets of(GapFillRequest request) {

            return new Targets(request.conditions().stream().map(GapFillRequest.Condition::conditionId).toList(),
                    Map.of(GapFillAction.CREATE_CARD, request.candidates().stream()
                                    .map(GapFillRequest.Candidate::candidateId)
                                    .collect(Collectors.toSet()),
                            GapFillAction.CONTINUE_CARD, request.draftCards().stream()
                                    .map(GapFillRequest.Card::cardId)
                                    .collect(Collectors.toSet()),
                            GapFillAction.ANALYZE_REPO, request.repos().stream()
                                    .filter(repo -> !repo.analyzed())
                                    .map(GapFillRequest.Repo::repoId)
                                    .collect(Collectors.toSet())));

        }

        Set<UUID> allowed(GapFillAction action) {

            return byAction.get(action);

        }
    }
}
