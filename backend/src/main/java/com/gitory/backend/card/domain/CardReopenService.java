package com.gitory.backend.card.domain;

import com.gitory.backend.audit.domain.AuditAction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 확정한 카드를 다시 작성 중으로 돌리고, 새 버전은 만들지 않아 확정본이 그대로 현재 버전으로 남는다
 * 확정본은 덮어쓸 수 없는 버전이라 다시 연 뒤 첫 임시 저장이 새 버전을 만들어 확정한 내용이 이력에 남는다
 */
@Service
@RequiredArgsConstructor
public class CardReopenService {

    private final CardOwnershipService ownership;
    private final CardDetailService details;
    private final ConfirmedCardChanges changes;

    /** 이미 작성 중인 카드면 아무것도 바꾸지 않고 지금 카드를 돌려준다 */
    @Transactional
    public CardDetail reopen(UUID publicId, Long userId) {

        Card card = ownership.lockOwned(publicId, userId, AuditAction.EDIT_CARD);
        if (card.isConfirmed()) {
            card.reopen();
            changes.announce(card);
        }

        return details.load(publicId, userId);

    }
}
