package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.JobPosting;
import com.gitory.backend.matching.domain.JobPostingContentStatus;
import com.gitory.backend.matching.domain.JobPostingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobPostingRepository extends JpaRepository<JobPosting, Long> {

    List<JobPosting> findByCompanyKeyAndStatus(String companyKey, JobPostingStatus status);

    List<JobPosting> findByCompanyKey(String companyKey);

    List<JobPosting> findByStatusAndContentStatusOrderById(JobPostingStatus status, JobPostingContentStatus contentStatus);

    Optional<JobPosting> findByCompanyKeyAndExternalId(String companyKey, String externalId);

    List<JobPosting> findByStatusAndCompanyKeyNotIn(JobPostingStatus status, Collection<String> companyKeys);

    List<JobPosting> findByStatusAndExpiresAtBefore(JobPostingStatus status, Instant now);

    // 공고와 등급 조건을 한 쿼리로 읽어야 새벽 조건 뽑기가 커밋되는 사이에 둘이 어긋나지 않는다
    // 조건을 뽑은 활성 공고 중 마감 전인 것만, 필수·우대 조건을 한 줄씩 읽고 추천·상세와 같은 공고를 다루도록 색인은 보지 않는다
    @Query("""
            SELECT new com.gitory.backend.matching.infra.MatchableCondition(p, c.id, c.type)
            FROM JobPosting p JOIN JobPostingCriterion c ON c.jobPostingId = p.id
            WHERE p.status = com.gitory.backend.matching.domain.JobPostingStatus.ACTIVE
              AND p.contentStatus = com.gitory.backend.matching.domain.JobPostingContentStatus.READY
              AND (p.expiresAt IS NULL OR p.expiresAt > :now)
              AND c.type IN (com.gitory.backend.matching.domain.CriterionType.REQUIRED,
                             com.gitory.backend.matching.domain.CriterionType.PREFERRED)
            ORDER BY p.id, c.seq
            """)
    List<MatchableCondition> findMatchableConditions(@Param("now") Instant now);

    // 상세도 목록과 같은 공고 조건에 담당 업무 조건까지 한 쿼리로 읽어, 목록에 없는 공고는 한 줄도 나오지 않게 한다
    @Query("""
            SELECT new com.gitory.backend.matching.infra.PostingCondition(p, c)
            FROM JobPosting p JOIN JobPostingCriterion c ON c.jobPostingId = p.id
            WHERE p.publicId = :publicId
              AND p.status = com.gitory.backend.matching.domain.JobPostingStatus.ACTIVE
              AND p.contentStatus = com.gitory.backend.matching.domain.JobPostingContentStatus.READY
              AND (p.expiresAt IS NULL OR p.expiresAt > :now)
              AND EXISTS (SELECT 1 FROM JobPostingCriterion g
                          WHERE g.jobPostingId = p.id
                            AND g.type IN (com.gitory.backend.matching.domain.CriterionType.REQUIRED,
                                           com.gitory.backend.matching.domain.CriterionType.PREFERRED))
            ORDER BY c.seq
            """)
    List<PostingCondition> findMatchableDetail(@Param("publicId") UUID publicId, @Param("now") Instant now);

    // 매칭 1단계는 색인을 쓰지 않아 색인 해시를 보지 않고, 조건을 뽑은 마감 전 공고의 필수·우대 조건을 한 쿼리로 읽는다
    @Query("""
            SELECT new com.gitory.backend.matching.infra.PostingCondition(p, c)
            FROM JobPosting p JOIN JobPostingCriterion c ON c.jobPostingId = p.id
            WHERE p.status = com.gitory.backend.matching.domain.JobPostingStatus.ACTIVE
              AND p.contentStatus = com.gitory.backend.matching.domain.JobPostingContentStatus.READY
              AND (p.expiresAt IS NULL OR p.expiresAt > :now)
              AND c.type IN (com.gitory.backend.matching.domain.CriterionType.REQUIRED,
                             com.gitory.backend.matching.domain.CriterionType.PREFERRED)
            ORDER BY p.id, c.seq
            """)
    List<PostingCondition> findShortlistConditions(@Param("now") Instant now);
}
