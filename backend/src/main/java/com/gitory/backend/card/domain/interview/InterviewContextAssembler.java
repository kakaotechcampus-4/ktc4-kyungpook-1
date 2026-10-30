package com.gitory.backend.card.domain.interview;

import com.gitory.backend.agent.port.InterviewCandidate;
import com.gitory.backend.agent.port.InterviewCardSourceType;
import com.gitory.backend.agent.port.InterviewCommit;
import com.gitory.backend.agent.port.InterviewConfidence;
import com.gitory.backend.agent.port.InterviewMissingSlot;
import com.gitory.backend.agent.port.InterviewPreviousTurn;
import com.gitory.backend.agent.port.InterviewQuestionRequest;
import com.gitory.backend.agent.port.InterviewStarSlot;
import com.gitory.backend.agent.port.InterviewTurnOutcome;
import com.gitory.backend.card.domain.Card;
import com.gitory.backend.card.domain.CardSlotNote;
import com.gitory.backend.card.domain.CardVersionReader;
import com.gitory.backend.card.domain.Confidence;
import com.gitory.backend.card.domain.EvidenceType;
import com.gitory.backend.card.domain.SlotContent;
import com.gitory.backend.card.domain.StarField;
import com.gitory.backend.card.infra.CardSlotNoteRepository;
import com.gitory.backend.ingest.domain.CommitSummary;
import com.gitory.backend.ingest.domain.CommitSummaryService;
import com.gitory.backend.ingest.domain.DraftCommit;
import com.gitory.backend.recommend.domain.CandidateDraftSource;
import com.gitory.backend.recommend.domain.CandidateDraftSourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 카드의 지금 버전에서 묻는 칸 하나를 AI 질문 요청으로 조립한다
 * 후보로 만든 카드는 후보 출처와 커밋·근거 커밋을 싣고, AI 가 받지 않는 빈 메시지는 뺀다
 */
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(InterviewLimits.class)
public class InterviewContextAssembler {

    // 한 칸에 문장이 하나뿐이다
    private static final int ONLY_STATEMENT_SEQ = 1;

    // 카드 초안 Job 이 diff 근거에 쓴 커밋과 같은 범위다
    private static final int COMMIT_LIMIT = 50;

    private static final int MESSAGE_MAX_LENGTH = 200;

    private final CardVersionReader reader;
    private final CandidateDraftSourceService draftSources;
    private final CommitSummaryService commitSummaries;
    private final CardSlotNoteRepository notes;
    private final InterviewLimits limits;

    /**
     * 지금 버전의 글과 후보를 읽으므로 카드와 같은 읽기 트랜잭션 안에서 불러야 한다
     * 고를 수 있는 칸마다 지금 글을 싣고, 지정한 칸과 이미 끝난 질문을 함께 보내 AI 가 다음 칸을 고르게 한다
     */
    public InterviewQuestionRequest assemble(Card card, List<StarField> allowed, StarField target,
                                             List<InterviewTurn> asked) {

        Map<StarField, SlotContent> slots = reader.read(card.getId(), card.getCurrentVersion()).slots();
        Optional<CandidateDraftSource> source = Optional.ofNullable(card.getCandidateId())
                .flatMap(candidateId -> draftSources.load(candidateId, COMMIT_LIMIT))
                .filter(found -> InterviewSourceType.of(found.type()) != InterviewSourceType.DIRECT_CARD);

        List<Long> candidateCommitIds = source.map(found -> found.commits().stream().map(DraftCommit::id).toList())
                .orElse(List.of());
        List<Long> linkedCommitIds = allowed.stream()
                .map(slots::get)
                .filter(Objects::nonNull)
                .flatMap(content -> content.commitIds().stream())
                .toList();
        Map<Long, CommitSummary> summaries = summariesOf(candidateCommitIds, linkedCommitIds);
        Map<StarField, String> reviewReasons = reviewReasonsOf(card);

        List<InterviewMissingSlot> missingSlots = allowed.stream()
                .map(slot -> missingSlotOf(slot, slots.get(slot), reviewReasons.get(slot), summaries))
                .toList();
        List<InterviewStarSlot> allowedSlots = allowed.stream().map(slot -> InterviewStarSlot.valueOf(slot.name())).toList();
        InterviewStarSlot targetSlot = target == null ? null : InterviewStarSlot.valueOf(target.name());
        List<InterviewPreviousTurn> previousTurns = previousTurnsOf(asked);

        if (source.isEmpty()) {
            return new InterviewQuestionRequest(card.getId(), null, missingSlots, asked.size(), limits.maxTurns(),
                    InterviewCardSourceType.DIRECT_CARD, targetSlot, allowedSlots, previousTurns);
        }

        CandidateDraftSource found = source.get();
        InterviewCandidate candidate = new InterviewCandidate(found.githubPrNumber(),
                commitsOf(candidateCommitIds, summaries), List.of(), List.of());

        return new InterviewQuestionRequest(card.getId(), candidate, missingSlots, asked.size(), limits.maxTurns(),
                InterviewCardSourceType.valueOf(InterviewSourceType.of(found.type()).name()), targetSlot, allowedSlots,
                previousTurns);

    }

