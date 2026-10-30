package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.CardArea;
import com.gitory.backend.card.domain.CardDetail;
import com.gitory.backend.card.domain.CardDraftFailure;
import com.gitory.backend.card.domain.CardKind;
import com.gitory.backend.card.domain.CardStatus;
import com.gitory.backend.card.domain.CardTech;
import com.gitory.backend.card.domain.EvidenceType;
import com.gitory.backend.card.domain.MaskRule;
import com.gitory.backend.card.domain.SlotContent;
import com.gitory.backend.card.domain.SlotNote;
import com.gitory.backend.card.domain.StarField;
import com.gitory.backend.card.domain.StarFieldState;
import com.gitory.backend.card.domain.VersionSlots;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Schema(description = "카드 한 장으로, 칸 글은 현재 버전 기준이고 원문은 가리기 규칙과 상관없이 그대로 온다")
public record CardResponse(
        @Schema(format = "uuid") String id,
        CardKind kind,
        CardStatus status,
        String title,
        @Schema(description = "AI 초안이 고른 분야를 중요한 순서로 최대 2개. 태그가 없는 카드는 빈 배열") List<CardArea> areas,
        @Schema(description = "AI 초안이 고른 기술을 중요한 순서로 최대 8개. 태그가 없는 카드는 빈 배열") List<CardTech> techs,
        String period,
        CardRepoResponse repo,
        @Schema(description = "카드가 속한 활동(레포 밖 경험 묶음)이고, 활동 밖 카드는 null. repo 와 함께 값이 있는 카드는 없다") CardActivityResponse activity,
        @Schema(description = "카드를 만든 후보이고, 직접 작성 카드는 null") CardCandidateResponse candidate,
        CardVersionResponse version,
        List<EvidenceResponse> evidence,
        List<LowConfidenceResponse> lowConfidenceFields,
        List<DroppedFieldResponse> droppedFields,
        @Schema(description = "AI 초안이 거친 단계를 순서대로 담고, AI 초안이 없거나 기록 전에 만든 카드는 빈 배열") List<DraftStepResponse> draftSteps,
        List<MaskRule> maskRules,
        @Schema(description = "후보로 만든 카드의 초안 Job 이 대기 중이거나 실행 중이거나 일부만 만들어졌을 때 그 Job 이고, 그 밖에는 null") CardGenerationResponse generation,
        @Schema(description = "AI 초안을 채우지 못한 이유로, GITHUB_UNAUTHORIZED 는 GitHub 이 토큰을 거절함(다시 연결한 뒤 다시 만듦)·AI_UNAVAILABLE 은 AI 가 잠시 응답하지 못함(다시 하면 될 수 있음)·INTERNAL_ERROR 는 그 밖의 실패다. 실패가 없으면 null 이고, POST /api/cards/{id}/draft/retry 로 다시 만든다", nullable = true) CardDraftFailure draftFailure,
        @Schema(description = "답한 되묻기 질문 수이고, 답하지 않은 질문은 세지 않는다") int interviewTurns,
        @Schema(description = "AI 가 되묻기를 그만두기로 한 이유 한 줄(60자 이내)로, 끝내지 않았거나 그 뒤 새 질문을 만들었으면 null", nullable = true) String interviewEndReason,
        Instant confirmedAt,
        Instant createdAt) {

    static CardResponse from(CardDetail card) {

        return new CardResponse(
                card.publicId().toString(),
                card.kind(),
                card.status(),
                card.title(),
                card.areas(),
                card.techs(),
                card.period(),
                card.repository() == null ? null : CardRepoResponse.from(card.repository()),
                card.activity() == null ? null : CardActivityResponse.from(card.activity()),
                card.candidate() == null ? null : CardCandidateResponse.from(card.candidate()),
                CardVersionResponse.from(card),
                evidenceOf(card),
                lowConfidenceOf(card.slots(), card.notes()),
                droppedOf(card.slots(), card.notes()),
                card.draftSteps().stream().map(DraftStepResponse::from).toList(),
                card.maskRules(),
                card.generation() == null ? null : CardGenerationResponse.from(card.generation()),
                card.draftFailure(),
                card.interviewTurns(),
                card.interviewEndReason(),
                card.confirmedAt(),
                card.createdAt());

    }

    private static List<EvidenceResponse> evidenceOf(CardDetail card) {

        List<EvidenceResponse> evidence = new ArrayList<>();
        for (StarField field : StarField.values()) {
            SlotContent slot = card.slots().slots().get(field);
            if (slot == null) {
                continue;
            }
            if (slot.evidenceType() == EvidenceType.COMMIT) {
                slot.commitIds().forEach(commitId ->
                        evidence.add(EvidenceResponse.commit(field, card.commits().get(commitId), card.repository())));
            } else {
                evidence.add(EvidenceResponse.user(field, slot.evidenceType(), card.turnNos().get(field)));
            }
        }

        return evidence;

    }

    // 확인이 필요한 이유를 AI 가 남기지 않은 칸은 빈 글로 둔다
    private static List<LowConfidenceResponse> lowConfidenceOf(VersionSlots slots, Map<StarField, SlotNote> notes) {

        List<LowConfidenceResponse> low = new ArrayList<>();
        for (StarField field : StarField.values()) {
            if (slots.stateOf(field) == StarFieldState.NEEDS_REVIEW) {
                SlotNote note = notes.get(field);
                String why = note == null || note.reviewReason() == null ? "" : note.reviewReason();
                low.add(new LowConfidenceResponse(field, why));
            }
        }

        return low;

    }

    // 사용자가 직접 비운 칸은 이유가 없어 싣지 않고, 메모가 남아 있어도 글이 있는 칸은 빈 칸이 아니라 싣지 않는다
    private static List<DroppedFieldResponse> droppedOf(VersionSlots slots, Map<StarField, SlotNote> notes) {

        List<DroppedFieldResponse> dropped = new ArrayList<>();
        for (StarField field : StarField.values()) {
            SlotNote note = notes.get(field);
            if (slots.stateOf(field) == StarFieldState.EMPTY && note != null && note.dropReason() != null) {
                dropped.add(new DroppedFieldResponse(field, note.dropReason()));
            }
        }

        return dropped;

    }
}
