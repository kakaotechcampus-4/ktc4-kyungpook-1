package com.gitory.backend.card.domain.interview;

import com.gitory.backend.agent.port.InterviewCandidate;
import com.gitory.backend.agent.port.InterviewCardSourceType;
import com.gitory.backend.agent.port.InterviewCommit;
import com.gitory.backend.agent.port.InterviewConfidence;
import com.gitory.backend.agent.port.InterviewMissingSlot;
import com.gitory.backend.agent.port.InterviewQuestionRequest;
import com.gitory.backend.agent.port.InterviewStarSlot;
import com.gitory.backend.card.domain.Card;
import com.gitory.backend.card.domain.CardVersionReader;
import com.gitory.backend.card.domain.EvidenceType;
import com.gitory.backend.card.domain.SlotContent;
import com.gitory.backend.card.domain.StarField;
import com.gitory.backend.ingest.domain.CommitSummary;
import com.gitory.backend.ingest.domain.CommitSummaryService;
import com.gitory.backend.ingest.domain.DraftCommit;
import com.gitory.backend.recommend.domain.CandidateDraftSource;
import com.gitory.backend.recommend.domain.CandidateDraftSourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private final InterviewLimits limits;

    /** 지금 버전의 글과 후보를 읽으므로 카드와 같은 읽기 트랜잭션 안에서 불러야 한다 */
    public InterviewQuestionRequest assemble(Card card, StarField slot, int existingTurnCount) {

        SlotContent content = reader.read(card.getId(), card.getCurrentVersion()).slots().get(slot);
        Optional<CandidateDraftSource> source = Optional.ofNullable(card.getCandidateId())
                .flatMap(candidateId -> draftSources.load(candidateId, COMMIT_LIMIT))
                .filter(found -> InterviewSourceType.of(found.type()) != InterviewSourceType.DIRECT_CARD);
        List<Long> candidateCommitIds = source.map(found -> found.commits().stream().map(DraftCommit::id).toList())
                .orElse(List.of());
        List<Long> linkedCommitIds = content == null ? List.of() : content.commitIds();
        Map<Long, CommitSummary> summaries = summariesOf(candidateCommitIds, linkedCommitIds);

        InterviewMissingSlot missingSlot = new InterviewMissingSlot(InterviewStarSlot.valueOf(slot.name()),
                ONLY_STATEMENT_SEQ, bodyOf(content), confidenceOf(content), commitsOf(linkedCommitIds, summaries));

        if (source.isEmpty()) {
            return new InterviewQuestionRequest(card.getId(), null, List.of(missingSlot), existingTurnCount,
                    limits.maxTurns(), InterviewCardSourceType.DIRECT_CARD);
        }

        CandidateDraftSource found = source.get();
        InterviewCandidate candidate = new InterviewCandidate(found.githubPrNumber(),
                commitsOf(candidateCommitIds, summaries), List.of(), List.of());

        return new InterviewQuestionRequest(card.getId(), candidate, List.of(missingSlot), existingTurnCount,
                limits.maxTurns(), InterviewCardSourceType.valueOf(InterviewSourceType.of(found.type()).name()));

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
