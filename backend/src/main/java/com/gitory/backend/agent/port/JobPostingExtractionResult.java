package com.gitory.backend.agent.port;

import java.util.List;

public record JobPostingExtractionResult(PostingExtractionOutcome outcome, String contentHash,
                                         PostingContentStatus contentStatus, List<ExtractedCriterion> criteria,
                                         PostingJobCategory category, ExtractedExperience experience,
                                         List<String> teamTechs, Integer extractionVersion) {

    public static JobPostingExtractionResult updated(String contentHash, PostingContentStatus contentStatus,
                                                     List<ExtractedCriterion> criteria, PostingJobCategory category,
                                                     ExtractedExperience experience, List<String> teamTechs,
                                                     Integer extractionVersion) {

        return new JobPostingExtractionResult(PostingExtractionOutcome.UPDATED, contentHash, contentStatus,
                List.copyOf(criteria), category, experience, List.copyOf(teamTechs), extractionVersion);

    }

    public static JobPostingExtractionResult unchanged(String contentHash, PostingContentStatus contentStatus) {

        return new JobPostingExtractionResult(PostingExtractionOutcome.UNCHANGED, contentHash, contentStatus, List.of(),
                null, null, List.of(), null);

    }

    public static JobPostingExtractionResult rejected() {

        return new JobPostingExtractionResult(PostingExtractionOutcome.REJECTED, null, null, List.of(), null, null,
                List.of(), null);

    }
}
