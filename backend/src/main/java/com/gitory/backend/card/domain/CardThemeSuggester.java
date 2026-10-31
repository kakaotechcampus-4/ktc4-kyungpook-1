package com.gitory.backend.card.domain;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.CardThemePort;
import com.gitory.backend.agent.port.CardThemeRequest;
import com.gitory.backend.agent.port.CardThemeSlot;
import com.gitory.backend.agent.port.InterviewStarSlot;
import com.gitory.backend.card.infra.CardRepository;
import com.gitory.backend.common.infra.ai.AiCallContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;

/**
 * 경험 카드가 확정될 때 문항 유형이 비어 있으면, 확정이 저장된 뒤 따로 AI 에게 유형을 물어 붙인다
 * 확정 버튼·직접 저장·되돌리기·되묻기·초안 Job 이 모두 여기를 거쳐야 유형이 빠지지 않고, 요청과 카드 잠금은 AI 를 기다리지 않는다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CardThemeSuggester {

    private final CardRepository cards;
    private final CardVersionReader reader;
    private final CardThemePort themes;
    private final ApplicationEventPublisher events;
    private final PlatformTransactionManager transactionManager;

    /** 지금 버전 글로 카드 상태를 정하고({@link Card#settle}), 이번에 확정됐으면 유형을 묻게 한다 */
    public void settle(Card card, VersionSlots slots) {

        if (card.settle(slots)) {
            askAfterCommit(card);
        }

    }

    /** 사용자가 고른 적 없이 유형이 빈 경험 카드만 묻고, 확정이 롤백되면 묻지 않는다 */
    public void askAfterCommit(Card card) {

        if (card.needsThemes()) {
            events.publishEvent(new CardThemesNeeded(card.getId()));
        }

    }

    // AI 가 늦거나 실패하면 빈 유형으로 두고, 다음에 다시 확정될 때 다시 묻는다
    @Async
    @TransactionalEventListener
    public void suggest(CardThemesNeeded event) {

        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        CardThemeRequest request = readOnly.execute(status -> requestOf(event.cardId()));
        if (request == null) {
            return;
        }

        List<CardTheme> suggested;
        try {
            suggested = AiCallContext.callForCard(request.cardId(), () -> themes.themesFor(request)).stream()
                    .map(theme -> CardTheme.valueOf(theme.name()))
                    .toList();
        } catch (AgentUnavailableException failure) {
            log.warn("카드 {} 의 문항 유형을 AI 에게서 받지 못해 빈 유형으로 둔다", request.cardId(), failure);
            return;
        }

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                cards.findWithLockById(event.cardId()).ifPresent(card -> card.suggestThemes(suggested)));

    }

    // 묻기 전에 카드가 지워졌거나 사용자가 유형을 골랐으면 AI 를 부르지 않는다
    private CardThemeRequest requestOf(Long cardId) {

        Card card = cards.findById(cardId).orElse(null);
        if (card == null || !card.needsThemes()) {
            return null;
        }
        VersionSlots slots = reader.read(card.getId(), card.getCurrentVersion());

        return new CardThemeRequest(card.getId(), card.getTitle(), Arrays.stream(StarField.values())
                .map(field -> new CardThemeSlot(InterviewStarSlot.valueOf(field.name()), slots.textOf(field)))
                .toList());

    }
}
