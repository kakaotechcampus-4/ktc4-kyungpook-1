package com.gitory.backend.recommend.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.ingest.domain.CommitSummary;
import com.gitory.backend.ingest.domain.CommitSummaryService;
import com.gitory.backend.ingest.domain.OwnCommitFileService;
import com.gitory.backend.ingest.domain.RepositoryReference;
import com.gitory.backend.ingest.domain.RepositoryReferenceService;
import com.gitory.backend.recommend.infra.CandidateBoardCommitRepository;
import com.gitory.backend.recommend.infra.CandidateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 후보 한 개의 제외·복원과 커밋 빼기를 처리하며, 판단하기 전에 그 후보 한 행만 잠가 같은 후보에 온 요청과 다시 분석 저장이 차례로 반영되게 한다
 * 수정할 수 있는 후보는 내 저장소 연결 안에서만 찾아, 남의 후보 행은 잠그지 않는다
 */
@Service
@RequiredArgsConstructor
public class CandidateEditService {

    private final OwnCommitFileService ownConnections;
    private final CandidateRepository candidates;
    private final CandidateBoardCommitRepository candidateCommits;
    private final CommitSummaryService commitSummaries;
    private final RepositoryReferenceService repositories;
    private final AuditLog auditLog;

    /** 카드로 쓴 후보는 409, 그다음 입력 검사 순서로 확인하고 하나라도 틀리면 아무것도 바꾸지 않는다 */
    @Transactional
    public EditedCandidate edit(UUID candidateId, Long userId, String status, List<String> excludedShas) {

        Candidate candidate = lockOwned(candidateId, userId);
        candidate.ensureEditable();
        CandidateStatus target = targetStatusOf(status);
        if (target == null && excludedShas == null) {
            throw new InvalidCandidateInputException("status 나 excludedShas 중 하나는 보내야 합니다.");
        }
        List<CandidateCommit> commits = candidateCommits.findByCandidateIdInOrderById(List.of(candidate.getId()));
        boolean cluster = candidate.getSourceType() == CandidateType.COMMIT_CLUSTER;
        Map<Long, CommitSummary> summaries = cluster
                ? commitSummaries.findByIds(commits.stream().map(CandidateCommit::getCommitId).toList())
                : Map.of();
        Set<String> excluded = excludedShas == null ? null : excludedShaSetOf(candidate, excludedShas, summaries);

        if (target == CandidateStatus.EXCLUDED) {
            candidate.exclude();
        } else if (target == CandidateStatus.NEW) {
            candidate.restore();
        }
        if (excluded != null) {
            for (CandidateCommit commit : commits) {
                if (excluded.contains(summaries.get(commit.getCommitId()).sha())) {
                    commit.exclude();
                } else {
                    commit.include();
                }
            }
        }
        candidate.markUserModified();
        Optional<RepositoryReference> repository = repositories.find(candidate.getUserRepositoryId());

        return new EditedCandidate(repository.orElseThrow().publicId(),
                CandidateBoardService.boardCandidateOf(candidate, commits, summaries, repository, null));

    }

    private Candidate lockOwned(UUID candidateId, Long userId) {

        List<Long> connectionIds = ownConnections.connectionIdsOf(userId);
        Optional<Candidate> owned = connectionIds.isEmpty() ? Optional.empty()
                : candidates.findWithLockByPublicIdAndUserRepositoryIdIn(candidateId, connectionIds);
        if (owned.isEmpty()) {
            candidates.findByPublicId(candidateId)
                    .ifPresent(others -> auditLog.denied(userId, AuditAction.EDIT_CANDIDATE, others.getId()));
            throw new CandidateNotFoundException();
        }

        return owned.get();

    }

    /** 카드로 쓰는 일은 이 요청이 아니라 카드 만들기가 하므로 NEW 와 EXCLUDED 만 받고, 받은 글은 고쳐 읽지 않는다 */
    private static CandidateStatus targetStatusOf(String status) {

        if (status == null) {
            return null;
        }
        if (status.equals(CandidateStatus.NEW.name())) {
            return CandidateStatus.NEW;
        }
        if (status.equals(CandidateStatus.EXCLUDED.name())) {
            return CandidateStatus.EXCLUDED;
        }

        throw new InvalidCandidateInputException("status 는 NEW 나 EXCLUDED 만 보낼 수 있습니다.");

    }

    /** 빼는 커밋은 보드가 준 전체 sha 와 같아야 하고, 같은 sha 를 여러 번 보내도 한 번으로 센다 */
    private static Set<String> excludedShaSetOf(Candidate candidate, List<String> excludedShas,
                                                Map<Long, CommitSummary> summaries) {

        if (candidate.getSourceType() != CandidateType.COMMIT_CLUSTER) {
            throw new InvalidCandidateInputException("커밋 묶음 후보만 커밋을 뺄 수 있습니다.");
        }
        Set<String> known = summaries.values().stream().map(CommitSummary::sha).collect(Collectors.toSet());
        Set<String> excluded = new LinkedHashSet<>(excludedShas);
        if (!known.containsAll(excluded)) {
            throw new InvalidCandidateInputException("excludedShas 에 이 후보의 커밋이 아닌 sha 가 있습니다.");
        }

        return excluded;

    }
}
