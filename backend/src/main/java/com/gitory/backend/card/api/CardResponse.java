package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.CardDetail;
import com.gitory.backend.card.domain.CardKind;
import com.gitory.backend.card.domain.CardStatus;
import com.gitory.backend.card.domain.EvidenceType;
import com.gitory.backend.card.domain.MaskRule;
import com.gitory.backend.card.domain.SlotContent;
import com.gitory.backend.card.domain.StarField;
import com.gitory.backend.card.domain.StarFieldState;
import com.gitory.backend.card.domain.VersionSlots;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Schema(description = "카드 한 장으로, 칸 글은 현재 버전 기준이고 원문은 가리기 규칙과 상관없이 그대로 온다")
public record CardResponse(
        @Schema(format = "uuid") String id,
        CardKind kind,
        CardStatus status,
        String title,
        String period,
        CardRepoResponse repo,
        @Schema(description = "카드를 만든 후보이고, 직접 작성 카드는 null") CardCandidateResponse candidate,
        CardVersionResponse version,
        List<EvidenceResponse> evidence,
        List<LowConfidenceResponse> lowConfidenceFields,
        List<Object> droppedFields,
        List<MaskRule> maskRules,
        @Schema(description = "후보로 만든 카드의 초안 Job 이 대기 중이거나 실행 중이거나 일부만 만들어졌을 때 그 Job 이고, 그 밖에는 null") CardGenerationResponse generation,
        @Schema(description = "답한 되묻기 질문 수이고, 답하지 않은 질문은 세지 않는다") int interviewTurns,
        Instant confirmedAt,
        Instant createdAt) {

    static CardResponse from(CardDetail card) {

        return new CardResponse(
                card.publicId().toString(),
                card.kind(),
                card.status(),
                card.title(),
                card.period(),
                card.repository() == null ? null : CardRepoResponse.from(card.repository()),
                card.candidate() == null ? null : CardCandidateResponse.from(card.candidate()),
                CardVersionResponse.from(card),
                evidenceOf(card),
                lowConfidenceOf(card.slots()),
                List.of(),
                card.maskRules(),
                card.generation() == null ? null : CardGenerationResponse.from(card.generation()),
                card.interviewTurns(),
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
                evidence.addAll(Collections.nCopies(slot.commitIds().size(), EvidenceResponse.commit(field)));
            } else {
                evidence.add(EvidenceResponse.user(field, slot.evidenceType(), card.turnNos().get(field)));
            }
        }

        return evidence;

    }

    private static List<LowConfidenceResponse> lowConfidenceOf(VersionSlots slots) {

        List<LowConfidenceResponse> low = new ArrayList<>();
        for (StarField field : StarField.values()) {
            if (slots.stateOf(field) == StarFieldState.NEEDS_REVIEW) {
                low.add(new LowConfidenceResponse(field, ""));
            }
        }

        return low;

    }
}
