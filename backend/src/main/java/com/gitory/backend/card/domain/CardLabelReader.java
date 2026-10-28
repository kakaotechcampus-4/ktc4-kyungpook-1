package com.gitory.backend.card.domain;

import com.gitory.backend.card.infra.CardRepository;
import com.gitory.backend.card.infra.ConfirmedCardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 다른 모듈이 카드를 가리킬 때 쓰는 공개 id·제목·종류를 읽으며, 지운 카드는 없는 카드로 본다 */
@Service
@RequiredArgsConstructor
public class CardLabelReader {

    private final ConfirmedCardRepository confirmedCards;
    private final CardRepository cards;

    @Transactional(readOnly = true)
    public Map<Long, CardLabel> liveLabels(Collection<Long> cardIds) {

        if (cardIds.isEmpty()) {
            return Map.of();
        }

        return cards.findAllById(confirmedCards.findLiveIds(cardIds)).stream()
                .map(CardLabelReader::labelOf)
                .collect(Collectors.toMap(CardLabel::id, Function.identity()));

    }

    /** 내 확정 카드가 아니면 빈 값이다 */
    @Transactional(readOnly = true)
    public Optional<CardLabel> confirmedOf(Long userId, UUID publicId) {

        return confirmedCards.findLiveConfirmedId(userId, publicId)
                .flatMap(cards::findById)
                .map(CardLabelReader::labelOf);

    }

    private static CardLabel labelOf(Card card) {

        return new CardLabel(card.getId(), card.getPublicId(), card.getTitle(), card.getCardType());

    }
}
