package com.gitory.backend.ingest.infra;

import com.gitory.backend.ingest.domain.GitCommit;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GitCommitRepository extends JpaRepository<GitCommit, Long> {

    Optional<GitCommit> findByRepositoryIdAndSha(Long repositoryId, String sha);

    List<GitCommit> findByCollectionRunIdAndExcludedFalse(Long collectionRunId);

    long countByRepositoryId(Long repositoryId);

    // sha 칸은 CHAR(40) 이라 짧은 sha 는 뒤에 공백이 붙어 나오므로 지운다
    @Query("""
            SELECT TRIM(TRAILING FROM c.sha) FROM GitCommit c
            WHERE c.repositoryId = :repositoryId AND c.issueNumbers IS NOT NULL
            ORDER BY c.id DESC
            """)
    List<String> findKnownShas(@Param("repositoryId") Long repositoryId, Limit limit);

    @Query("SELECT c.sha FROM GitCommit c WHERE c.repositoryId = :repositoryId AND c.sha IN :shas")
    List<String> findStoredShas(@Param("repositoryId") Long repositoryId, @Param("shas") Collection<String> shas);

    // 다른 Job 이 같은 저장소의 같은 커밋을 먼저 저장했으면 건너뛰어, 동시에 저장해도 유니크 위반으로 실패하지 않는다
    @Modifying
    @Query(value = """
            INSERT INTO git_commit (repository_id, collection_run_id, sha, author_login, author_name, message,
                                    authored_at, additions, deletions, changed_files, parent_count,
                                    is_excluded, exclusion_reason)
            VALUES (:#{#commit.repositoryId}, :#{#commit.collectionRunId}, :#{#commit.sha},
                    :#{#commit.authorLogin}, :#{#commit.authorName}, :#{#commit.message},
                    :#{#commit.authoredAt}, :#{#commit.additions}, :#{#commit.deletions},
                    :#{#commit.changedFiles}, :#{#commit.parentCount},
                    :#{#commit.excluded}, :#{#commit.exclusionReason?.name()})
            ON CONFLICT (repository_id, sha) DO NOTHING
            """, nativeQuery = true)
    void insertIfAbsent(@Param("commit") GitCommit commit);

    // sha 칸은 CHAR(40) 이라 40자보다 짧은 sha 는 뒤에 공백이 붙어 나오므로, 수집 결과의 sha 와 같게 비교되도록 지운다
    @Query("""
            SELECT TRIM(TRAILING FROM c.sha) FROM GitCommit c
            WHERE c.repositoryId = :repositoryId AND c.sha IN :shas AND c.issueNumbers IS NULL
            """)
    List<String> findUnfilledShas(@Param("repositoryId") Long repositoryId, @Param("shas") Collection<String> shas);

    // 다른 Job 이 먼저 채운 커밋은 고치지 않고 id 도 돌려주지 않아, 바뀐 파일은 채운 Job 한 곳에서만 저장된다
    @Query(value = """
            UPDATE git_commit SET issue_numbers = :issueNumbers
            WHERE repository_id = :repositoryId AND sha = :sha AND issue_numbers IS NULL
            RETURNING id
            """, nativeQuery = true)
    Optional<Long> fillIssueNumbers(@Param("repositoryId") Long repositoryId, @Param("sha") String sha,
                                    @Param("issueNumbers") Integer[] issueNumbers);

    @Query("""
            SELECT c FROM GitCommit c
            WHERE c.repositoryId = :repositoryId
              AND LOWER(c.authorLogin) = LOWER(:login)
              AND c.exclusionReason IS NULL
              AND (LOWER(c.message) LIKE :messagePattern ESCAPE '\\' OR c.sha LIKE :shaPrefix ESCAPE '\\')
            ORDER BY c.authoredAt DESC
            """)
    List<GitCommit> searchOwn(@Param("repositoryId") Long repositoryId, @Param("login") String login,
                              @Param("messagePattern") String messagePattern, @Param("shaPrefix") String shaPrefix,
                              Limit limit);
}
