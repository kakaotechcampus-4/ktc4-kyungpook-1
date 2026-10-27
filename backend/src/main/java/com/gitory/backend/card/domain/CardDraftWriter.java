package com.gitory.backend.card.domain;

import com.gitory.backend.agent.port.StarDraft;
import com.gitory.backend.agent.port.StarEmptyReason;
import com.gitory.backend.agent.port.StarEvidenceRef;
import com.gitory.backend.agent.port.StarSlot;
import com.gitory.backend.agent.port.StarSlotDraft;
import com.gitory.backend.agent.port.StarSlotStatus;
import com.gitory.backend.card.domain.evidence.EvidenceShaMatcher;
import com.gitory.backend.card.infra.CardRepository;
import com.gitory.backend.card.infra.CardSlotNoteRepository;
import com.gitory.backend.card.infra.CardStatementRepository;
import com.gitory.backend.card.infra.CardVersionRepository;
import com.gitory.backend.card.infra.StatementEvidenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 초안 Job 이 받은 AI 초안을 후보 카드의 1번 버전에 저장하며, Job 행을 잠근 초안 Job 트랜잭션 안에서만 부른다
 * 사용자가 이미 쓴 칸은 그대로 두고, 근거 커밋을 하나도 이을 수 없는 문장은 화면에 뜨지 않게 저장하지 않고 근거 없음으로 비운다
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class CardDraftWriter {

    private static final short FIRST = 1;
    private static final int MAX_AREAS = 2;
    private static final int MAX_TECHS = 4;

    private final CardRepository cards;
    private final CardVersionRepository versions;
    private final CardStatementRepository statements;
    private final StatementEvidenceRepository evidence;
    private final CardSlotNoteRepository notes;

    /** 서버가 다시 켜져 같은 Job 이 다시 돌 때 이미 초안을 담은 카드를 건너뛰게 하며, 1번 버전이 없는 카드도 건너뛴다 */
    public boolean isDrafted(Long cardId) {

        return versions.findByCardIdAndVersionNo(cardId, FIRST)
                .map(first -> first.getSource() == VersionSource.AI_DRAFT)
                .orElse(true);

    }

    /**
     * 칸마다 AI 문장을 근거 커밋과 함께, 비운 칸은 비운 이유를, 확인이 필요한 칸은 그 이유를 저장한다
     * 시간이 모자라 비운 칸이 있으면 카드를 일부만 만든 카드로 표시하고 true 를 돌려준다
     */
    public boolean writeStar(Long cardId, StarDraft draft, Map<String, Long> commitIdsBySha) {

        Card card = cards.findWithLockById(cardId).orElseThrow();
        Optional<CardVersion> writable = writableFirst(card);
        if (writable.isEmpty()) {
            return false;
        }

        card.tag(tagsOf(draft.areas(), CardArea.class, MAX_AREAS), tagsOf(draft.techs(), CardTech.class, MAX_TECHS));
        boolean timedOut = false;
        Set<StarField> written = userWrittenSlots(cardId);
        for (StarField field : StarField.values()) {
            if (written.contains(field)) {
                continue;
            }
            StarSlotDraft slot = draft.slots().get(StarSlot.valueOf(field.name()));
            if (slot.status() == StarSlotStatus.EMPTY) {
                notes.save(CardSlotNote.dropped(cardId, FIRST, field, DropReason.valueOf(slot.emptyReason().name())));
                timedOut |= slot.emptyReason() == StarEmptyReason.TIMEOUT;
                continue;
            }
            writeSentence(cardId, field, slot, commitIdsBySha);
        }

        writable.get().markAiDraft();
        card.clearGenerationFailure();
        if (timedOut) {
            card.markGenerationPartial();
        }

        return timedOut;

    }

    /** diff 근거가 하나도 없어 STAR 를 부르지 않은 카드의 빈 칸에 근거 없음을 남겨, 화면이 빈 까닭을 보여 주게 한다 */
    /** 초안을 채우지 못한 카드에 실패 이유를 남겨, Job 이 끝나도 화면이 실패와 다시 시도를 보여 주게 한다 */
    public void markFailed(Long cardId, CardDraftFailure failure) {

        cards.findWithLockById(cardId).orElseThrow().markGenerationFailed(failure);

    }

    public void writeNoEvidence(Long cardId) {

        Card card = cards.findWithLockById(cardId).orElseThrow();
        Optional<CardVersion> writable = writableFirst(card);
        if (writable.isEmpty()) {
            return;
        }

        Set<StarField> written = userWrittenSlots(cardId);
        for (StarField field : StarField.values()) {
            if (!written.contains(field)) {
                notes.save(CardSlotNote.dropped(cardId, FIRST, field, DropReason.NO_EVIDENCE));
            }
        }

        writable.get().markAiDraft();
        card.clearGenerationFailure();

    }

    private void writeSentence(Long cardId, StarField field, StarSlotDraft slot, Map<String, Long> commitIdsBySha) {

        List<Long> commitIds = EvidenceShaMatcher.commitIdsOf(
                slot.evidence().stream().map(StarEvidenceRef::sha).toList(), commitIdsBySha);
        if (commitIds.isEmpty()) {
            notes.save(CardSlotNote.dropped(cardId, FIRST, field, DropReason.NO_EVIDENCE));
            return;
        }

        CardStatement saved = statements.save(CardStatement.drafted(cardId, FIRST, field, slot.text(),
                Confidence.valueOf(slot.confidence().name())));
        for (Long commitId : commitIds) {
            evidence.save(StatementEvidence.link(saved.getId(), commitId));
        }

        if (slot.status() == StarSlotStatus.NEEDS_REVIEW && slot.reviewReason() != null
                && !CardText.isBlank(slot.reviewReason())) {
            notes.save(CardSlotNote.review(cardId, FIRST, field, slot.reviewReason()));
        }

    }

    // AI 가 고른 순서가 중요한 순서라, 목록 밖의 값만 빼고 처음 나온 순서대로 상한까지 남긴다
    // 목록 밖의 값은 카드 초안을 실패시킬 일이 아니어서 버리고 WARN 으로만 알린다
    private static <E extends Enum<E>> List<E> tagsOf(List<String> values, Class<E> type, int max) {

        Set<E> kept = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String value : values) {
            Optional<E> tag = Arrays.stream(type.getEnumConstants()).filter(known -> known.name().equals(value))
                    .findFirst();
            tag.ifPresentOrElse(kept::add, () -> unknown.add(value));
        }
        if (!unknown.isEmpty()) {
            log.warn("AI 가 준 카드 태그 중 {} 목록 밖의 값 {}개를 버린다: {}", type.getSimpleName(), unknown.size(), unknown);
        }

        return kept.stream().limit(max).toList();

    }

    // 초안 Job 이 도는 동안에는 카드를 고칠 수 없어, 1번 버전이 현재 버전이고 아직 사용자 버전일 때만 담는다
    private Optional<CardVersion> writableFirst(Card card) {

        if (card.getCurrentVersion() != FIRST) {
            return Optional.empty();
        }

        return versions.findByCardIdAndVersionNo(card.getId(), FIRST)
                .filter(first -> first.getSource() == VersionSource.USER_EDIT && !first.isConfirmed());

    }

    private Set<StarField> userWrittenSlots(Long cardId) {

        Set<StarField> written = EnumSet.noneOf(StarField.class);
        for (CardStatement statement : statements.findByCardIdAndVersionNo(cardId, FIRST)) {
            written.add(statement.getStarSlot());
        }

        return written;

    }
}
