package com.gitory.backend.ingest.infra;

import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.consent.port.GithubCollectionTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 호출 범위의 내부 연결 ID를 실제 GitHub 저장소 정보로 해석한다. */
@Repository
@RequiredArgsConstructor
public class CollectionTargetLookup {

    private final JdbcTemplate jdbc;

    public Target find(Long userRepositoryId, List<String> branches) {
        List<Target> targets = jdbc.query("""
                SELECT ur.user_id, r.github_repo_id, r.owner_login, r.name, r.default_branch
                  FROM user_repository ur
                  JOIN repository r ON r.id = ur.repository_id
                  JOIN users u ON u.id = ur.user_id
                 WHERE ur.id = ? AND u.deleted_at IS NULL
                """, (row, number) -> new Target(row.getLong("user_id"),
                new GithubCollectionTarget(userRepositoryId,
                        new GithubCollectionTarget.Repository(row.getLong("github_repo_id"),
                                row.getString("owner_login"), row.getString("name"),
                                row.getString("default_branch")), branches)), userRepositoryId);
        if (targets.size() != 1) {
            throw new AiClientException("COLLECTION_TARGET_NOT_FOUND", false, null);
        }
        Target target = targets.getFirst();
        var repository = target.collection().repository();
        if (repository.githubRepoId() <= 0 || empty(repository.ownerLogin())
                || empty(repository.name()) || empty(repository.defaultBranch())) {
            // 브랜치를 main 등으로 추측해서 잘못된 활동을 수집하지 않는다.
            throw new AiClientException("COLLECTION_TARGET_INCOMPLETE", false, null);
        }
        return target;
    }

    private static boolean empty(String value) { return value == null || value.isBlank(); }

    public record Target(Long userId, GithubCollectionTarget collection) { }
}
