package com.gitory.backend.card.domain;

import com.gitory.backend.ingest.domain.CommitSummary;
import com.gitory.backend.ingest.domain.RepositoryReference;
import com.gitory.backend.recommend.domain.CandidateReference;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CardDetail(
        UUID publicId,
        CardKind kind,
        CardStatus status,
        String title,
        List<CardArea> areas,
        List<CardTech> techs,
        String period,
        RepositoryReference repository,
        ActivityReference activity,
        VersionSource source,
        Instant versionSavedAt,
        VersionSlots slots,
        List<MaskRule> maskRules,
        Instant confirmedAt,
        Instant createdAt,
        CandidateReference candidate,
        CardGeneration generation,
        CardDraftFailure draftFailure,
        int interviewTurns,
        String interviewEndReason,
        Map<StarField, Integer> turnNos,
        Map<StarField, SlotNote> notes,
        Map<Long, CommitSummary> commits,
        List<DraftStep> draftSteps) {
}
