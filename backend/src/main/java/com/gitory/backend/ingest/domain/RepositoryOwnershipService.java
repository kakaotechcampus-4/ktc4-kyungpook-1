package com.gitory.backend.ingest.domain;

import com.gitory.backend.ingest.infra.ConnectedRepositoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RepositoryOwnershipService {

    private final ConnectedRepositoryRepository repositories;

    @Transactional(readOnly = true)
    public Optional<Long> findOwnedRepository(UUID publicId, Long userId) {
        return repositories.findByPublicIdAndUserId(publicId, userId)
                .map(ConnectedRepository::getId);
    }
}
