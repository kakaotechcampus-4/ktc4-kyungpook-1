package com.gitory.backend.job.api;

import com.gitory.backend.job.domain.RepoUpdateRow;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record RepoUpdateResponse(
        @Schema(description = "레포 목록 응답의 id 와 같은 값", format = "uuid") String repoId,
        @Schema(description = "owner/name 모양", example = "grow22/gitory") String repoName,
        @Schema(description = "새로 만든 카드 수. 보지 않은 알림이 여럿이면 합") int cardCount,
        @Schema(description = "가장 늦게 끝난 시각") Instant finishedAt) {

    static RepoUpdateResponse from(RepoUpdateRow row) {

        return new RepoUpdateResponse(row.repoId().toString(), row.repoName(), row.cardCount(), row.finishedAt());

    }
}
