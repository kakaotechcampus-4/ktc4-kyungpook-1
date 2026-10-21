package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.JobPostingCriterion;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobPostingCriterionRepository extends JpaRepository<JobPostingCriterion, Long> {
}
