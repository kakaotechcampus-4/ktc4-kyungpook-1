package com.gitory.backend.card.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.card.infra.CardRepository;
import com.gitory.backend.recommend.domain.CandidateUseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 내 카드를 버전·문장·근거·되묻기까지 DB 에서 통째로 지우고, 후보로 만든 카드면 그 후보를 다시 고를 수 있게 되돌린다
 * 초안 Job 이 지운 카드에 글을 쓰려다 실패하지 않도록, 초안을 만드는 중인 카드는 지우지 않는다
 */
@Service
@RequiredArgsConstructor
public class CardDeleteService {

    private final CardOwnershipService ownership;
    private final CardGenerationGuard generation;
    private final CandidateUseService candidates;
    private final CardRepository cards;
    private final AuditLog auditLog;

    @Transactional
    public void delete(UUID publicId, Long userId) {

        Card card = ownership.lockOwned(publicId, userId, AuditAction.DELETE_CARD);
        generation.requireNotGenerating(card);

        // 카드 행을 잠근 뒤 후보 행을 잠가, 확정·되묻기처럼 카드부터 잠그는 요청과 같은 순서를 지킨다
        if (card.getCandidateId() != null) {
            candidates.release(card.getCandidateId());
        }
        cards.delete(card);
        auditLog.ok(userId, AuditAction.DELETE_CARD, card.getId());

    }
}
