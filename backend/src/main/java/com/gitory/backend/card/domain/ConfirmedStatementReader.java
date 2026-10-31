package com.gitory.backend.card.domain;

import com.gitory.backend.card.infra.CardRepository;
import com.gitory.backend.card.infra.CardStatementRepository;
import com.gitory.backend.card.infra.ConfirmedCardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 매칭이 근거로 쓰는 확정 카드의 지금 확정 버전 행동(A)·결과(R) 문장을 가리기까지 적용해 읽는다
 * 상한을 넘으면 최근에 확정한 카드부터 남기고, 가린 뒤 비는 문장은 뺀다
 */
@Service
@RequiredArgsConstructor
public class ConfirmedStatementReader {

    private final ConfirmedCardRepository confirmedCards;
    private final CardRepository cards;
    private final CardStatementRepository statements;

    @Transactional(readOnly = true)
    public ConfirmedStatementSet read(Long userId, int limit) {

        List<Long> cardIds = confirmedCards.findLiveIdsNewestFirst(userId);
        if (cardIds.isEmpty()) {
            return new ConfirmedStatementSet(false, List.of());
        }

        Map<Long, Card> byId = new HashMap<>();
        cards.findAllById(cardIds).forEach(card -> byId.put(card.getId(), card));
        Map<Long, Integer> order = new HashMap<>();
        for (int i = 0; i < cardIds.size(); i++) {
            order.put(cardIds.get(i), i);
        }

        List<ConfirmedStatement> readable = statements.findCurrentOf(cardIds).stream()
                .filter(statement -> statement.getStarSlot() == StarField.A || statement.getStarSlot() == StarField.R)
                .sorted(Comparator.comparing((CardStatement statement) -> order.get(statement.getCardId()))
                        .thenComparing(CardStatement::getStarSlot).thenComparing(CardStatement::getSeq))
                .map(statement -> masked(statement, byId.get(statement.getCardId())))
                .filter(statement -> !CardText.isBlank(statement.body()))
                .limit(limit)
                .toList();

        return new ConfirmedStatementSet(true, readable);

    }

    private static ConfirmedStatement masked(CardStatement statement, Card card) {

        return new ConfirmedStatement(statement.getId(), card.getPublicId(), card.getTitle(), card.getCardType(),
                statement.getStarSlot(), MaskRule.apply(statement.getBody(), card.getMaskRules()));

    }
}
