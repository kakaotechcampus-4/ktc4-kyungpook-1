package com.gitory.backend.card.domain;

import java.util.UUID;

public record ConfirmedStatement(long statementId, UUID cardPublicId, String cardTitle, CardKind cardKind,
                                 StarField field, String body) {
}
