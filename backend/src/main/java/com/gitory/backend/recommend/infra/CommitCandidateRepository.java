package com.gitory.backend.recommend.infra;

import com.gitory.backend.recommend.domain.CandidateCommit;
import com.gitory.backend.recommend.domain.CommitCandidate;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface CommitCandidateRepository extends Repository<CandidateCommit, Long> {

    // 한 커밋이 같은 저장소의 후보 여러 개에 들어 있으면 내부 id 가 작은 후보가 앞에 오도록 정렬한다
    @Query("""
            SELECT new com.gitory.backend.recommend.domain.CommitCandidate(cc.commitId, c.publicId)
            FROM CandidateCommit cc JOIN Candidate c ON c.id = cc.candidateId
            WHERE c.userRepositoryId = :userRepositoryId AND cc.commitId IN :commitIds AND cc.included = TRUE
            ORDER BY c.id
            """)
    List<CommitCandidate> findIncluding(@Param("userRepositoryId") Long userRepositoryId,
                                        @Param("commitIds") Collection<Long> commitIds);
}
