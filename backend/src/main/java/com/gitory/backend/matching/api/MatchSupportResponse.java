package com.gitory.backend.matching.api;

import com.gitory.backend.card.domain.CardKind;
import com.gitory.backend.card.domain.StarField;
import com.gitory.backend.matching.domain.MatchSupport;

public record MatchSupportResponse(String cardId, String cardTitle, CardKind cardKind, StarField field,
                                   String sentence) {

    public static MatchSupportResponse from(MatchSupport support) {

        return new MatchSupportResponse(support.cardId().toString(), support.cardTitle(), support.cardKind(),
                support.field(), support.sentence());

    }
}
