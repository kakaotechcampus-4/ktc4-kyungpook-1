package com.gitory.backend.ingest.infra;

import com.gitory.backend.ingest.domain.GitCommit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GitCommitRepository extends JpaRepository<GitCommit, Long> {

    Optional<GitCommit> findByRepositoryIdAndSha(Long repositoryId, String sha);

    List<GitCommit> findByCollectionRunIdAndExcludedFalse(Long collectionRunId);

    @Query("SELECT c.sha FROM GitCommit c WHERE c.repositoryId = :repositoryId AND c.sha IN :shas")
    List<String> findStoredShas(@Param("repositoryId") Long repositoryId, @Param("shas") Collection<String> shas);
}
