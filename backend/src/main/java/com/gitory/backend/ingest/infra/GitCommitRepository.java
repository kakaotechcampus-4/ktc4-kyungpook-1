package com.gitory.backend.ingest.infra;

import com.gitory.backend.ingest.domain.GitCommit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GitCommitRepository extends JpaRepository<GitCommit, Long> {

    Optional<GitCommit> findByRepositoryIdAndSha(Long repositoryId, String sha);

    List<GitCommit> findByCollectionRunIdAndExcludedFalse(Long collectionRunId);
}
