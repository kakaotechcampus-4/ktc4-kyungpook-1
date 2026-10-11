package com.gitory.backend.ingest.infra;

import com.gitory.backend.ingest.domain.CollectionRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CollectionRunRepository extends JpaRepository<CollectionRun, Long> {

    Optional<CollectionRun> findFirstByUserRepositoryIdOrderByCollectedAtDesc(Long userRepositoryId);
}
