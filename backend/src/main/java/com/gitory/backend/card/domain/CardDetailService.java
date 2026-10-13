package com.gitory.backend.card.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.card.domain.interview.InterviewTurn;
import com.gitory.backend.card.infra.CardVersionRepository;
import com.gitory.backend.card.infra.InterviewTurnRepository;
import com.gitory.backend.card.port.CardGenerationPort;
import com.gitory.backend.ingest.domain.RepositoryReference;
import com.gitory.backend.ingest.domain.RepositoryReferenceService;
import com.gitory.backend.recommend.domain.CandidateReference;
import com.gitory.backend.recommend.domain.CandidateReferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 내 카드 한 장을 현재 버전의 네 칸, 답한 되묻기 질문 수, 칸에 연결된 질문 번호와 함께 읽는다
 * 임시 저장이 문장을 지우고 다시 넣는 사이에도 문장과 근거를 한 시점으로 읽도록 REPEATABLE READ 로 읽는다
 */
@Service
@RequiredArgsConstructor
public class CardDetailService {

    private final CardOwnershipService ownership;
    private final CardVersionRepository versions;
    private final CardVersionReader reader;
    private final RepositoryReferenceService repositories;
    private final CandidateReferenceService candidates;
    private final CardGenerationPort generationJobs;
    private final InterviewTurnRepository turns;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CardDetail load(UUID publicId, Long userId) {

        Card card = ownership.findOwned(publicId, userId, AuditAction.VIEW_CARD);
        CardVersion current = versions.findByCardIdAndVersionNo(card.getId(), card.getCurrentVersion()).orElseThrow();
        RepositoryReference repository = Optional.ofNullable(card.getUserRepositoryId())
                .flatMap(repositories::find)
                .orElse(null);
        VersionSlots slots = reader.read(card.getId(), card.getCurrentVersion());
        List<InterviewTurn> asked = turns.findByCardIdOrderBySeqAsc(card.getId());

        CandidateReference candidate = Optional.ofNullable(card.getCandidateId())
                .flatMap(candidates::find)
                .orElse(null);

        return new CardDetail(card.getPublicId(), card.getCardType(), card.getStatus(), card.getTitle(),
                card.getPeriod(), repository, current.getSource(), current.getUpdatedAt(), slots,
                card.getMaskRules(), card.getConfirmedAt(), card.getCreatedAt(), candidate, generationOf(card),
                answeredCount(asked), turnNosOf(slots, asked));

    }

    /** 초안 Job 이 아직 돌거나 일부만 만들어졌을 때만 알리고, 그 밖에는 화면이 초안 대기를 멈추도록 비운다 */
    private CardGeneration generationOf(Card card) {

        UUID jobId = card.getGenerationJobId();
        if (jobId == null) {
            return null;
        }

        if (card.isGenerationPartial() || generationJobs.isRunning(jobId)) {
            return new CardGeneration(jobId, card.isGenerationPartial());
        }

        return null;

    }

    // 답하지 않은 질문은 세지 않고, 답한 뒤 글이 바뀌어 질문과의 연결이 끊긴 칸이 있어도 센다
    private static int answeredCount(List<InterviewTurn> asked) {

        return (int) asked.stream().filter(InterviewTurn::isAnswered).count();

    }

    private static Map<StarField, Integer> turnNosOf(VersionSlots slots, List<InterviewTurn> asked) {

        Map<Long, Integer> seqById = new HashMap<>();
        asked.forEach(turn -> seqById.put(turn.getId(), (int) turn.getSeq()));

        Map<StarField, Integer> turnNos = new EnumMap<>(StarField.class);
        slots.slots().forEach((field, slot) -> {
            Integer turnNo = seqById.get(slot.sourceTurnId());
            if (turnNo != null) {
                turnNos.put(field, turnNo);
            }
        });

        return turnNos;

    }
}
