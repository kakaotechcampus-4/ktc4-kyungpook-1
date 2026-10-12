package com.gitory.backend.recommend.infra;

import com.gitory.backend.recommend.domain.CandidateCommit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface CandidateCommitRepository extends JpaRepository<CandidateCommit, Long> {

    List<CandidateCommit> findByCandidateIdInAndIncludedTrue(Collection<Long> candidateIds);
}
