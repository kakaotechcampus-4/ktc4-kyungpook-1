package com.gitory.backend.card.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.card.infra.CardStatementRepository;
import com.gitory.backend.card.infra.CardVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 카드의 작성 이력을 보여 주고, 예전 버전은 고치지 않고 복사한 RESTORE 새 버전으로 되살린다
 * 임시 저장이 문장을 지우고 다시 넣는 사이에도 버전과 문장을 한 시점으로 읽도록 이력은 REPEATABLE READ 로 읽는다
 */
@Service
@RequiredArgsConstructor
public class CardHistoryService {

    private final CardOwnershipService ownership;
    private final CardVersionRepository versions;
    private final CardStatementRepository statements;
    private final CardVersionWriter writer;
    private final CardDetailService details;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<VersionHistoryItem> history(UUID publicId, Long userId) {

        Card card = ownership.findOwned(publicId, userId, AuditAction.VIEW_CARD);
        List<CardVersion> found = versions.findByCardIdOrderByVersionNoDesc(card.getId());
        Map<Short, Set<StarField>> filled = filledSlotsByVersion(card.getId());
        CardVersion aiOriginal = found.stream()
                .filter(version -> version.getSource() == VersionSource.AI_DRAFT)
                .min(Comparator.comparingInt(CardVersion::getVersionNo))
                .orElse(null);

        List<VersionHistoryItem> items = new ArrayList<>();
        for (CardVersion version : found) {
            String summary = summaryOf(filled.getOrDefault(version.getVersionNo(), Set.of()),
                    version == aiOriginal, version.isConfirmed());
            items.add(new VersionHistoryItem(version.getVersionNo(), version.getSource(), version.getUpdatedAt(),
                    summary, version.getVersionNo() == card.getCurrentVersion()));
        }

        return items;

    }

    /** 예전 버전을 고르면 확정된 카드도 다시 열면서 되살리고, 없는 번호면 롤백돼 다시 연 것까지 되돌아간 채 404 로 답하게 한다 */
    @Transactional
    public CardDetail restore(UUID publicId, Long userId, int versionNo) {

        Card card = ownership.lockOwned(publicId, userId, AuditAction.EDIT_CARD);

        // 버전 번호는 SMALLINT 라, 그 범위 밖 번호를 short 로 바꾸면 다른 버전을 가리키게 된다
        if (versionNo < 1 || versionNo > Short.MAX_VALUE) {
            throw new CardVersionNotFoundException();
        }

        // 지금 버전을 되살려도 같은 내용의 버전만 하나 늘어나므로, 새 버전을 만들지 않고 확정한 카드도 다시 열지 않는다
        if (versionNo == card.getCurrentVersion()) {
            return details.load(publicId, userId);
        }

        card.reopen();
        writer.copyToNext(card, (short) versionNo, VersionSource.RESTORE);

        return details.load(publicId, userId);

    }

    private Map<Short, Set<StarField>> filledSlotsByVersion(Long cardId) {

        return statements.findByCardId(cardId).stream()
                .collect(Collectors.groupingBy(CardStatement::getVersionNo,
                        Collectors.mapping(CardStatement::getStarSlot, Collectors.toSet())));

    }

    /**
     * 확정하며 만든 버전은 출처가 앞 버전과 같아 이력에서 두 줄이 똑같아 보이므로 '확정' 을 붙인다
     * '원본(잠김)' 은 AI 초안을 그대로 확정한 버전이 아니라 카드의 첫 AI 초안에만 붙인다
     */
    private static String summaryOf(Set<StarField> filled, boolean aiOriginal, boolean confirmed) {

        String slots = Arrays.stream(StarField.values())
                .filter(filled::contains)
                .map(StarField::name)
                .collect(Collectors.joining(" · "));
        StringBuilder summary = new StringBuilder(slots.isEmpty() ? "채운 칸 없음" : slots + " 채움");

        if (aiOriginal) {
            summary.append(" · 원본(잠김)");
        }

        if (confirmed) {
            summary.append(" · 확정");
        }

        return summary.toString();

    }
}
