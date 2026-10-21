package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.JobPostingSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;

public interface JobPostingSourceRepository extends JpaRepository<JobPostingSource, String> {

    @Query("select max(s.lastAttemptAt) from JobPostingSource s")
    Instant findLatestAttemptAt();
}
