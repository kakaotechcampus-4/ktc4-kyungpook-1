package com.gitory.backend.recommend.domain;

import com.gitory.backend.ingest.domain.CommitSummaryService;
import com.gitory.backend.recommend.infra.CandidateCommitRepository;
import com.gitory.backend.recommend.infra.CandidateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 서버가 스스로 다시 읽은 뒤 카드로 만들 후보를 골라, 이전 분석 뒤에 생긴 일이 든 후보만 후보 표를 직접 읽지 않고 얻게 한다 */
@Service
@RequiredArgsConstructor
public class RefreshCandidatePicker {

    private final CandidateRepository candidates;
    private final CandidateCommitRepository candidateCommits;
    private final CommitSummaryService commits;

    /**
     * 새 후보 가운데 사용자가 빼지 않은 커밋 하나라도 previousAnalyzedAt 뒤에 썼거나 이번 수집에서 처음 저장된 후보를 점수가 높은 순으로 limit 개까지 돌려준다
     * 카드감이 낮지 않은 후보가 하나라도 있으면 낮은 후보는 쓰지 않는다
     */
    @Transactional(readOnly = true)
    public List<UUID> pick(Long userRepositoryId, Instant previousAnalyzedAt, Long collectionRunId, int limit) {

        List<Candidate> news = candidates.findByUserRepositoryId(userRepositoryId).stream()
                .filter(candidate -> candidate.getStatus() == CandidateStatus.NEW)
                .sorted(Candidate.BOARD_ORDER)
                .toList();
        if (news.isEmpty()) {
            return List.of();
        }

        List<CandidateCommit> included = candidateCommits.findByCandidateIdInAndIncludedTrue(
                news.stream().map(Candidate::getId).toList());
        Set<Long> newCommitIds = commits.newIds(included.stream().map(CandidateCommit::getCommitId).toList(),
                previousAnalyzedAt, collectionRunId);
        Set<Long> withNewWork = included.stream()
                .filter(commit -> newCommitIds.contains(commit.getCommitId()))
                .map(CandidateCommit::getCandidateId)
                .collect(Collectors.toSet());
        List<Candidate> fresh = news.stream().filter(candidate -> withNewWork.contains(candidate.getId())).toList();
        List<Candidate> worthy = fresh.stream().filter(candidate -> !candidate.isLowCardWorth()).toList();

        return (worthy.isEmpty() ? fresh : worthy).stream()
                .limit(limit)
                .map(Candidate::getPublicId)
                .toList();

    }
}
