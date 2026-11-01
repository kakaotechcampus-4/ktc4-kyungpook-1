package com.gitory.backend.card.domain;

import com.gitory.backend.ingest.domain.RepositoryReference;
import com.gitory.backend.recommend.domain.CandidateReference;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CardSummary(
        UUID id,
        CardKind kind,
        CardStatus status,
        String title,
        List<CardArea> areas,
        List<CardTech> techs,
        List<CardTheme> themes,
        short versionNo,
        Map<StarField, StarFieldState> star,
        Instant updatedAt,
        String sourceLabel,
        String sourceType,
        String period,
        int evidenceCount,
        int userStatedCount,
        RepositoryReference repository,
        ActivityReference activity) {

    static CardSummary of(Card card, VersionSlots current, CandidateReference candidate, RepositoryReference repository,
                          ActivityReference activity, boolean interviewed) {

        Map<StarField, StarFieldState> star = new EnumMap<>(StarField.class);
        for (StarField field : StarField.values()) {
            star.put(field, current.stateOf(field));
        }

        return new CardSummary(
                card.getPublicId(),
                card.getCardType(),
                card.getStatus(),
                card.getTitle(),
                card.areaTags(),
                card.techTags(),
                card.themeTags(),
                card.getCurrentVersion(),
                star,
                card.getUpdatedAt(),
                sourceLabelOf(card, candidate, interviewed),
                candidate == null ? null : candidate.type().name(),
                card.getPeriod(),
                evidenceCountOf(current),
                userStatedCountOf(current),
                repository,
                activity);

    }

    private static String sourceLabelOf(Card card, CandidateReference candidate, boolean interviewed) {

        if (card.getOrigin() == CardOrigin.MANUAL) {
            return interviewed ? "INTERVIEW" : "MANUAL";
        }

        if (candidate != null) {
            return candidate.ref();
        }

        return null;

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
