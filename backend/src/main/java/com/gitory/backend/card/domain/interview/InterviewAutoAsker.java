package com.gitory.backend.card.domain.interview;

import com.gitory.backend.card.domain.Card;
import com.gitory.backend.card.infra.CardRepository;
import com.gitory.backend.card.infra.InterviewTurnRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * 초안이 카드에 저장된 뒤 사용자가 질문 받기를 누르기 전에 첫 질문을 서버가 만들어 둔다
 * 초안 Job 과 따로 커밋 뒤에 돌고, 어떤 이유로든 실패해도 초안 결과에는 영향이 없다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewAutoAsker {

    private final CardRepository cards;
    private final InterviewTurnRepository turns;
    private final InterviewLimits limits;
    private final InterviewAskService askService;
    private final PlatformTransactionManager transactionManager;

    @Async
    @TransactionalEventListener
    public void ask(InterviewQuestionNeeded event) {

        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        Target target = readOnly.execute(status -> targetOf(event.cardId()));
        if (target == null) {
            return;
        }

        try {
            askService.askFirst(target.publicId(), target.userId());
        } catch (RuntimeException failure) {
            // 예외 메시지에 AI 가 돌려준 글이 실릴 수 있어 종류만 남긴다
            log.warn("카드 {} 의 첫 질문을 만들지 못했다: {}", event.cardId(), failure.getClass().getSimpleName());
        }

    }

    // 커밋 뒤 사이에 사용자가 확정했거나 이미 질문이 있거나 상한이면 AI 를 부르지 않는다
    private Target targetOf(Long cardId) {

        Card card = cards.findById(cardId).orElse(null);
        if (card == null || card.isConfirmed()) {
            return null;
        }
        List<InterviewTurn> asked = turns.findByCardIdOrderBySeqAsc(cardId);
        if (asked.stream().anyMatch(turn -> !turn.isClosed()) || asked.size() >= limits.maxTurns()) {
            return null;
        }

        return new Target(card.getPublicId(), card.getUserId());

    }

    private record Target(UUID publicId, Long userId) {
    }
}
