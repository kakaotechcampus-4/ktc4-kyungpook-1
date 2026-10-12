package com.gitory.backend.ingest.infra;

import com.gitory.backend.ingest.domain.CommitSummary;
import com.gitory.backend.ingest.domain.GitCommit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface CommitSummaryRepository extends Repository<GitCommit, Long> {

    // sha 칸은 CHAR(40) 이라 40자보다 짧은 sha 는 뒤에 공백이 붙어 나오므로 지운다
    @Query("""
            SELECT new com.gitory.backend.ingest.domain.CommitSummary(
                c.id, TRIM(TRAILING FROM c.sha), c.message, c.authoredAt)
            FROM GitCommit c WHERE c.id IN :commitIds
            """)
    List<CommitSummary> findByIds(@Param("commitIds") Collection<Long> commitIds);
}
