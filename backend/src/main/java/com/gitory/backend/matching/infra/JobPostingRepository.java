package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.JobPosting;
import com.gitory.backend.matching.domain.JobPostingContentStatus;
import com.gitory.backend.matching.domain.JobPostingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface JobPostingRepository extends JpaRepository<JobPosting, Long> {

    List<JobPosting> findByCompanyKeyAndStatus(String companyKey, JobPostingStatus status);

    List<JobPosting> findByCompanyKey(String companyKey);

    List<JobPosting> findByStatusAndContentStatusOrderById(JobPostingStatus status, JobPostingContentStatus contentStatus);

    Optional<JobPosting> findByCompanyKeyAndExternalId(String companyKey, String externalId);

    List<JobPosting> findByStatusAndCompanyKeyNotIn(JobPostingStatus status, Collection<String> companyKeys);

    List<JobPosting> findByStatusAndExpiresAtBefore(JobPostingStatus status, Instant now);

    @Modifying
    @Query("""
            update JobPosting p set p.indexedContentHash = null
            where p.indexedContentHash is not null
              and (p.status <> :status or p.contentStatus <> :contentStatus)
            """)
    int clearIndexedContentHashExcept(@Param("status") JobPostingStatus status,
                                      @Param("contentStatus") JobPostingContentStatus contentStatus);
}
