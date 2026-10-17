package com.gitory.backend.ingest.infra;

import com.gitory.backend.ingest.domain.ConnectedRepository;
import com.gitory.backend.ingest.domain.MeRepositoryTotals;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MeRepositoryStatsRepository extends Repository<ConnectedRepository, Long> {

    @Query("SELECT c.id FROM ConnectedRepository c WHERE c.userId = :userId")
    List<Long> findIdsByUserId(@Param("userId") Long userId);

    @Query("""
            SELECT new com.gitory.backend.ingest.domain.MeRepositoryTotals(COUNT(c.lastAnalyzedAt), MAX(c.lastAnalyzedAt))
            FROM ConnectedRepository c WHERE c.userId = :userId
            """)
    MeRepositoryTotals totalsOf(@Param("userId") Long userId);
}
