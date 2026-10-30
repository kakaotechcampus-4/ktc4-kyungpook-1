package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.CardArea;
import com.gitory.backend.card.domain.CardKind;
import com.gitory.backend.card.domain.CardStatus;
import com.gitory.backend.card.domain.CardSummary;
import com.gitory.backend.card.domain.CardTech;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "카드 목록의 한 줄. 칸 상태(star)는 현재 버전 글로 서버가 판정합니다.")
public record CardSummaryResponse(
        @Schema(format = "uuid") String id,
        CardKind kind,
        CardStatus status,
        String title,
        @Schema(description = "AI 초안이 고른 분야를 중요한 순서로 최대 2개. 태그가 없는 카드는 빈 배열") List<CardArea> areas,
        @Schema(description = "AI 초안이 고른 기술을 중요한 순서로 최대 8개. 태그가 없는 카드는 빈 배열") List<CardTech> techs,
        int versionNo,
        StarStatesResponse star,
        @Schema(description = "카드 내용을 마지막으로 바꾼 시각") Instant updatedAt,
        @Schema(description = "직접 작성 카드는 MANUAL, 되묻기에 답한 질문이 있는 직접 작성 카드는 INTERVIEW, 후보로 만든 카드는 그 후보의 ref(PR 번호나 날짜 같은 참조 글자)") String sourceLabel,
        @Schema(description = "후보로 만든 카드의 후보 종류. 직접 작성 카드는 null") String sourceType,
        String period,
        @Schema(description = "현재 버전 문장에 붙은 근거 커밋 수") int evidenceCount,
        @Schema(description = "현재 버전에서 사용자가 쓰거나 고른 문장 수") int userStatedCount,
        @Schema(description = "카드를 만든 저장소로 카드 상세의 repo 와 같은 값이고, 저장소 없이 직접 쓴 카드는 null") CardRepoResponse repo,
        @Schema(description = "카드가 속한 활동으로 카드 상세의 activity 와 같은 값이고, 활동 밖 카드는 null") CardActivityResponse activity) {

    static CardSummaryResponse from(CardSummary card) {

        return new CardSummaryResponse(
                card.id().toString(),
                card.kind(),
                card.status(),
                card.title(),
                card.areas(),
                card.techs(),
                card.versionNo(),
                StarStatesResponse.from(card.star()),
                card.updatedAt(),
                card.sourceLabel(),
                card.sourceType(),
                card.period(),
                card.evidenceCount(),
                card.userStatedCount(),
                card.repository() == null ? null : CardRepoResponse.from(card.repository()),
                card.activity() == null ? null : CardActivityResponse.from(card.activity()));

    }
}
