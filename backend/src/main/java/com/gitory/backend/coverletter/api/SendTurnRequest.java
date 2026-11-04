package com.gitory.backend.coverletter.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "대화에서 사용자가 보낸 말")
public record SendTurnRequest(@Schema(description = "1~500자, 공백만이면 안 된다") String text) {
}
