package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.JobPosting;
import com.gitory.backend.matching.domain.JobPostingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface JobPostingRepository extends JpaRepository<JobPosting, Long> {

    List<JobPosting> findByCompanyKeyAndStatus(String companyKey, JobPostingStatus status);

    List<JobPosting> findByCompanyKey(String companyKey);

    Optional<JobPosting> findByCompanyKeyAndExternalId(String companyKey, String externalId);

    List<JobPosting> findByStatusAndCompanyKeyNotIn(JobPostingStatus status, Collection<String> companyKeys);

    List<JobPosting> findByStatusAndExpiresAtBefore(JobPostingStatus status, Instant now);
}
