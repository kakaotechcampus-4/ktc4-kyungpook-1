package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.CardKind;
import com.gitory.backend.card.domain.CardStatus;
import com.gitory.backend.card.domain.CardSummary;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "카드 목록의 한 줄. 칸 상태(star)는 현재 버전 글로 서버가 판정합니다.")
public record CardSummaryResponse(
        @Schema(format = "uuid") String id,
        CardKind kind,
        CardStatus status,
        String title,
        int versionNo,
        StarStatesResponse star,
        @Schema(description = "카드 내용을 마지막으로 바꾼 시각") Instant updatedAt,
        @Schema(description = "직접 작성 카드는 MANUAL") String sourceLabel,
        @Schema(description = "후보로 만든 카드의 후보 종류. 직접 작성 카드는 null") String sourceType,
        String period,
        @Schema(description = "현재 버전 문장에 붙은 근거 커밋 수") int evidenceCount,
        @Schema(description = "현재 버전에서 사용자가 쓰거나 고른 문장 수") int userStatedCount) {

    static CardSummaryResponse from(CardSummary card) {

        return new CardSummaryResponse(
                card.id().toString(),
                card.kind(),
                card.status(),
                card.title(),
                card.versionNo(),
                StarStatesResponse.from(card.star()),
                card.updatedAt(),
                card.sourceLabel(),
                null,
                card.period(),
                card.evidenceCount(),
                card.userStatedCount());

    }
}
