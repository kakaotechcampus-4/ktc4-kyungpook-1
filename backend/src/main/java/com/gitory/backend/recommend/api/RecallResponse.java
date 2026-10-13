package com.gitory.backend.recommend.api;

import com.gitory.backend.recommend.domain.Recall;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "회상 도우미 폴더 목록")
public record RecallResponse(
        @Schema(format = "uuid", description = "저장소 id") String repoId,
        @Schema(description = "화면 위에 보여 줄 안내 글") String note,
        @Schema(description = "만진 폴더 최대 5개. 내 커밋이 많은 순") List<RecallDirResponse> dirs) {

    static RecallResponse from(Recall recall) {

        return new RecallResponse(recall.repositoryId().toString(), recall.note(),
                recall.dirs().stream().map(RecallDirResponse::from).toList());

    }
}
