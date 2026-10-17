package com.gitory.backend.recommend.domain;

import com.gitory.backend.ingest.domain.CommitSummaryService;
import com.gitory.backend.recommend.infra.CandidateCommitRepository;
import com.gitory.backend.recommend.infra.CandidateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** 초안 Job 이 후보 표를 직접 읽지 않고, 후보를 AI 에 보낼 종류·PR 번호와 사용자가 빼지 않은 커밋을 얻게 한다 */
@Service
@RequiredArgsConstructor
public class CandidateDraftSourceService {

    private final CandidateRepository candidates;
    private final CandidateCommitRepository candidateCommits;
    private final CommitSummaryService commits;

    /** 커밋은 변경량이 큰 순서로 commitLimit 개까지이고, 후보가 없으면 빈 값을 돌려준다 */
    @Transactional(readOnly = true)
    public Optional<CandidateDraftSource> load(Long candidateId, int commitLimit) {

        return candidates.findById(candidateId).map(candidate -> new CandidateDraftSource(candidate.getPublicId(),
                candidate.getSourceType(), candidate.getGithubPrNumber(), candidate.getSourceRef(),
                commits.largestFirst(includedCommitIds(candidateId), commitLimit)));

    }

    private List<Long> includedCommitIds(Long candidateId) {

        return candidateCommits.findByCandidateIdInAndIncludedTrue(List.of(candidateId)).stream()
                .map(CandidateCommit::getCommitId)
                .toList();

    }
}
