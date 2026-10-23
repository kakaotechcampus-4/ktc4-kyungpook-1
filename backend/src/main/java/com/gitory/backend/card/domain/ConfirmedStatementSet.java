package com.gitory.backend.card.domain;

import java.util.List;

public record ConfirmedStatementSet(boolean hasConfirmedCard, List<ConfirmedStatement> statements) {
}
