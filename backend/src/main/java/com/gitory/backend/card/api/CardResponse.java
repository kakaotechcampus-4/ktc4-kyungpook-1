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
        @Schema(description = "카드를 만든 후보이고, 직접 작성 카드는 null") Object candidate,
        CardVersionResponse version,
        List<EvidenceResponse> evidence,
        List<LowConfidenceResponse> lowConfidenceFields,
        List<Object> droppedFields,
        List<MaskRule> maskRules,
        @Schema(description = "초안을 만드는 중이면 그 Job 이고, 아니면 null") Object generation,
        int interviewTurns,
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
                null,
                CardVersionResponse.from(card),
                evidenceOf(card.slots()),
                lowConfidenceOf(card.slots()),
                List.of(),
                card.maskRules(),
                null,
                0,
                card.confirmedAt(),
                card.createdAt());

    }

    private static List<EvidenceResponse> evidenceOf(VersionSlots slots) {

        List<EvidenceResponse> evidence = new ArrayList<>();
        for (StarField field : StarField.values()) {
            SlotContent slot = slots.slots().get(field);
            if (slot == null) {
                continue;
            }
            if (slot.evidenceType() == EvidenceType.COMMIT) {
                evidence.addAll(Collections.nCopies(slot.commitIds().size(), EvidenceResponse.commit(field)));
            } else {
                evidence.add(EvidenceResponse.user(field, slot.evidenceType()));
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
