package com.gitory.backend.recommend.infra;

import com.gitory.backend.recommend.domain.Candidate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateRepository extends JpaRepository<Candidate, Long> {
}