    private static InterviewMissingSlot missingSlotOf(StarField slot, SlotContent content, String reviewReason,
                                                      Map<Long, CommitSummary> summaries) {

        List<Long> linked = content == null ? List.of() : content.commitIds();

        return new InterviewMissingSlot(InterviewStarSlot.valueOf(slot.name()), ONLY_STATEMENT_SEQ, bodyOf(content),
                confidenceOf(content), reviewReasonOf(content, reviewReason), commitsOf(linked, summaries));

    }

    private Map<StarField, String> reviewReasonsOf(Card card) {

        Map<StarField, String> reasons = new EnumMap<>(StarField.class);
        for (CardSlotNote note : notes.findByCardIdAndVersionNo(card.getId(), card.getCurrentVersion())) {
            if (note.getReviewReason() != null) {
                reasons.put(note.getStarSlot(), note.getReviewReason());
            }
        }

        return reasons;

    }

    // 카드 화면의 확인 필요 칸과 같게, 확신도가 LOW 인 글에만 이유를 실어 AI 가 무엇이 약한지 짚어 묻게 한다
    private static String reviewReasonOf(SlotContent content, String reviewReason) {

        if (content == null || content.confidence() != Confidence.LOW || reviewReason == null || isBlank(reviewReason)) {
            return null;
        }

        return reviewReason;

    }

    // 결과가 정해진 질문만 보내고, 지금 쓰지 않는 LATER·INSUFFICIENT 는 AI 계약에 없어 뺀다
    private static List<InterviewPreviousTurn> previousTurnsOf(List<InterviewTurn> asked) {

        List<InterviewPreviousTurn> previous = new ArrayList<>();
        for (InterviewTurn turn : asked) {
            InterviewTurnOutcome outcome = outcomeOf(turn.getOutcome());
            if (outcome != null) {
                previous.add(new InterviewPreviousTurn(InterviewStarSlot.valueOf(turn.getStarSlot().name()),
                        turn.getQuestionText(), outcome, turn.getAnswerText()));
            }
        }

        return previous;

    }

    private static InterviewTurnOutcome outcomeOf(TurnOutcome outcome) {

        if (outcome == null) {
            return null;
        }

        return switch (outcome) {
            case ANSWERED -> InterviewTurnOutcome.ANSWERED;
            case MEASURED -> InterviewTurnOutcome.MEASURED;
            case NOT_MEASURED -> InterviewTurnOutcome.NOT_MEASURED;
            case SKIPPED -> InterviewTurnOutcome.SKIPPED;
            case LATER, INSUFFICIENT -> null;
        };

    }

    private Map<Long, CommitSummary> summariesOf(List<Long> candidateCommitIds, List<Long> linkedCommitIds) {

        Set<Long> ids = new LinkedHashSet<>(candidateCommitIds);
        ids.addAll(linkedCommitIds);

        return ids.isEmpty() ? Map.of() : commitSummaries.findByIds(ids);

    }

    // AI 가 맨 앞 커밋을 질문에 인용하므로 받은 순서를 지킨다
    private static List<InterviewCommit> commitsOf(List<Long> ids, Map<Long, CommitSummary> summaries) {

        List<InterviewCommit> commits = new ArrayList<>();
        for (Long id : ids) {
            CommitSummary summary = summaries.get(id);
            String message = summary == null ? null : firstLineOf(summary.message());
            if (message != null) {
                commits.add(new InterviewCommit(summary.id(), summary.sha(), message));
            }
        }

        return commits;

    }

    // 빈 글이 아닌 첫 줄을 보내고, 없으면 null 이다
    private static String firstLineOf(String message) {

        if (message == null) {
            return null;
        }

        for (String line : message.split("\\R")) {
            if (!isBlank(line)) {
                return truncate(line.strip());
            }
        }

        return null;

    }

    private static String truncate(String line) {

        if (line.codePointCount(0, line.length()) <= MESSAGE_MAX_LENGTH) {
            return line;
        }

        return line.substring(0, line.offsetByCodePoints(0, MESSAGE_MAX_LENGTH)) + "…";

    }

    // AI 는 NBSP 같은 공백을 지운 뒤 빈 글을 거절하는데 자바 isBlank 는 NBSP 를 공백으로 보지 않는다
    // U+FEFF 는 AI 가 받지만 질문에 보이지 않아 카드 글처럼 뺀다
    private static boolean isBlank(String text) {

        return text.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0xFEFF);

    }

    private static String bodyOf(SlotContent content) {

        return content == null ? null : content.body();

    }

    // 사용자가 쓰거나 고른 글은 확신도를 비워 두므로, 이미 답한 칸을 약한 칸으로 되묻지 않게 HIGH 로 보낸다
    private static InterviewConfidence confidenceOf(SlotContent content) {

        if (content == null) {
            return InterviewConfidence.LOW;
        }

        if (content.confidence() != null) {
            return InterviewConfidence.valueOf(content.confidence().name());
        }

        return isWrittenByUser(content.evidenceType()) ? InterviewConfidence.HIGH : InterviewConfidence.LOW;

    }

    private static boolean isWrittenByUser(EvidenceType evidenceType) {

        return evidenceType == EvidenceType.USER_STATED || evidenceType == EvidenceType.USER_SELECTED;

    }
}
