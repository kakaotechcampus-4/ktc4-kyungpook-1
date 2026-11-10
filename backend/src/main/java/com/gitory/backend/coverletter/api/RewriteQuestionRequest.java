package com.gitory.backend.coverletter.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "문항을 다시 쓸 카드를 고를 때 보내는 값")
public record RewriteQuestionRequest(
        @Schema(description = "내 확정 카드의 공개 id", format = "uuid") String cardId) {
}
