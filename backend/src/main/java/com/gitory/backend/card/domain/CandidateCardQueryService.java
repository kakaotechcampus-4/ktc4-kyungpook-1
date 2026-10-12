package com.gitory.backend.card.domain;

import com.gitory.backend.card.infra.CandidateCardRepository;
import com.gitory.backend.card.infra.CandidateCardRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** 후보로 만든 카드가 있는지를 다른 모듈이 카드 표를 직접 읽지 않고 얻게 한다 */
@Service
@RequiredArgsConstructor
public class CandidateCardQueryService {

    private final CandidateCardRepository cards;

    /** 지워지지 않은 카드는 후보마다 하나뿐이라는 규칙을 믿고, 둘이 있으면 예외로 드러낸다 */
    public Map<Long, UUID> publicIdsOf(Collection<Long> candidateIds) {

        return cards.findLive(candidateIds).stream()
                .collect(Collectors.toMap(CandidateCardRow::getCandidateId, CandidateCardRow::getPublicId));

    }
}
