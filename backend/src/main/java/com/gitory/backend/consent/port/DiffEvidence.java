package com.gitory.backend.consent.port;

import java.util.List;

public record DiffEvidence(String sha, String message, String authorLogin, String churn, String summary,
                           DiffEvidenceSource summarySource, List<String> technicalPoints, String url) {
}
