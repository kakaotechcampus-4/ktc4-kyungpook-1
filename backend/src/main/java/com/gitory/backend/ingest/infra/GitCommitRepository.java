package com.gitory.backend.ingest.infra;

import com.gitory.backend.ingest.domain.GitCommit;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GitCommitRepository extends JpaRepository<GitCommit, Long> {

    Optional<GitCommit> findByRepositoryIdAndSha(Long repositoryId, String sha);

    List<GitCommit> findByCollectionRunIdAndExcludedFalse(Long collectionRunId);

    @Query("""
            SELECT c.sha FROM GitCommit c, CollectionRun r
            WHERE c.collectionRunId = r.id AND r.userRepositoryId = :userRepositoryId
            ORDER BY c.id
            """)
    List<String> findAllShasByUserRepositoryId(@Param("userRepositoryId") Long userRepositoryId);

    @Query("SELECT c.sha FROM GitCommit c WHERE c.repositoryId = :repositoryId AND c.sha IN :shas")
    List<String> findStoredShas(@Param("repositoryId") Long repositoryId, @Param("shas") Collection<String> shas);

    @Query("""
            SELECT c FROM GitCommit c
            WHERE c.repositoryId = :repositoryId
              AND LOWER(c.authorLogin) = LOWER(:login)
              AND (c.exclusionReason IS NULL
                   OR c.exclusionReason = com.gitory.backend.ingest.domain.ExclusionReason.NOT_OWN)
              AND (LOWER(c.message) LIKE :messagePattern ESCAPE '\\' OR c.sha LIKE :shaPrefix ESCAPE '\\')
            ORDER BY c.authoredAt DESC
            """)
    List<GitCommit> searchOwn(@Param("repositoryId") Long repositoryId, @Param("login") String login,
                              @Param("messagePattern") String messagePattern, @Param("shaPrefix") String shaPrefix,
                              Limit limit);
}
