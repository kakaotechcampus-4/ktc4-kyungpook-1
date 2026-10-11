package com.gitory.backend.ingest.infra;

import com.gitory.backend.ingest.domain.CommitFile;
import com.gitory.backend.ingest.domain.GitCommit;
import com.gitory.backend.ingest.domain.ReviewCommentCount;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** 다른 모듈에 내어 주는 저장된 수집 결과 조회를 모았고, 커밋·리뷰 댓글은 저장소 연결이 가리키는 저장소의 행만 읽는다 */
public interface StoredActivityRepository extends Repository<GitCommit, Long> {

    @Query("""
            SELECT c FROM GitCommit c JOIN ConnectedRepository ur ON ur.repositoryId = c.repositoryId
            WHERE ur.id = :userRepositoryId AND c.sha IN :shas
            """)
    List<GitCommit> findCommits(@Param("userRepositoryId") Long userRepositoryId,
                                @Param("shas") Collection<String> shas);

    @Query("SELECT f FROM CommitFile f WHERE f.commitId IN :commitIds ORDER BY f.id")
    List<CommitFile> findFiles(@Param("commitIds") Collection<Long> commitIds);

    @Query("SELECT TRIM(TRAILING FROM c.sha) FROM GitCommit c WHERE c.id IN :commitIds")
    List<String> findShas(@Param("commitIds") Collection<Long> commitIds);

    @Query("""
            SELECT new com.gitory.backend.ingest.domain.ReviewCommentCount(rc.githubPrNumber, COUNT(rc))
            FROM PrReviewComment rc JOIN ConnectedRepository ur ON ur.repositoryId = rc.repositoryId
            WHERE ur.id = :userRepositoryId AND rc.githubPrNumber IN :pullRequestNumbers
            GROUP BY rc.githubPrNumber
            """)
    List<ReviewCommentCount> countReviewComments(@Param("userRepositoryId") Long userRepositoryId,
                                                 @Param("pullRequestNumbers") Collection<Integer> pullRequestNumbers);
}
