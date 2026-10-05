package com.gitory.backend.ingest.infra;

import com.gitory.backend.ingest.domain.ConnectedRepository;
import com.gitory.backend.ingest.domain.RepositorySummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConnectedRepositoryRepository extends JpaRepository<ConnectedRepository, Long> {

    Optional<ConnectedRepository> findByPublicIdAndUserId(UUID publicId, Long userId);

    @Modifying
    @Query(value = """
            INSERT INTO user_repository (user_id, repository_id)
            SELECT :userId, id FROM repository WHERE github_repo_id = :githubRepoId
            ON CONFLICT (user_id, repository_id) DO NOTHING
            """, nativeQuery = true)
    void connectIfAbsent(@Param("userId") Long userId, @Param("githubRepoId") long githubRepoId);

    @Query("""
            SELECT new com.gitory.backend.ingest.domain.RepositorySummary(
                c.publicId, r.ownerLogin, r.name, r.primaryLanguage,
                r.githubCreatedAt, COALESCE(r.githubPushedAt, r.githubCreatedAt), c.lastAnalyzedAt)
            FROM ConnectedRepository c JOIN GithubRepo r ON r.id = c.repositoryId
            WHERE c.userId = :userId AND r.githubRepoId IN :githubRepoIds
            ORDER BY r.ownerLogin, r.name
            """)
    List<RepositorySummary> findSummaries(@Param("userId") Long userId,
                                          @Param("githubRepoIds") Collection<Long> githubRepoIds);
}
