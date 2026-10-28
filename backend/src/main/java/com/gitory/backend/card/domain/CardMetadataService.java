package com.gitory.backend.card.domain;

import com.gitory.backend.audit.domain.AuditAction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 내 카드의 제목·기간·문항 유형만 고치고 네 칸·근거·버전은 그대로 둔다
 * 카드 행은 저장할 때 모든 칸을 다시 쓰므로, 잠그지 않으면 그사이 끝난 확정을 이 저장이 작성 중으로 되돌린다
 */
@Service
@RequiredArgsConstructor
public class CardMetadataService {

    private static final int MAX_THEMES = 2;

    private final CardOwnershipService ownership;

    /**
     * null 인 칸은 그대로 두고, 셋 다 null 이면 바꿀 것이 없어 400 으로 답하게 한다
     * 지금과 같은 값은 고치지 않아 updatedAt 도 그대로 두므로, 같은 값을 다시 보내도 카드가 목록 맨 위로 올라가지 않는다
     * 문항 유형만 보낸 요청은 확정 카드도 바꾸고, 그 밖의 요청은 지금처럼 확정 카드면 400 검사보다 먼저 409 로 답한다
     */
    @Transactional
    public CardMetadata edit(UUID publicId, Long userId, String title, String period, List<String> themes) {

        Card card = ownership.lockOwned(publicId, userId, AuditAction.EDIT_CARD);
        if (themes == null || title != null || period != null) {
            card.requireDraft();
        }

        if (title == null && period == null && themes == null) {
            throw new InvalidCardInputException("바꿀 제목·기간·문항 유형을 보내 주세요.");
        }

        if (title != null && !title.equals(card.getTitle())) {
            card.rename(title);
        }

        if (period != null && !Objects.equals(storedPeriod(period), card.getPeriod())) {
            card.changePeriod(period);
        }

        if (themes != null) {
            card.chooseThemes(checkedThemes(themes));
        }

        return new CardMetadata(card.getPublicId(), card.getTitle(), card.getPeriod(), card.themeTags(),
                card.getUpdatedAt());

    }

    private static List<CardTheme> checkedThemes(List<String> themes) {

        if (themes.size() > MAX_THEMES || new HashSet<>(themes).size() != themes.size()) {
            throw new InvalidCardInputException("문항 유형은 겹치지 않게 두 개까지 고를 수 있어요.");
        }
        try {
            return themes.stream().map(CardTheme::valueOf).toList();
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new InvalidCardInputException("모르는 문항 유형이에요.");
        }

    }

    // 빈 기간은 NULL 로 저장되므로, 기간이 없는 카드에 빈 기간을 보내면 같은 값이다
    private static String storedPeriod(String period) {

        return CardText.isBlank(period) ? null : period;

    }
}
