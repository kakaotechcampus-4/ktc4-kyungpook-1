package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.PolishedDraft;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "저장하지 않은 다듬은 문장 미리 보기")
public record PolishedDraftResponse(
        @Schema(description = "AI 가 표현만 다듬은 문장으로, 다듬지 못했으면 사용자 답 그대로") String sentence,
        @Schema(description = "AI 가 다듬었으면 true, 다듬지 못해 사용자 답을 그대로 돌려줬으면 false") boolean polished,
        @Schema(description = "다듬은 문장에는 있는데 사용자 답에는 없는 숫자로, 화면이 강조해 지우거나 고치게 한다") List<String> unsourcedNumbers) {

    static PolishedDraftResponse from(PolishedDraft draft) {

        return new PolishedDraftResponse(draft.sentence(), draft.polished(), draft.unsourcedNumbers());

    }
}
