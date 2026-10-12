package com.gitory.backend.recommend.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.ingest.domain.CommitSummary;
import com.gitory.backend.ingest.domain.CommitSummaryService;
import com.gitory.backend.ingest.domain.LastAnalyzedService;
import com.gitory.backend.ingest.domain.RepositoryOwnershipService;
import com.gitory.backend.ingest.domain.RepositoryReference;
import com.gitory.backend.ingest.domain.RepositoryReferenceService;
import com.gitory.backend.recommend.infra.CandidateBoardCommitRepository;
import com.gitory.backend.recommend.infra.CandidateGroupingRepository;
import com.gitory.backend.recommend.infra.CandidateRepository;
import com.gitory.backend.recommend.port.UsedCardPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 저장해 둔 후보를 보드로 읽을 뿐 열 때 묶지 않으며, 묶음 교체나 카드 만들기가 읽는 사이에 커밋돼도 한 시점으로 읽도록 REPEATABLE READ 로 읽는다
 */
@Service
@RequiredArgsConstructor
public class CandidateBoardService {

    static final String NEVER_ANALYZED = "아직 정리하지 않은 저장소예요";
    static final String NOT_GROUPED_YET = "아직 이 저장소의 후보를 만들지 못했어요";

    private final RepositoryOwnershipService ownership;
    private final RepositoryReferenceService repositories;
    private final LastAnalyzedService lastAnalyzed;
    private final CommitSummaryService commitSummaries;
    private final CandidateRepository candidates;
    private final CandidateBoardCommitRepository candidateCommits;
    private final CandidateGroupingRepository groupings;
    private final UsedCardPort usedCards;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CandidateBoard load(UUID repositoryId, Long userId) {

        Long userRepositoryId = ownership.findOwnedRepository(repositoryId, userId, AuditAction.VIEW_CANDIDATES)
                .orElseThrow(CandidateBoardNotFoundException::new);
        List<Candidate> stored = candidates.findByUserRepositoryId(userRepositoryId).stream()
                .sorted(Candidate.BOARD_ORDER)
                .toList();
        CandidateVerdict verdict = CandidateVerdict.of(stored.size(), false);
        if (stored.isEmpty()) {
            return new CandidateBoard(repositoryId, verdict, emptyReasonsOf(userRepositoryId), List.of());
        }

        Map<Long, List<CandidateCommit>> commitsByCandidate = candidateCommits
                .findByCandidateIdInOrderById(stored.stream().map(Candidate::getId).toList()).stream()
                .collect(Collectors.groupingBy(CandidateCommit::getCandidateId));
        Map<Long, CommitSummary> summaries = commitSummaries.findByIds(clusterCommitIdsOf(stored, commitsByCandidate));
        Map<Long, UUID> cardIds = usedCards.cardIdsOf(stored.stream()
                .filter(candidate -> candidate.getStatus() == CandidateStatus.USED)
                .map(Candidate::getId)
                .toList());
        Optional<RepositoryReference> repository = repositories.find(userRepositoryId);

        return new CandidateBoard(repositoryId, verdict, List.of(), stored.stream()
                .map(candidate -> boardCandidateOf(candidate,
                        commitsByCandidate.getOrDefault(candidate.getId(), List.of()), summaries, repository,
                        cardIds.get(candidate.getId())))
                .toList());

    }

    /** 분석하지 않았거나 아직 못 묶은 빈 보드에만 이유를 한 줄 준다 */
    private List<String> emptyReasonsOf(Long userRepositoryId) {

        Optional<Instant> analyzedAt = lastAnalyzed.lastAnalyzedAt(userRepositoryId);
        if (analyzedAt.isEmpty()) {
            return List.of(NEVER_ANALYZED);
        }
        boolean groupedSince = groupings.findById(userRepositoryId)
                .map(grouping -> !grouping.getGroupedAt().isBefore(analyzedAt.get()))
                .orElse(false);

        return groupedSince ? List.of() : List.of(NOT_GROUPED_YET);

    }

    private static List<Long> clusterCommitIdsOf(List<Candidate> stored,
                                                 Map<Long, List<CandidateCommit>> commitsByCandidate) {

        return stored.stream()
                .filter(candidate -> candidate.getSourceType() == CandidateType.COMMIT_CLUSTER)
                .flatMap(candidate -> commitsByCandidate.getOrDefault(candidate.getId(), List.of()).stream())
                .map(CandidateCommit::getCommitId)
                .toList();

    }

    private static BoardCandidate boardCandidateOf(Candidate candidate, List<CandidateCommit> commits,
                                                   Map<Long, CommitSummary> summaries,
                                                   Optional<RepositoryReference> repository, UUID usedByCardId) {

        boolean cluster = candidate.getSourceType() == CandidateType.COMMIT_CLUSTER;
        List<BoardCommit> boardCommits = cluster
                ? commits.stream().map(commit -> boardCommitOf(commit, summaries, repository.orElseThrow())).toList()
                : null;

        return new BoardCandidate(candidate.getPublicId(), candidate.getSourceType(), candidate.getStatus(),
                refOf(candidate), candidate.getTitle(), candidate.getReasonText(),
                (int) commits.stream().filter(CandidateCommit::isIncluded).count(), candidate.getFileCount(),
                candidate.getReviewCommentCount(), candidate.getSpanDays(), candidate.getCodeRatio(),
                candidate.isLowCardWorth(), candidate.getScore() == null ? BigDecimal.ZERO : candidate.getScore(),
                boardCommits, usedByCardId);

    }

    /** 화면은 ref 로 null 을 받지 못해서, AI 가 ref 를 주지 않은 PR 후보는 AI 와 같은 규칙으로 PR 번호를 쓴다 */
    private static String refOf(Candidate candidate) {

        if (candidate.getSourceRef() != null) {
            return candidate.getSourceRef();
        }

        return candidate.getSourceType() == CandidateType.PR ? "#" + candidate.getGithubPrNumber() : "";

    }

    private static BoardCommit boardCommitOf(CandidateCommit commit, Map<Long, CommitSummary> summaries,
                                             RepositoryReference repository) {

        CommitSummary summary = summaries.get(commit.getCommitId());
        String url = "https://github.com/" + repository.ownerLogin() + "/" + repository.name() + "/commit/"
                + summary.sha();

        return new BoardCommit(summary.sha(), summary.message() == null ? "" : summary.message(),
                commit.getDisplayPath(), summary.authoredAt(), commit.isIncluded(), url);

    }
}
