package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.JobPostingCriterion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobPostingCriterionRepository extends JpaRepository<JobPostingCriterion, Long> {

    // 파생 삭제는 같은 트랜잭션에서 새 조건 INSERT 뒤에 실행돼 UNIQUE(공고, seq)에 걸리므로 바로 실행되는 쿼리로 지운다
    @Modifying
    @Query("delete from JobPostingCriterion c where c.jobPostingId = :postingId")
    void deleteAllByPostingId(@Param("postingId") Long postingId);
}
