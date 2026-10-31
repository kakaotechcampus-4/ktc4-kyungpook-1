package com.gitory.backend.card.domain;

import com.gitory.backend.audit.domain.AuditAction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 카드의 가리기 규칙을 받은 순서 그대로 저장하고, 규칙이 바뀌면 네 칸을 그대로 복사한 MASK 버전을 더한다
 * 원문은 바꾸지 않고, 가린 글은 화면이 저장된 규칙으로 만든다
 */
@Service
@RequiredArgsConstructor
public class CardMaskService {

    private final CardOwnershipService ownership;
    private final CardVersionWriter writer;
    private final CardDetailService details;
    private final ConfirmedCardChanges changes;

    /** 화면은 규칙이 그대로여도 적용을 누를 때마다 보내므로, 저장된 규칙과 순서까지 같으면 버전을 만들지 않는다 */
    @Transactional
    public CardDetail mask(UUID publicId, Long userId, List<MaskRule> rules) {

        Card card = ownership.lockOwned(publicId, userId, AuditAction.EDIT_CARD);

        if (card.replaceMaskRules(rules)) {
            writer.copyToNext(card, card.getCurrentVersion(), VersionSource.MASK);
            if (card.isConfirmed()) {
                changes.announce(card);
            }
        }

        return details.load(publicId, userId);

    }
}
