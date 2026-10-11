package com.gitory.backend.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 후보와 후보 커밋 행을 SQL 로 만든다
 * 마이그레이션 테스트가 V14 이전 표에도 쓸 수 있게 V1 부터 있던 칸만 채운다
 */
public final class CandidateFixtures {

    private final JdbcTemplate jdbc;

    public CandidateFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long insertCandidate(Long userRepositoryId, String sourceType, Integer githubPrNumber, String status) {

        return jdbc.queryForObject(
                "INSERT INTO candidate (user_repository_id, source_type, github_pr_number, title, reason_text, status) VALUES (?, ?, ?, '후보', '이유', ?) RETURNING id",
                Long.class, userRepositoryId, sourceType, githubPrNumber, status);

    }

    public Long insertCandidateCommit(Long candidateId, Long commitId) {

        return jdbc.queryForObject(
                "INSERT INTO candidate_commit (candidate_id, commit_id, origin) VALUES (?, ?, 'AI') RETURNING id",
                Long.class, candidateId, commitId);

    }
}
