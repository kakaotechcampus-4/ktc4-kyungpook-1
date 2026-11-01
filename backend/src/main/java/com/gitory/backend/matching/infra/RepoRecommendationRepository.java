package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.RepoRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RepoRecommendationRepository extends JpaRepository<RepoRecommendation, Long> {

    Optional<RepoRecommendation> findByUserRepositoryId(Long userRepositoryId);
}
