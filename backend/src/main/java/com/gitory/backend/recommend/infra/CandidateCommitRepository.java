package com.gitory.backend.recommend.infra;

import com.gitory.backend.recommend.domain.CandidateCommit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateCommitRepository extends JpaRepository<CandidateCommit, Long> {
}
