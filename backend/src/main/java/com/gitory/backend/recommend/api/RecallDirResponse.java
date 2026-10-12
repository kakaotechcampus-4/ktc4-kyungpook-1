package com.gitory.backend.recommend.api;

import com.gitory.backend.recommend.domain.RecallDir;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "내 커밋이 파일을 바꾼 폴더 한 곳과 그 폴더에서 던질 질문")
public record RecallDirResponse(
        @Schema(description = "폴더 끝 두 단계. 예: /main/java/, 맨 위 파일은 /") String path,
        @Schema(description = "그 폴더에서 바뀐 서로 다른 파일 경로 수") int filesChanged,
        @Schema(description = "그 폴더의 파일을 하나라도 바꾼 내 커밋 수") int myCommits,
        @Schema(description = "파일 이름 최대 5개. 그 파일을 바꾼 내 커밋이 많은 순") List<String> files,
        String question,
        List<String> options) {

    static RecallDirResponse from(RecallDir dir) {

        return new RecallDirResponse(dir.path(), dir.filesChanged(), dir.myCommits(), dir.files(), dir.question(),
                dir.options());

    }
}
