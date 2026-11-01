package com.gitory.backend.card.domain;

import com.gitory.backend.card.infra.CardRepository;
import com.gitory.backend.card.infra.CardStatementRepository;
import com.gitory.backend.card.infra.ConfirmedCardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 매칭 AI 판단에 보낼 확정 카드의 지금 버전 글을 사용자가 정한 가리기를 적용해 칸마다 한 덩어리로 읽는다
 * 매칭은 이 글로 판단을 저장해 두므로, 가리기를 바꾸면 보내는 글이 달라져 판단을 다시 묻게 된다
 */
@Service
@RequiredArgsConstructor
public class MatchingCardReader {

    // AI 는 빈 제목을 받지 않아서, 가리기가 제목을 모두 지운 카드는 이 이름으로 보낸다
    private static final String MASKED_TITLE = "제목을 가린 카드";

    private final ConfirmedCardRepository confirmedCards;
    private final CardRepository cards;
    private final CardStatementRepository statements;

    @Transactional(readOnly = true)
    public List<MatchingCard> confirmedOf(Long userId, Long userRepositoryId) {

        List<Long> ids = confirmedCards.findLiveConfirmedIdsOfRepository(userId, userRepositoryId);
        if (ids.isEmpty()) {
            return List.of();
        }

        Map<Long, List<CardStatement>> byCard = statements.findCurrentOf(ids).stream()
                .collect(Collectors.groupingBy(CardStatement::getCardId));
        return cards.findAllById(ids).stream()
                .sorted(Comparator.comparing(Card::getId))
                .map(card -> card(card, byCard.getOrDefault(card.getId(), List.of())))
                .toList();

    }

    private static MatchingCard card(Card card, List<CardStatement> current) {

        Map<StarField, String> slots = current.stream()
                .sorted(Comparator.comparing(CardStatement::getSeq))
                .map(statement -> Map.entry(statement.getStarSlot(),
                        MaskRule.apply(statement.getBody(), card.getMaskRules())))
                .filter(entry -> !CardText.isBlank(entry.getValue()))
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.joining(" "))));
        String title = MaskRule.apply(card.getTitle(), card.getMaskRules());
        return new MatchingCard(card.getId(), card.getUserRepositoryId(), CardText.isBlank(title) ? MASKED_TITLE : title,
                slots.get(StarField.S), slots.get(StarField.T), slots.get(StarField.A), slots.get(StarField.R),
                card.techTags());

    }
}
