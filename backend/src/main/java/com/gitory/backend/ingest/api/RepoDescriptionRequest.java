package com.gitory.backend.ingest.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "고칠 폴더 설명")
public record RepoDescriptionRequest(
        @Schema(description = "앞뒤 공백을 뗀 글이 120자까지이며, 비우거나 공백뿐이거나 null 이면 사용자가 쓴 설명을 지우고 GitHub 설명이나 README 문장을 보여 줌")
        String description) {
}
