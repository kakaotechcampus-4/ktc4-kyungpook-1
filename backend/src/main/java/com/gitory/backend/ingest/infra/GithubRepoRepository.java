package com.gitory.backend.ingest.infra;

import com.gitory.backend.ingest.domain.GithubRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GithubRepoRepository extends JpaRepository<GithubRepo, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO repository (github_repo_id, owner_login, name, visibility, primary_language, default_branch)
            VALUES (:githubRepoId, :ownerLogin, :name, :visibility, :primaryLanguage, :defaultBranch)
            ON CONFLICT (github_repo_id) DO UPDATE
                SET owner_login      = EXCLUDED.owner_login,
                    name             = EXCLUDED.name,
                    visibility       = EXCLUDED.visibility,
                    primary_language = EXCLUDED.primary_language,
                    default_branch   = EXCLUDED.default_branch
            """, nativeQuery = true)
    void upsert(@Param("githubRepoId") long githubRepoId,
                @Param("ownerLogin") String ownerLogin,
                @Param("name") String name,
                @Param("visibility") String visibility,
                @Param("primaryLanguage") String primaryLanguage,
                @Param("defaultBranch") String defaultBranch);
}
