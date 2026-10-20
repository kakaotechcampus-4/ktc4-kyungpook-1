package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.JobPostingSource;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobPostingSourceRepository extends JpaRepository<JobPostingSource, String> {
}
