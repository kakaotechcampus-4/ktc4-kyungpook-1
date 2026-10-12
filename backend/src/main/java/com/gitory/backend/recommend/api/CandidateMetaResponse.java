package com.gitory.backend.recommend.api;

import com.gitory.backend.recommend.domain.BoardCandidate;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "후보 카드에 보이는 숫자. commits 는 지금 포함된 커밋 수이고 나머지는 묶을 때 저장한 값입니다.")
public record CandidateMetaResponse(
        int commits,
        int files,
        int reviewComments,
        @Schema(description = "첫 커밋부터 마지막 커밋까지 일수. 모르면 null") Integer days,
        @Schema(description = "코드 파일이 바뀐 줄의 비율(0~1). 모르면 null") Double codeRatio) {

    static CandidateMetaResponse from(BoardCandidate candidate) {

        return new CandidateMetaResponse(candidate.commitCount(), candidate.fileCount(),
                candidate.reviewCommentCount(), candidate.days(),
                candidate.codeRatio() == null ? null : candidate.codeRatio().doubleValue());

    }
}
