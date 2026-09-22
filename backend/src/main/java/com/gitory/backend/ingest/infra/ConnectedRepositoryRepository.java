package com.gitory.backend.ingest.infra;

import com.gitory.backend.ingest.domain.ConnectedRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ConnectedRepositoryRepository extends JpaRepository<ConnectedRepository, Long> {

    Optional<ConnectedRepository> findByPublicIdAndUserId(UUID publicId, Long userId);

}
