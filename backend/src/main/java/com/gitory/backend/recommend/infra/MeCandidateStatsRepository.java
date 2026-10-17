package com.gitory.backend.recommend.infra;

import com.gitory.backend.recommend.domain.Candidate;
import com.gitory.backend.recommend.domain.CandidateStatus;
import org.springframework.data.repository.Repository;

import java.util.Collection;

public interface MeCandidateStatsRepository extends Repository<Candidate, Long> {

    long countByUserRepositoryIdInAndStatus(Collection<Long> userRepositoryIds, CandidateStatus status);
}
