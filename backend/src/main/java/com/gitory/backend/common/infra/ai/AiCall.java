package com.gitory.backend.common.infra.ai;

import tools.jackson.databind.JsonNode;

public record AiCall(String path, Long jobId, Long cardId, Integer httpStatus, String errorCode, JsonNode meta,
                     long elapsedMs) {
}
