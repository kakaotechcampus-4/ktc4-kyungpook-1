package com.gitory.backend.ingest.infra;

import com.gitory.backend.ingest.domain.CommitSummary;
import com.gitory.backend.ingest.domain.DraftCommit;
import com.gitory.backend.ingest.domain.GitCommit;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface CommitSummaryRepository extends Repository<GitCommit, Long> {

    // sha 칸은 CHAR(40) 이라 40자보다 짧은 sha 는 뒤에 공백이 붙어 나오므로 지운다
    @Query("""
            SELECT new com.gitory.backend.ingest.domain.CommitSummary(
                c.id, TRIM(TRAILING FROM c.sha), c.message, c.authoredAt, c.changedFiles)
            FROM GitCommit c WHERE c.id IN :commitIds
            """)
    List<CommitSummary> findByIds(@Param("commitIds") Collection<Long> commitIds);

    // 추가·삭제 줄 수를 모르는 커밋은 0 줄로 보고, 줄 수가 같으면 최근 커밋을 앞에 둔다
    @Query("""
            SELECT new com.gitory.backend.ingest.domain.DraftCommit(c.id, TRIM(TRAILING FROM c.sha))
            FROM GitCommit c WHERE c.id IN :commitIds
            ORDER BY COALESCE(c.additions, 0) + COALESCE(c.deletions, 0) DESC, c.authoredAt DESC, c.id DESC
            """)
    List<DraftCommit> findLargestFirst(@Param("commitIds") Collection<Long> commitIds, Limit limit);
}
