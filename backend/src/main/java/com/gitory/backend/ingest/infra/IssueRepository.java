package com.gitory.backend.ingest.infra;

import com.gitory.backend.ingest.domain.Issue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface IssueRepository extends JpaRepository<Issue, Long> {

    // 다른 Job 이 같은 이슈를 먼저 저장했어도 유니크 위반 없이 이번 수집 값으로 덮어쓴다
    @Modifying
    @Query(value = """
            INSERT INTO issue (repository_id, collection_run_id, github_issue_number, title, body_excerpt, state,
                               author_login, labels, opened_at, closed_at)
            VALUES (:repositoryId, :collectionRunId, :number, :title, :bodyExcerpt, :state, :authorLogin, :labels,
                    :openedAt, :closedAt)
            ON CONFLICT (repository_id, github_issue_number) DO UPDATE
                SET collection_run_id = EXCLUDED.collection_run_id,
                    title             = EXCLUDED.title,
                    body_excerpt      = EXCLUDED.body_excerpt,
                    state             = EXCLUDED.state,
                    author_login      = EXCLUDED.author_login,
                    labels            = EXCLUDED.labels,
                    opened_at         = EXCLUDED.opened_at,
                    closed_at         = EXCLUDED.closed_at
            """, nativeQuery = true)
    void upsert(@Param("repositoryId") Long repositoryId,
                @Param("collectionRunId") Long collectionRunId,
                @Param("number") int number,
                @Param("title") String title,
                @Param("bodyExcerpt") String bodyExcerpt,
                @Param("state") String state,
                @Param("authorLogin") String authorLogin,
                @Param("labels") String[] labels,
                @Param("openedAt") Instant openedAt,
                @Param("closedAt") Instant closedAt);
}
