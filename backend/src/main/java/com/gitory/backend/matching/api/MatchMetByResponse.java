package com.gitory.backend.matching.api;

import com.gitory.backend.card.domain.CardKind;
import com.gitory.backend.matching.domain.MatchMetBy;
import io.swagger.v3.oas.annotations.media.Schema;

public record MatchMetByResponse(String cardId, String cardTitle, CardKind cardKind,
                                 @Schema(description = "카드를 만든 레포 id. 레포 화면에서 들어왔을 때 '이 레포가 채움' 을 고르는 데 쓴다. 레포 없는 카드는 null",
                                         nullable = true) String repoId,
                                 @Schema(description = "이 카드가 조건을 채웠다고 AI 가 본 이유 한 줄") String reason) {

    static MatchMetByResponse from(MatchMetBy metBy) {

        return new MatchMetByResponse(metBy.cardId().toString(), metBy.cardTitle(), metBy.cardKind(),
                metBy.repoId() == null ? null : metBy.repoId().toString(), metBy.reason());

    }
}
