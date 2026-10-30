package com.gitory.backend.card.domain;

import com.gitory.backend.audit.domain.AuditAction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * 작성 중인 내 카드의 제목·기간만 고치고 네 칸·근거·버전은 그대로 둔다
 * 카드 행은 저장할 때 모든 칸을 다시 쓰므로, 잠그지 않으면 그사이 끝난 확정을 이 저장이 작성 중으로 되돌린다
 */
@Service
@RequiredArgsConstructor
public class CardMetadataService {

    private final CardOwnershipService ownership;

    /**
     * null 인 칸은 그대로 두고, 둘 다 null 이면 바꿀 것이 없어 400 으로 답하게 한다
     * 지금과 같은 값은 고치지 않아 updatedAt 도 그대로 두므로, 같은 값을 다시 보내도 카드가 목록 맨 위로 올라가지 않는다
     */
    @Transactional
    public CardMetadata edit(UUID publicId, Long userId, String title, String period) {

        Card card = ownership.lockOwned(publicId, userId, AuditAction.EDIT_CARD);

        if (title == null && period == null) {
            throw new InvalidCardInputException("바꿀 제목이나 기간을 보내 주세요.");
        }

        if (title != null && !title.equals(card.getTitle())) {
            card.rename(title);
        }

        if (period != null && !Objects.equals(storedPeriod(period), card.getPeriod())) {
            card.changePeriod(period);
        }

        return new CardMetadata(card.getPublicId(), card.getTitle(), card.getPeriod(), card.getUpdatedAt());

    }

    // 빈 기간은 NULL 로 저장되므로, 기간이 없는 카드에 빈 기간을 보내면 같은 값이다
    private static String storedPeriod(String period) {

        return CardText.isBlank(period) ? null : period;

    }
}
