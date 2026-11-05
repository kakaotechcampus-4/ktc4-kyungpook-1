package com.gitory.backend.assistant.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "사용자가 홈에서 적은 한 문장이나 물음에 대한 답")
public record AssistantMessageRequest(@Schema(description = "앞뒤 공백을 뺀 1~500자") String text) {
}
