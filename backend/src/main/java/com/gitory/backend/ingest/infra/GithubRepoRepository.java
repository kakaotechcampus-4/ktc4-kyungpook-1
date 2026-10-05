package com.gitory.backend.ingest.infra;

import com.gitory.backend.ingest.domain.GithubRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface GithubRepoRepository extends JpaRepository<GithubRepo, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO repository (github_repo_id, owner_login, name, visibility, primary_language, default_branch,
                                    github_created_at, github_pushed_at)
            VALUES (:githubRepoId, :ownerLogin, :name, :visibility, :primaryLanguage, :defaultBranch,
                    :githubCreatedAt, :githubPushedAt)
            ON CONFLICT (github_repo_id) DO UPDATE
                SET owner_login       = EXCLUDED.owner_login,
                    name              = EXCLUDED.name,
                    visibility        = EXCLUDED.visibility,
                    primary_language  = EXCLUDED.primary_language,
                    default_branch    = EXCLUDED.default_branch,
                    github_created_at = EXCLUDED.github_created_at,
                    github_pushed_at  = EXCLUDED.github_pushed_at
            """, nativeQuery = true)
    void upsert(@Param("githubRepoId") long githubRepoId,
                @Param("ownerLogin") String ownerLogin,
                @Param("name") String name,
                @Param("visibility") String visibility,
                @Param("primaryLanguage") String primaryLanguage,
                @Param("defaultBranch") String defaultBranch,
                @Param("githubCreatedAt") Instant githubCreatedAt,
                @Param("githubPushedAt") Instant githubPushedAt);
}
