package com.gitory.backend.card.domain;

import com.gitory.backend.card.infra.CardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** 초안 Job 이 자기 카드를 카드 표를 직접 읽지 않고 찾게 한다 */
@Service
@RequiredArgsConstructor
public class GeneratedCardQueryService {

    private final CardRepository cards;

    /** Job 이 만든 카드의 공개 id 를 만든 순서대로 돌려준다 */
    public List<UUID> cardIdsOf(UUID generationJobId) {

        return cards.findPublicIdsByGenerationJobId(generationJobId);

    }

    /** Job 이 초안을 채울 카드를 만든 순서대로 돌려준다 */
    public List<GeneratedCard> cardsOf(UUID generationJobId) {

        return cards.findByGenerationJobIdOrderById(generationJobId).stream()
                .map(card -> new GeneratedCard(card.getId(), card.getCandidateId(), card.getTitle()))
                .toList();

    }
}
