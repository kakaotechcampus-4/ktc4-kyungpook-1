package com.gitory.backend.ingest.infra;

import com.gitory.backend.ingest.domain.CollectionCounts;
import com.gitory.backend.ingest.domain.CollectionRun;
import com.gitory.backend.ingest.domain.CollectionRunSummary;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 저장소 연결의 공개 id 와 소유자로 찾아 남의 연결 행은 읽지 않으며, 커밋은 연결이 가리키는 저장소의 행만 읽는다 */
public interface CollectionStatsRepository extends Repository<CollectionRun, Long> {

    @Query("""
            SELECT new com.gitory.backend.ingest.domain.CollectionRunSummary(r.partial, r.prsCollected)
            FROM CollectionRun r JOIN ConnectedRepository c ON c.id = r.userRepositoryId
            WHERE c.publicId = :publicId AND c.userId = :userId
            ORDER BY r.collectedAt DESC, r.id DESC
            """)
    List<CollectionRunSummary> findLatestRuns(@Param("publicId") UUID publicId, @Param("userId") Long userId,
                                              Limit limit);

    @Query("""
            SELECT new com.gitory.backend.ingest.domain.CollectionCounts(
                c.countedAt, c.commitCount, c.ownCommitCount, c.prCount, c.ownPrCount, c.reviewedPrCount)
            FROM ConnectedRepository c
            WHERE c.publicId = :publicId AND c.userId = :userId
            """)
    Optional<CollectionCounts> findCounts(@Param("publicId") UUID publicId, @Param("userId") Long userId);

    @Query("""
            SELECT COUNT(g) FROM GitCommit g JOIN ConnectedRepository c ON c.repositoryId = g.repositoryId
            WHERE c.publicId = :publicId AND c.userId = :userId
            """)
    long countStoredCommits(@Param("publicId") UUID publicId, @Param("userId") Long userId);

    @Query("""
            SELECT COUNT(g) FROM GitCommit g JOIN ConnectedRepository c ON c.repositoryId = g.repositoryId
            WHERE c.publicId = :publicId AND c.userId = :userId AND g.authorLogin IS NULL
            """)
    long countWithoutAuthorLogin(@Param("publicId") UUID publicId, @Param("userId") Long userId);

    @Query("""
            SELECT COALESCE(g.message, '') FROM GitCommit g JOIN ConnectedRepository c ON c.repositoryId = g.repositoryId
            WHERE c.publicId = :publicId AND c.userId = :userId
              AND LOWER(g.authorLogin) = LOWER(:login) AND g.exclusionReason IS NULL
            """)
    List<String> findOwnMessages(@Param("publicId") UUID publicId, @Param("userId") Long userId,
                                 @Param("login") String login);
}
