package com.gitory.backend.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * 테스트가 쓸 부모 행을 만든다. analysis_job 은 users · user_repository 를 참조해서 그 행이 먼저 있어야 한다.
 */
public final class TestFixtures {

    private final JdbcTemplate jdbc;

    public TestFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void clear() {
        jdbc.execute("TRUNCATE users, repository CASCADE");
    }

    public Long insertUser(Long githubUserId, String login) {
        return jdbc.queryForObject(
                "INSERT INTO users (github_user_id, github_login) VALUES (?, ?) RETURNING id",
                Long.class, githubUserId, login);
    }

    public Long insertRepository(Long githubRepoId, String ownerLogin, String name) {
        return jdbc.queryForObject(
                "INSERT INTO repository (github_repo_id, owner_login, name, visibility) VALUES (?, ?, ?, 'PUBLIC') RETURNING id",
                Long.class, githubRepoId, ownerLogin, name);
    }

    public Long insertUserRepository(Long userId, Long repositoryId) {
        return jdbc.queryForObject(
                "INSERT INTO user_repository (user_id, repository_id) VALUES (?, ?) RETURNING id",
                Long.class, userId, repositoryId);
    }

    public Long insertUserRepository(UUID publicId, Long userId, Long repositoryId) {
        return jdbc.queryForObject(
                "INSERT INTO user_repository (public_id, user_id, repository_id) VALUES (?, ?, ?) RETURNING id",
                Long.class, publicId, userId, repositoryId);
    }
}
