package com.gitory.backend.recommend.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.ingest.domain.IngestLimits;
import com.gitory.backend.ingest.domain.OwnCommitFileService;
import com.gitory.backend.ingest.domain.RepositoryOwnershipService;
import com.gitory.backend.ingest.domain.StoredCommit;
import com.gitory.backend.recommend.infra.CandidateBoardCommitRepository;
import com.gitory.backend.recommend.infra.CandidateCommitRepository;
import com.gitory.backend.recommend.infra.CandidateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 사용자가 제목·요약과 고른 내 커밋으로 만든 직접 추가 후보를 저장한다
 * 다른 후보 행은 읽기만 하고 잠그지 않아, 다시 분석이 저장하는 동안에도 기다리지 않는다
 */
@Service
@RequiredArgsConstructor
public class CandidateAddService {

    static final String SOURCE_REF = "MANUAL";

    private final RepositoryOwnershipService ownership;
    private final OwnCommitFileService ownCommits;
    private final CandidateRepository candidates;
    private final CandidateBoardCommitRepository boardCommits;
    private final CandidateCommitRepository candidateCommits;
    private final IngestLimits limits;

    /** 같은 제목·요약·커밋으로 만든 새 후보가 이미 있으면 또 만들지 않고 그 후보를 돌려준다 */
    @Transactional
    public BoardCandidate add(UUID repositoryId, Long userId, String login, String title, String summary,
                              List<String> shas) {

        Long userRepositoryId = ownership.findOwnedRepository(repositoryId, userId, AuditAction.CREATE_CANDIDATE)
                .orElseThrow(CandidateBoardNotFoundException::new);
        CandidateText.require(title, "title", CandidateText.TITLE_MAX_LENGTH);
        CandidateText.require(summary, "summary", CandidateText.REASON_MAX_LENGTH);
        List<StoredCommit> commits = ownCommitsOf(userRepositoryId, login, distinctShasOf(shas));

        return sameCandidate(userRepositoryId, title, summary, commits)
                .orElseGet(() -> create(userRepositoryId, title, summary, commits));

    }

    private List<String> distinctShasOf(List<String> shas) {

        if (shas == null) {
            return List.of();
        }
        if (shas.stream().anyMatch(Objects::isNull)) {
            throw new InvalidCandidateInputException("shas 에 빈 값이 있습니다.");
        }
        List<String> distinct = List.copyOf(new LinkedHashSet<>(shas));
        if (distinct.size() > limits.maxCommits()) {
            throw new InvalidCandidateInputException("shas 는 " + limits.maxCommits() + "개까지 보낼 수 있습니다.");
        }

        return distinct;

    }

    /** 이 저장소 연결의 내 커밋만 붙일 수 있고, 하나라도 아니면 아무것도 만들지 않으며, 보낸 순서를 지킨다 */
    private List<StoredCommit> ownCommitsOf(Long userRepositoryId, String login, List<String> shas) {

        if (shas.isEmpty()) {
            return List.of();
        }
        Map<String, StoredCommit> found = ownCommits.findOwnCommits(userRepositoryId, login, shas).stream()
                .collect(Collectors.toMap(StoredCommit::sha, Function.identity()));
        if (!found.keySet().containsAll(shas)) {
            throw new InvalidCandidateInputException("shas 에 이 저장소의 내 커밋이 아닌 sha 가 있습니다.");
        }

        return shas.stream().map(found::get).toList();

    }

    private Optional<BoardCandidate> sameCandidate(Long userRepositoryId, String title, String summary,
                                                   List<StoredCommit> commits) {

        Set<Long> commitIds = commits.stream().map(StoredCommit::id).collect(Collectors.toSet());
        for (Candidate existing : candidates
                .findByUserRepositoryIdAndSourceTypeAndSourceRefAndTitleAndReasonTextAndStatusOrderById(
                        userRepositoryId, CandidateType.MANUAL, SOURCE_REF, title, summary, CandidateStatus.NEW)) {
            List<CandidateCommit> attached = boardCommits.findByCandidateIdInOrderById(List.of(existing.getId()));
            if (attached.stream().map(CandidateCommit::getCommitId).collect(Collectors.toSet()).equals(commitIds)) {
                return Optional.of(boardCandidateOf(existing, attached));
            }
        }

        return Optional.empty();

    }

    private BoardCandidate create(Long userRepositoryId, String title, String summary, List<StoredCommit> commits) {

        Candidate candidate = candidates.save(
                Candidate.manual(userRepositoryId, SOURCE_REF, title, summary, CandidateMeta.of(commits)));
        List<CandidateCommit> attached = candidateCommits.saveAll(commits.stream()
                .map(commit -> CandidateCommit.attached(candidate.getId(), commit.id(),
                        CandidateMeta.displayPathOf(commit)))
                .toList());

        return boardCandidateOf(candidate, attached);

    }

    private static BoardCandidate boardCandidateOf(Candidate candidate, List<CandidateCommit> attached) {

        return CandidateBoardService.boardCandidateOf(candidate, attached, Map.of(), Optional.empty(), null);

    }
}
