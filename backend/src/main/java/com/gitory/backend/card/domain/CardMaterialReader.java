package com.gitory.backend.card.domain;

import com.gitory.backend.card.infra.ActivityRepository;
import com.gitory.backend.card.infra.CardRepository;
import com.gitory.backend.card.infra.CardStatementRepository;
import com.gitory.backend.card.infra.ConfirmedCardRepository;
import com.gitory.backend.ingest.domain.RepositoryReference;
import com.gitory.backend.ingest.domain.RepositoryReferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 자소서 AI 에 보낼 확정 카드의 지금 버전 문장을 제목까지 가려 읽는다
 * 상한을 넘으면 최근에 확정한 카드부터 남기고, 가린 뒤 비는 문장은 빼며, AI 가 받는 길이에 맞춰 자른다
 */
@Service
@RequiredArgsConstructor
public class CardMaterialReader {

    private static final int TITLE_MAX_LENGTH = 256;
    private static final int STATEMENT_MAX_LENGTH = 1_000;
    private static final int SOURCE_MAX_LENGTH = 100;
    // AI 는 빈 제목을 받지 않아서, 가리기가 제목을 모두 지운 카드는 이 이름으로 보낸다
    private static final String MASKED_TITLE = "제목을 가린 카드";

    private final ConfirmedCardRepository confirmedCards;
    private final CardRepository cards;
    private final CardStatementRepository statements;
    private final RepositoryReferenceService repositories;
    private final ActivityRepository activities;

    @Transactional(readOnly = true)
    public List<CardMaterial> read(Long userId, int limit) {

        List<Long> cardIds = confirmedCards.findLiveIdsNewestFirst(userId).stream().limit(limit).toList();
        if (cardIds.isEmpty()) {
            return List.of();
        }

        Map<Long, Card> byId = cards.findAllById(cardIds).stream()
                .collect(Collectors.toMap(Card::getId, Function.identity()));
        Map<Long, List<CardStatement>> byCard = statements.findCurrentOf(cardIds).stream()
                .collect(Collectors.groupingBy(CardStatement::getCardId));
        Map<Long, RepositoryReference> repos = repositories.findAll(byId.values().stream()
                .map(Card::getUserRepositoryId)
                .filter(Objects::nonNull)
                .toList());
        Map<Long, String> activityNames = activities.findAllById(byId.values().stream()
                        .map(Card::getActivityId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(Activity::getId, Activity::getName));

        return cardIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(card -> material(card, byCard.getOrDefault(card.getId(), List.of()), repos, activityNames))
                .toList();

    }

    private static CardMaterial material(Card card, List<CardStatement> current, Map<Long, RepositoryReference> repos,
                                         Map<Long, String> activityNames) {

        String title = MaskRule.apply(card.getTitle(), card.getMaskRules());
        List<CardMaterialStatement> masked = current.stream()
                .sorted(Comparator.comparing(CardStatement::getStarSlot).thenComparing(CardStatement::getSeq))
                .map(statement -> new CardMaterialStatement(statement.getStarSlot(),
                        cut(MaskRule.apply(statement.getBody(), card.getMaskRules()), STATEMENT_MAX_LENGTH)))
                .filter(statement -> !CardText.isBlank(statement.text()))
                .toList();

        return new CardMaterial(card.getId(), card.getPublicId(),
                CardText.isBlank(title) ? MASKED_TITLE : cut(title, TITLE_MAX_LENGTH), card.getCardType(),
                card.getPeriod(), sourceName(card, repos, activityNames), masked);

    }

    private static String sourceName(Card card, Map<Long, RepositoryReference> repos, Map<Long, String> activityNames) {

        RepositoryReference repo = card.getUserRepositoryId() == null ? null : repos.get(card.getUserRepositoryId());
        if (repo != null) {
            return cut(repo.ownerLogin() + "/" + repo.name(), SOURCE_MAX_LENGTH);
        }

        String activity = card.getActivityId() == null ? null : activityNames.get(card.getActivityId());
        return activity == null ? null : cut(activity, SOURCE_MAX_LENGTH);

    }

    private static String cut(String text, int max) {

        return text == null || text.length() <= max ? text : text.substring(0, max);

    }
}
