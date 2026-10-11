package com.gitory.backend.ingest.infra;

import com.gitory.backend.ingest.domain.CollectionRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CollectionRunRepository extends JpaRepository<CollectionRun, Long> {

    Optional<CollectionRun> findFirstByUserRepositoryIdOrderByCollectedAtDesc(Long userRepositoryId);

    /** 부분 수집은 이후 커밋을 누락시킬 수 있으므로 증분 기준으로 사용하지 않는다. */
    Optional<CollectionRun> findFirstByUserRepositoryIdAndPartialFalseOrderByCollectedAtDesc(Long userRepositoryId);
}
