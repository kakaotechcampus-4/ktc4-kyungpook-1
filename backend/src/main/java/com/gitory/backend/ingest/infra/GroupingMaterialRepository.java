package com.gitory.backend.ingest.infra;

import com.gitory.backend.ingest.domain.CommitFile;
import com.gitory.backend.ingest.domain.GitCommit;
import com.gitory.backend.ingest.domain.GithubRepo;
import com.gitory.backend.ingest.domain.Issue;
import com.gitory.backend.ingest.domain.PullRequest;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GroupingMaterialRepository extends Repository<GitCommit, Long> {

    @Query("""
            SELECT r FROM ConnectedRepository c JOIN GithubRepo r ON r.id = c.repositoryId
            WHERE c.id = :userRepositoryId
            """)
    Optional<GithubRepo> findRepository(@Param("userRepositoryId") Long userRepositoryId);

    // AI 요청은 작성 시각이 꼭 있어야 해서 작성 시각이 없는 커밋은 읽지 않는다
    // 저장된 값이 같으면 요청도 같아야 지난번 요청과 비교해 AI 를 다시 부르지 않을 수 있어 순서를 정해 읽는다
    @Query("""
            SELECT c FROM GitCommit c
            WHERE c.repositoryId = :repositoryId
              AND LOWER(c.authorLogin) = LOWER(:login)
              AND c.exclusionReason IS NULL
              AND c.authoredAt IS NOT NULL
            ORDER BY c.authoredAt, c.sha
            """)
    List<GitCommit> findOwnCommits(@Param("repositoryId") Long repositoryId, @Param("login") String login);

    // 저장한 순서가 GitHub 가 준 순서라 그 순서대로 읽는다
    @Query("SELECT f FROM CommitFile f WHERE f.commitId IN :commitIds ORDER BY f.id")
    List<CommitFile> findFiles(@Param("commitIds") Collection<Long> commitIds);

    @Query("SELECT p FROM PullRequest p WHERE p.repositoryId = :repositoryId")
    List<PullRequest> findPullRequests(@Param("repositoryId") Long repositoryId);

    @Query("SELECT i FROM Issue i WHERE i.repositoryId = :repositoryId")
    List<Issue> findIssues(@Param("repositoryId") Long repositoryId);
}
