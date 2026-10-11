package com.gitory.backend.ingest.api;

import com.gitory.backend.ingest.domain.FoundCommit;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "커밋 찾기 결과 한 줄. 파일 경로는 저장하지 않고 후보 기능은 아직 없어 path·candidateId 는 null 입니다.")
public record RepoCommitResponse(
        String sha,
        String message,
        String path,
        Instant at,
        @Schema(description = "GitHub 커밋 주소") String url,
        String candidateId) {

    static RepoCommitResponse from(FoundCommit commit) {
        return new RepoCommitResponse(commit.sha(), commit.message(), null, commit.authoredAt(), commit.url(), null);
    }
}
