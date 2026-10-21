package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.StarConfidence;
import com.gitory.backend.agent.port.StarDraft;
import com.gitory.backend.agent.port.StarDraftPort;
import com.gitory.backend.agent.port.StarDraftRequest;
import com.gitory.backend.agent.port.StarEmptyReason;
import com.gitory.backend.agent.port.StarEvidenceRef;
import com.gitory.backend.agent.port.StarSlot;
import com.gitory.backend.agent.port.StarSlotDraft;
import com.gitory.backend.agent.port.StarSlotStatus;
import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiClientProperties;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI STAR 초안을 부르고, 네 칸이 카드에 저장해도 되는 모양인지 확인한 뒤 돌려준다
 * 실패는 다시 시도해도 되는 것과 응답이 이상한 것 둘 중 하나로만 던진다
 */
@Component
@RequiredArgsConstructor
public class AiStarDraftAdapter implements StarDraftPort {

    private static final String PATH = "/internal/analysis/star";

    private final AiHttpClient ai;
    private final AiClientProperties properties;

    @Override
    public StarDraft draft(StarDraftRequest request) {

        JsonNode data;
        try {
            data = ai.post(PATH, request, properties.starReadTimeout());
        } catch (AiClientException failure) {
            throw unavailable(failure);
        }
        try {
            return draft(data);
        } catch (RuntimeException malformed) {
            // 응답 원문이 든 예외는 원인으로 넘기지 않는다
            throw unavailable(AiClientException.invalidResponse());
        }

    }

    private static StarDraft draft(JsonNode data) {

        JsonNode star = data.path("star");
        if (!star.isObject() || star.size() != StarSlot.values().length) {
            throw AiClientException.invalidResponse();
        }
        Map<StarSlot, StarSlotDraft> slots = new EnumMap<>(StarSlot.class);
        Set<StarSlot> emptySlots = EnumSet.noneOf(StarSlot.class);
        for (StarSlot slot : StarSlot.values()) {
            StarSlotDraft draft = slot(star.path(slot.name()));
            slots.put(slot, draft);
            if (draft.status() == StarSlotStatus.EMPTY) {
                emptySlots.add(slot);
            }
        }
        List<StarSlot> missingFields = new ArrayList<>();
        for (String field : strings(data, "missing_fields")) {
            missingFields.add(StarSlot.valueOf(field));
        }
        if (missingFields.size() != emptySlots.size() || !Set.copyOf(missingFields).equals(emptySlots)) {
            throw AiClientException.invalidResponse();
        }
        return new StarDraft(string(data, "title"), Map.copyOf(slots), List.copyOf(missingFields),
                optionalString(data, "shared_with"), strings(data, "removed_claims"), optionalStrings(data, "areas"),
                optionalStrings(data, "techs"));

    }

    /** 채운 칸은 문장과 확신도가, 비운 칸은 비운 이유 코드가 있어야 하고 카드는 LOW 를 확인 필요로 읽으므로 둘은 늘 함께다 */
    private static StarSlotDraft slot(JsonNode node) {

        if (!node.isObject()) {
            throw AiClientException.invalidResponse();
        }
        StarSlotStatus status = StarSlotStatus.valueOf(string(node, "status"));
        String text = optionalString(node, "text");
        String confidence = optionalString(node, "confidence");
        String reason = optionalString(node, "insufficient_reason_code");
        List<StarEvidenceRef> evidence = new ArrayList<>();
        for (JsonNode ref : array(node, "evidence")) {
            evidence.add(evidenceRef(ref));
        }
        if (status == StarSlotStatus.EMPTY) {
            if (reason == null || (text != null && !text.isBlank()) || !evidence.isEmpty()) {
                throw AiClientException.invalidResponse();
            }
            return new StarSlotDraft(status, null, null, List.of(), StarEmptyReason.valueOf(reason),
                    optionalString(node, "insufficient_reason"), null);
        }
        if (text == null || text.isBlank() || confidence == null || reason != null) {
            throw AiClientException.invalidResponse();
        }
        StarConfidence parsed = StarConfidence.valueOf(confidence);
        if ((status == StarSlotStatus.NEEDS_REVIEW) != (parsed == StarConfidence.LOW)) {
            throw AiClientException.invalidResponse();
        }
        return new StarSlotDraft(status, text, parsed, List.copyOf(evidence), null, null,
                optionalString(node, "review_reason"));

    }

    private static StarEvidenceRef evidenceRef(JsonNode node) {

        String sha = string(node, "sha");
        if (sha.length() < 7 || sha.length() > 40) {
            throw AiClientException.invalidResponse();
        }
        return new StarEvidenceRef(sha, string(node, "message"), optionalString(node, "url"));

    }

    private static JsonNode array(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (!value.isArray()) {
            throw AiClientException.invalidResponse();
        }
        return value;

    }

    private static String string(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (!value.isString()) {
            throw AiClientException.invalidResponse();
        }
        return value.asString();

    }

    private static String optionalString(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return string(node, field);

    }

    private static List<String> strings(JsonNode node, String field) {

        List<String> values = new ArrayList<>();
        for (JsonNode value : array(node, field)) {
            if (!value.isString()) {
                throw AiClientException.invalidResponse();
            }
            values.add(value.asString());
        }
        return List.copyOf(values);

    }

    // 태그를 보내기 전의 AI 와도 같이 돌도록 칸이 없으면 빈 목록이다
    private static List<String> optionalStrings(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return List.of();
        }
        return strings(node, field);

    }

    private static AgentUnavailableException unavailable(AiClientException failure) {

        boolean retryable = retryable(failure);
        return new AgentUnavailableException("AI STAR 초안 실패 (" + failure.errorCode() + ")", retryable,
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
