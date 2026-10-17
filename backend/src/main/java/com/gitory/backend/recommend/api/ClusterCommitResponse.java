package com.gitory.backend.recommend.api;

import com.gitory.backend.recommend.domain.BoardCommit;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "커밋 묶음 후보에 든 커밋 한 줄. 사용자가 뺀 커밋도 included=false 로 함께 옵니다.")
public record ClusterCommitResponse(
        @Schema(description = "커밋 번호 40자") String sha,
        @Schema(description = "커밋 메시지. 없으면 빈 글자") String message,
        @Schema(description = "그 커밋에서 바뀐 줄이 가장 많은 파일 경로. 모르면 빈 글자") String path,
        @Schema(description = "작성 시각(ISO-8601). 모르면 빈 글자") String at,
        boolean included,
        @Schema(description = "GitHub 커밋 주소") String url) {

    static ClusterCommitResponse from(BoardCommit commit) {

        return new ClusterCommitResponse(commit.sha(), commit.message(), commit.path(),
                commit.at() == null ? "" : commit.at().toString(), commit.included(), commit.url());

    }
}
