package com.gitory.backend.ingest.infra;

import com.gitory.backend.ingest.domain.CommitFile;
import com.gitory.backend.ingest.domain.OwnCommitFile;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** 저장소 연결이 가리키는 저장소에서 내 커밋과 그 커밋이 바꾼 파일을 읽는다 */
public interface OwnCommitFileRepository extends Repository<CommitFile, Long> {

    // 커밋 행은 사용자끼리 함께 쓰므로 내 커밋인지는 작성자 로그인으로 가린다
    @Query("""
            SELECT new com.gitory.backend.ingest.domain.OwnCommitFile(c.id, f.path)
            FROM CommitFile f
            JOIN GitCommit c ON c.id = f.commitId
            JOIN ConnectedRepository ur ON ur.repositoryId = c.repositoryId
            WHERE ur.id = :userRepositoryId
              AND LOWER(c.authorLogin) = LOWER(:login)
              AND c.exclusionReason IS NULL
            ORDER BY c.id, f.id
            """)
    List<OwnCommitFile> findOwnCommitFiles(@Param("userRepositoryId") Long userRepositoryId,
                                           @Param("login") String login);
}
