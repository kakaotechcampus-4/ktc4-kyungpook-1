package com.gitory.backend.matching.domain;

import com.gitory.backend.agent.port.CriterionMatch;
import com.gitory.backend.card.domain.ConfirmedStatementSet;

import java.util.List;

public record MatchEvidence(ConfirmedStatementSet confirmed, List<CriterionMatch> matches) {
}
