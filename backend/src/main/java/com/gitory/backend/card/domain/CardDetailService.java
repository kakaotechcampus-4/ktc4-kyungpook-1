package com.gitory.backend.card.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.card.domain.interview.InterviewTurn;
import com.gitory.backend.card.infra.CardDraftStepRepository;
import com.gitory.backend.card.infra.CardSlotNoteRepository;
import com.gitory.backend.card.infra.CardVersionRepository;
import com.gitory.backend.card.infra.InterviewTurnRepository;
import com.gitory.backend.card.port.CardGenerationPort;
import com.gitory.backend.ingest.domain.CommitSummary;
import com.gitory.backend.ingest.domain.CommitSummaryService;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 내 카드 한 장을 현재 버전의 네 칸, 칸 메모, AI 초안 단계, 답한 되묻기 질문 수, 칸에 연결된 질문 번호와 함께 읽는다
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
    private final CardSlotNoteRepository notes;
    private final CardDraftStepRepository draftSteps;
    private final CommitSummaryService commitSummaries;

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
                card.areaTags(), card.techTags(), card.themeTags(), card.getPeriod(), repository, current.getSource(),
                current.getUpdatedAt(), slots, card.getMaskRules(), card.getConfirmedAt(), card.getCreatedAt(),
                candidate, generationOf(card), card.getGenerationFailure(), answeredCount(asked),
                card.getInterviewEndReason(), turnNosOf(slots, asked), notesOf(card),
                commitsOf(slots), draftStepsOf(card));

    }

    // 근거 커밋의 sha 로 화면이 GitHub 커밋 링크를 걸 수 있게, 네 칸의 커밋을 한 번에 읽는다
    private Map<Long, CommitSummary> commitsOf(VersionSlots slots) {

        Set<Long> commitIds = new LinkedHashSet<>();
        slots.slots().values().forEach(slot -> commitIds.addAll(slot.commitIds()));

        return commitIds.isEmpty() ? Map.of() : commitSummaries.findByIds(commitIds);

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

    // 단계는 AI 초안을 담은 1번 버전에만 남고, 사용자가 고쳐 버전이 늘어도 초안을 만든 과정이라 그대로 보여 준다
    private List<DraftStep> draftStepsOf(Card card) {

        return draftSteps.findByCardIdAndVersionNoOrderBySeq(card.getId(), CardDraftWriter.FIRST).stream()
                .map(CardDraftStep::view)
                .toList();

    }

    private Map<StarField, SlotNote> notesOf(Card card) {

        Map<StarField, SlotNote> notesBySlot = new EnumMap<>(StarField.class);
        for (CardSlotNote note : notes.findByCardIdAndVersionNo(card.getId(), card.getCurrentVersion())) {
            notesBySlot.put(note.getStarSlot(), new SlotNote(note.getDropReason(), note.getReviewReason()));
        }

        return notesBySlot;

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
