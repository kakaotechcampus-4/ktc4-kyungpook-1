package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.VersionHistoryItem;
import com.gitory.backend.card.domain.VersionSource;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "작성 이력의 버전 하나")
public record VersionListItemResponse(
        int versionNo,
        VersionSource source,
        @Schema(description = "이 버전을 마지막으로 저장한 시각") Instant createdAt,
        @Schema(description = "그 버전에서 채운 칸을 적은 한 줄") String summary,
        @Schema(description = "지금 카드에 보이는 버전인지") boolean current) {

    static VersionListItemResponse from(VersionHistoryItem item) {

        return new VersionListItemResponse(item.versionNo(), item.source(), item.savedAt(), item.summary(),
                item.current());

    }
}
