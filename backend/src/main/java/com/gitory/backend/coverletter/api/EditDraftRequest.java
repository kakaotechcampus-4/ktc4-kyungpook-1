package com.gitory.backend.coverletter.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "고친 초안")
public record EditDraftRequest(@Schema(description = "고친 글 전체, 10,000자까지") String text) {
}
