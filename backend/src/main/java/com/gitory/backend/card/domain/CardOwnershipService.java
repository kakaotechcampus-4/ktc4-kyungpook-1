package com.gitory.backend.card.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.card.infra.CardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 내 카드만 꺼내 주고, 남의 카드면 부른 요청의 action 으로 거절 기록을 남긴 뒤 없는 카드와 똑같이 404 로 답하게 한다 */
@Service
@RequiredArgsConstructor
public class CardOwnershipService {

    private final CardRepository cards;
    private final AuditLog auditLog;

    @Transactional(readOnly = true)
    public Card findOwned(UUID publicId, Long userId, AuditAction action) {

        return cards.findByPublicIdAndUserId(publicId, userId)
                .orElseThrow(() -> notFound(publicId, userId, action));

    }

    /** 카드를 바꾸는 요청은 판단 전에 행을 잠그고, 트랜잭션 밖에서 부르면 잠금이 바로 풀리므로 막는다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Card lockOwned(UUID publicId, Long userId, AuditAction action) {

        return cards.findWithLockByPublicIdAndUserId(publicId, userId)
                .orElseThrow(() -> notFound(publicId, userId, action));

    }

    private CardNotFoundException notFound(UUID publicId, Long userId, AuditAction action) {

        cards.findByPublicId(publicId)
                .ifPresent(others -> auditLog.denied(userId, action, others.getId()));

        return new CardNotFoundException();

    }
}
