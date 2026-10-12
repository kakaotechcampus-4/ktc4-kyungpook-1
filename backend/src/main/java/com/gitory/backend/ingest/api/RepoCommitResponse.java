package com.gitory.backend.ingest.api;

import com.gitory.backend.ingest.domain.FoundCommit;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "커밋 찾기 결과 한 줄. 파일 경로는 응답에 아직 넣지 않아 path 는 null 입니다.")
public record RepoCommitResponse(
        String sha,
        String message,
        String path,
        Instant at,
        @Schema(description = "GitHub 커밋 주소") String url,
        @Schema(description = "이 커밋이 들어 있는 이 저장소의 후보 id. 후보에 없거나 사용자가 후보에서 뺀 커밋이면 null") String candidateId) {

    static RepoCommitResponse from(FoundCommit commit) {

        return new RepoCommitResponse(commit.sha(), commit.message(), null, commit.authoredAt(), commit.url(),
                commit.candidateId() == null ? null : commit.candidateId().toString());

    }
}
