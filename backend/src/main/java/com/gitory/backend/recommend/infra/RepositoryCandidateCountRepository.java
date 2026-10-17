package com.gitory.backend.recommend.infra;

import com.gitory.backend.recommend.domain.Candidate;
import com.gitory.backend.recommend.domain.RepositoryCandidateCount;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface RepositoryCandidateCountRepository extends Repository<Candidate, Long> {

    @Query("""
            SELECT new com.gitory.backend.recommend.domain.RepositoryCandidateCount(c.userRepositoryId, COUNT(c))
            FROM Candidate c
            WHERE c.userRepositoryId IN :userRepositoryIds
            GROUP BY c.userRepositoryId
            """)
    List<RepositoryCandidateCount> countByUserRepositoryIds(@Param("userRepositoryIds") Collection<Long> userRepositoryIds);
}
