package com.gitory.backend.consent.infra;

import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.consent.port.DiffEvidence;
import com.gitory.backend.consent.port.DiffEvidencePartialReason;
import com.gitory.backend.consent.port.DiffEvidenceResult;
import com.gitory.backend.consent.port.DiffEvidenceSource;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** AI diff 근거 응답이 카드 초안에 넘겨도 되는 모양인지 확인하고, 아니면 응답 원문 없이 거절한다 */
final class DiffEvidenceReader {

    private DiffEvidenceReader() {
    }

    static DiffEvidenceResult read(JsonNode data, long userRepositoryId) {

        try {
            JsonNode echoed = data.path("user_repository_id");
            if (!echoed.isIntegralNumber() || echoed.longValue() != userRepositoryId) {
                throw AiClientException.invalidResponse();
            }
            List<DiffEvidence> evidence = new ArrayList<>();
            for (JsonNode item : array(data, "evidence")) {
                evidence.add(evidence(item));
            }
            boolean partial = bool(data, "partial");
            String reason = optionalString(data, "partial_reason");
            List<String> missing = strings(data, "missing_commit_shas");
            if (partial != (reason != null) || partial == missing.isEmpty()) {
                throw AiClientException.invalidResponse();
            }
            return new DiffEvidenceResult(List.copyOf(evidence), partial,
                    reason == null ? null : DiffEvidencePartialReason.valueOf(reason), missing);
        } catch (AiClientException invalid) {
            throw invalid;
        } catch (RuntimeException malformed) {
            // 응답 원문이 든 예외는 원인으로 넘기지 않는다
            throw AiClientException.invalidResponse();
        }

    }

    private static DiffEvidence evidence(JsonNode node) {

        String sha = string(node, "sha");
        if (sha.length() < 7 || sha.length() > 40) {
            throw AiClientException.invalidResponse();
        }
        return new DiffEvidence(sha, string(node, "message"), optionalString(node, "author_login"),
                string(node, "churn"), string(node, "summary"),
                DiffEvidenceSource.valueOf(string(node, "summary_source")), strings(node, "technical_points"),
                optionalString(node, "url"), optionalStrings(node, "changed_paths"));

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

    // 바뀐 파일 경로를 보내기 전의 AI 와도 같이 돌도록 칸이 없으면 빈 목록이다
    private static List<String> optionalStrings(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return List.of();
        }
        return strings(node, field);

    }

    private static boolean bool(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (!value.isBoolean()) {
            throw AiClientException.invalidResponse();
        }
        return value.booleanValue();

    }
}
