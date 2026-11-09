package com.gitory.backend.ingest.infra;

import com.gitory.backend.consent.port.AccountRepositoryCleanupPort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** repository 의 자식(커밋·PR·이슈 등)은 FK 의 ON DELETE CASCADE 로 함께 지워진다 */
@Component
@RequiredArgsConstructor
public class AccountRepositoryCleanupAdapter implements AccountRepositoryCleanupPort {

    private final JdbcTemplate jdbc;

    @Override
    public List<Long> repositoryIdsOf(Long userId) {

        return jdbc.queryForList("SELECT repository_id FROM user_repository WHERE user_id = ?", Long.class, userId);

    }

    @Override
    public void deleteUnlinked(List<Long> repositoryIds) {

        if (repositoryIds.isEmpty()) {
            return;
        }

        jdbc.update("""
                DELETE FROM repository r
                 WHERE r.id = ANY (?)
                   AND NOT EXISTS (SELECT 1 FROM user_repository ur WHERE ur.repository_id = r.id)
                """, ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", repositoryIds.toArray())));

    }
}
