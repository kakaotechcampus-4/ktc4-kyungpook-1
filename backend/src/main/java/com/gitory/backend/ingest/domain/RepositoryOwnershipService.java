package com.gitory.backend.ingest.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.audit.domain.AuditLog;
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
    private final AuditLog auditLog;

    /** 남의 저장소면 부른 요청의 action 으로 거절 기록을 남기고, 응답은 없는 저장소와 똑같이 빈 값을 돌려준다 */
    @Transactional(readOnly = true)
    public Optional<Long> findOwnedRepository(UUID publicId, Long userId, AuditAction action) {

        Optional<Long> owned = repositories.findByPublicIdAndUserId(publicId, userId)
                .map(ConnectedRepository::getId);

        if (owned.isEmpty()) {
            repositories.findByPublicId(publicId)
                    .ifPresent(others -> auditLog.denied(userId, action, others.getId()));
        }

        return owned;

    }
}
