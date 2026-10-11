package com.gitory.backend.card.domain;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

public record CardSummary(
        UUID id,
        CardKind kind,
        CardStatus status,
        String title,
        short versionNo,
        Map<StarField, StarFieldState> star,
        Instant updatedAt,
        String sourceLabel,
        String period,
        int evidenceCount,
        int userStatedCount) {

    static CardSummary of(Card card, VersionSlots current) {

        Map<StarField, StarFieldState> star = new EnumMap<>(StarField.class);
        for (StarField field : StarField.values()) {
            star.put(field, current.stateOf(field));
        }

        return new CardSummary(
                card.getPublicId(),
                card.getCardType(),
                card.getStatus(),
                card.getTitle(),
                card.getCurrentVersion(),
                star,
                card.getUpdatedAt(),
                card.getOrigin() == CardOrigin.MANUAL ? "MANUAL" : null,
                card.getPeriod(),
                evidenceCountOf(current),
                userStatedCountOf(current));

    }

    private static int evidenceCountOf(VersionSlots current) {

        return current.slots().values().stream()
                .mapToInt(slot -> slot.commitIds().size())
                .sum();

    }

    private static int userStatedCountOf(VersionSlots current) {

        return (int) current.slots().values().stream()
                .filter(slot -> slot.evidenceType() == EvidenceType.USER_STATED
                        || slot.evidenceType() == EvidenceType.USER_SELECTED)
                .count();

    }
}
