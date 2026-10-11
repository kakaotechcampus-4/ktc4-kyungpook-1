package com.gitory.backend.card.domain.interview;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.card.domain.Card;
import com.gitory.backend.card.domain.CardOwnershipService;
import com.gitory.backend.card.infra.InterviewTurnRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 내 카드의 되묻기 질문과 답을 질문 번호 순서로 돌려준다
 * 읽기는 감사 기록에 남기지 않고, 남의 카드를 읽으려 한 것만 거절로 남는다
 */
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(InterviewLimits.class)
public class InterviewHistoryService {

    private final CardOwnershipService ownership;
    private final InterviewTurnRepository turns;
    private final InterviewLimits limits;

    @Transactional(readOnly = true)
    public List<InterviewTurnView> history(UUID publicId, Long userId) {

        Card card = ownership.findOwned(publicId, userId, AuditAction.VIEW_CARD);
        InterviewSourceType sourceType = InterviewSourceType.of(card);

        return turns.findByCardIdOrderBySeqAsc(card.getId()).stream()
                .map(turn -> InterviewTurnView.of(turn, sourceType, limits.maxTurns()))
                .toList();

    }
}
