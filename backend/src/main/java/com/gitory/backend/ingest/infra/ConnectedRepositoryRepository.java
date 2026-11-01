package com.gitory.backend.ingest.infra;

import com.gitory.backend.ingest.domain.ConnectedRepository;
import com.gitory.backend.ingest.domain.ContributionTarget;
import com.gitory.backend.ingest.domain.RepositoryReference;
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

    // 소유자를 보지 않으므로 남의 저장소인지 가려 거절 기록을 남길 때만 쓴다
    Optional<ConnectedRepository> findByPublicId(UUID publicId);

    boolean existsByUserIdAndCountedAtIsNotNull(Long userId);

    @Query("""
            SELECT new com.gitory.backend.ingest.domain.RepositoryReference(c.publicId, r.ownerLogin, r.name)
            FROM ConnectedRepository c JOIN GithubRepo r ON r.id = c.repositoryId
            WHERE c.id = :id
            """)
    Optional<RepositoryReference> findReference(@Param("id") Long id);

    @Query("""
            SELECT c.id AS id, c.publicId AS publicId, r.ownerLogin AS ownerLogin, r.name AS name
            FROM ConnectedRepository c JOIN GithubRepo r ON r.id = c.repositoryId
            WHERE c.id IN :ids
            """)
    List<ReferenceRow> findReferences(@Param("ids") Collection<Long> ids);

    @Modifying
    @Query(value = """
            INSERT INTO user_repository (user_id, repository_id)
            SELECT :userId, id FROM repository WHERE github_repo_id = :githubRepoId
            ON CONFLICT (user_id, repository_id) DO NOTHING
            """, nativeQuery = true)
    void connectIfAbsent(@Param("userId") Long userId, @Param("githubRepoId") long githubRepoId);

    // 두 목록 쿼리 모두 커밋이 없는 빈 저장소와 내 프로필 README 저장소(login/login)는 카드로 만들 경험이 없어 뺀다
    // 아직 못 센 저장소는 빈 저장소인지 알 수 없어 그대로 보여 준다
    @Query("""
            SELECT new com.gitory.backend.ingest.domain.RepositorySummary(
                c.id, c.publicId, r.ownerLogin, r.name, r.primaryLanguage,
                r.githubCreatedAt, COALESCE(r.githubPushedAt, r.githubCreatedAt), c.lastAnalyzedAt, c.countedAt,
                c.commitCount, c.ownCommitCount, c.prCount, c.ownPrCount, c.reviewedPrCount)
            FROM ConnectedRepository c JOIN GithubRepo r ON r.id = c.repositoryId
            WHERE c.userId = :userId AND r.githubRepoId IN :githubRepoIds
              AND (c.countedAt IS NULL OR c.commitCount > 0)
              AND NOT (LOWER(r.ownerLogin) = LOWER(:login) AND LOWER(r.name) = LOWER(:login))
            ORDER BY r.ownerLogin, r.name
            """)
    List<RepositorySummary> findSummaries(@Param("userId") Long userId,
                                          @Param("githubRepoIds") Collection<Long> githubRepoIds,
                                          @Param("login") String login);

    @Query("""
            SELECT new com.gitory.backend.ingest.domain.RepositorySummary(
                c.id, c.publicId, r.ownerLogin, r.name, r.primaryLanguage,
                r.githubCreatedAt, COALESCE(r.githubPushedAt, r.githubCreatedAt), c.lastAnalyzedAt, c.countedAt,
                c.commitCount, c.ownCommitCount, c.prCount, c.ownPrCount, c.reviewedPrCount)
            FROM ConnectedRepository c JOIN GithubRepo r ON r.id = c.repositoryId
            WHERE c.userId = :userId
              AND (c.countedAt IS NULL OR c.commitCount > 0)
              AND NOT (LOWER(r.ownerLogin) = LOWER(:login) AND LOWER(r.name) = LOWER(:login))
            ORDER BY r.ownerLogin, r.name
            """)
    List<RepositorySummary> findAllSummaries(@Param("userId") Long userId, @Param("login") String login);

    @Query("""
            SELECT new com.gitory.backend.ingest.domain.RepositorySummary(
                c.id, c.publicId, r.ownerLogin, r.name, r.primaryLanguage,
                r.githubCreatedAt, COALESCE(r.githubPushedAt, r.githubCreatedAt), c.lastAnalyzedAt, c.countedAt,
                c.commitCount, c.ownCommitCount, c.prCount, c.ownPrCount, c.reviewedPrCount)
            FROM ConnectedRepository c JOIN GithubRepo r ON r.id = c.repositoryId
            WHERE c.publicId = :publicId AND c.userId = :userId
            """)
    Optional<RepositorySummary> findSummary(@Param("publicId") UUID publicId, @Param("userId") Long userId);

    // 한 번에 다 세지 못할 때 안 센 저장소가 0 으로 남지 않게 먼저 세고, 그다음 최근에 활동한 저장소를 센다
    @Query("""
            SELECT new com.gitory.backend.ingest.domain.ContributionTarget(c.id, r.githubRepoId, r.ownerLogin, r.name)
            FROM ConnectedRepository c JOIN GithubRepo r ON r.id = c.repositoryId
            WHERE c.userId = :userId AND r.githubRepoId IN :githubRepoIds
              AND (c.countedAt IS NULL OR c.countedAt < r.githubPushedAt)
            ORDER BY CASE WHEN c.countedAt IS NULL THEN 0 ELSE 1 END,
                     COALESCE(r.githubPushedAt, r.githubCreatedAt) DESC NULLS LAST, c.id
            """)
    List<ContributionTarget> findUncounted(@Param("userId") Long userId,
                                           @Param("githubRepoIds") Collection<Long> githubRepoIds);

    interface ReferenceRow {

        Long getId();

        UUID getPublicId();

        String getOwnerLogin();

        String getName();
    }
}
