package com.gitory.backend.recommend.infra;

import com.gitory.backend.recommend.domain.CandidateGrouping;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateGroupingRepository extends JpaRepository<CandidateGrouping, Long> {
}
