package com.gitory.backend.ingest.api;

import com.gitory.backend.ingest.domain.ExclusionReason;
import com.gitory.backend.ingest.domain.RepositoryMaterials;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

public record RepoMaterialsResponse(List<Commit> commits, List<PullRequest> pullRequests) {

    @Schema(name = "RepoMaterialCommit")
    public record Commit(
            @Schema(description = "커밋 번호 앞 7자") String sha,
            @Schema(description = "커밋 메시지 첫 줄") String title,
            Instant authoredAt,
            @Schema(description = "바뀐 파일 수") int files,
            @Schema(description = "분석이 이 커밋을 묶은 PR 번호. 묶을 PR 이 없거나 뺀 커밋이면 null") Integer pullRequestNumber,
            @Schema(description = "분석에서 뺀 이유. 분석에 쓴 커밋이면 null") ExclusionReason excluded) {
    }

    @Schema(name = "RepoMaterialPullRequest")
    public record PullRequest(
            int number,
            String title,
            @Schema(description = "머지되지 않았으면 null") Instant mergedAt,
            @Schema(description = "이 PR 에 묶인 내 커밋 수") int commitCount) {
    }

    static RepoMaterialsResponse from(RepositoryMaterials materials) {

        return new RepoMaterialsResponse(
                materials.commits().stream()
                        .map(commit -> new Commit(commit.sha(), commit.title(), commit.authoredAt(), commit.files(),
                                commit.pullRequestNumber(), commit.excluded()))
                        .toList(),
                materials.pullRequests().stream()
                        .map(pullRequest -> new PullRequest(pullRequest.number(), pullRequest.title(),
                                pullRequest.mergedAt(), pullRequest.commitCount()))
                        .toList());

    }
}
