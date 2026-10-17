package com.gitory.backend.card.domain;

import com.gitory.backend.card.infra.CardStatementRepository;
import com.gitory.backend.card.infra.StatementEvidenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 카드 버전 하나의 네 칸을 읽어, 상세와 목록이 같은 칸 상태 규칙({@link VersionSlots#stateOf})을 쓰게 한다
 * 임시 저장은 문장을 지우고 다시 넣어 문장 id 가 바뀌므로, 문장과 근거를 한 시점으로 읽으려면 부르는 쪽이 REPEATABLE READ 읽기 트랜잭션을 열어야 한다
 */
@Service
@RequiredArgsConstructor
public class CardVersionReader {

    private final CardStatementRepository statements;
    private final StatementEvidenceRepository evidence;

    public VersionSlots read(Long cardId, short versionNo) {

        List<CardStatement> found = statements.findByCardIdAndVersionNo(cardId, versionNo);

        return slotsOf(versionNo, found, commitIdsOf(found));

    }

    /** 카드마다 현재 버전을 읽으며, 카드가 몇 장이든 쿼리는 두 번이고 문장이 없는 카드도 결과에 넣는다 */
    public Map<Long, VersionSlots> readCurrent(Collection<Card> cards) {

        List<CardStatement> found = statements.findCurrentOf(cards.stream().map(Card::getId).toList());
        Map<Long, List<Long>> commitIds = commitIdsOf(found);
        Map<Long, List<CardStatement>> byCard = found.stream()
                .collect(Collectors.groupingBy(CardStatement::getCardId));

        Map<Long, VersionSlots> current = new HashMap<>();
        for (Card card : cards) {
            current.put(card.getId(),
                    slotsOf(card.getCurrentVersion(), byCard.getOrDefault(card.getId(), List.of()), commitIds));
        }

        return current;

    }

    private Map<Long, List<Long>> commitIdsOf(List<CardStatement> found) {

        return evidence.findCommitIdsByStatement(found.stream().map(CardStatement::getId).toList());

    }

    private static VersionSlots slotsOf(short versionNo, List<CardStatement> found, Map<Long, List<Long>> commitIds) {

        Map<StarField, SlotContent> slots = new EnumMap<>(StarField.class);
        for (CardStatement statement : found) {
            slots.put(statement.getStarSlot(), new SlotContent(statement.getStarSlot(), statement.getBody(),
                    statement.getEvidenceType(), statement.getConfidence(), statement.getSourceTurnId(),
                    List.copyOf(commitIds.getOrDefault(statement.getId(), List.of()))));
        }

        return new VersionSlots(versionNo, Collections.unmodifiableMap(slots));

    }
}
