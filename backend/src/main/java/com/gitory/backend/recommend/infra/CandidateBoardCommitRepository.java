package com.gitory.backend.recommend.infra;

import com.gitory.backend.recommend.domain.CandidateCommit;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;

public interface CandidateBoardCommitRepository extends Repository<CandidateCommit, Long> {

    // 사용자가 뺀 커밋도 보드에 보여 주므로 포함 여부와 상관없이 모두 읽는다
    List<CandidateCommit> findByCandidateIdInOrderById(Collection<Long> candidateIds);
}
