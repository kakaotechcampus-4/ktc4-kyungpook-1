package com.gitory.backend.card.domain;

import com.gitory.backend.card.infra.CardRepository;
import com.gitory.backend.card.infra.ConfirmedCardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 자소서가 새 카드를 문항에 맞춰 볼 때 쓰는 읽기로, 지웠거나 확정이 풀렸거나 유형이 없는 카드는 비어 있다
 */
@Service
@RequiredArgsConstructor
public class ConfirmedCardThemeReader {

    private final CardRepository cards;
    private final ConfirmedCardRepository confirmedCards;

    @Transactional(readOnly = true)
    public Optional<ConfirmedCardThemes> read(Long cardId) {

        if (confirmedCards.findLiveIds(List.of(cardId)).isEmpty()) {
            return Optional.empty();
        }

        return cards.findById(cardId)
                .filter(card -> card.isConfirmed() && !card.themeTags().isEmpty())
                .map(card -> new ConfirmedCardThemes(card.getId(), card.getPublicId(), card.getUserId(),
                        card.getTitle(), card.themeTags()));

    }
}
