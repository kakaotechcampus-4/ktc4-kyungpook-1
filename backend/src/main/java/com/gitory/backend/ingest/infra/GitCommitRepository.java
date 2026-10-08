package com.gitory.backend.ingest.infra;

import com.gitory.backend.ingest.domain.GitCommit;
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
}
