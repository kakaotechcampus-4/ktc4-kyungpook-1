package com.gitory.backend.ingest.infra;

import com.gitory.backend.ingest.domain.CommitFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommitFileRepository extends JpaRepository<CommitFile, Long> {

    // 같은 커밋에 같은 경로가 이미 있으면 건너뛰어, 유니크 위반으로 같은 수집 전체가 되돌아가지 않게 한다
    @Modifying
    @Query(value = """
            INSERT INTO commit_file (commit_id, path, status, additions, deletions)
            VALUES (:#{#file.commitId}, :#{#file.path}, :#{#file.status.name()}, :#{#file.additions},
                    :#{#file.deletions})
            ON CONFLICT (commit_id, path) DO NOTHING
            """, nativeQuery = true)
    void insertIfAbsent(@Param("file") CommitFile file);
}
