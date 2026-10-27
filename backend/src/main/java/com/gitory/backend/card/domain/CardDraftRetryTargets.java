package com.gitory.backend.card.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.card.infra.CardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 초안을 채우지 못한 카드를 새 초안 Job 으로 다시 채울 수 있는지 확인하고 새 Job 에 다시 이어 준다
 * Job 을 만드는 쪽(job 모듈)이 확인·Job 저장·다시 잇기를 한 트랜잭션으로 묶도록, 부르는 트랜잭션 안에서만 쓴다
 */
@Service
@RequiredArgsConstructor
public class CardDraftRetryTargets {

    private final CardOwnershipService ownership;
    private final CardRepository cards;

    @Transactional(propagation = Propagation.MANDATORY)
    public DraftRetryTarget lockRetryable(UUID publicId, Long userId) {

        Card card = ownership.lockOwned(publicId, userId, AuditAction.EDIT_CARD);
        card.requireDraftRetryable();

        return new DraftRetryTarget(card.getId(), card.getUserRepositoryId());

    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void restart(Long cardId, UUID jobId) {

        cards.findWithLockById(cardId).orElseThrow().restartGeneration(jobId);

    }
}
