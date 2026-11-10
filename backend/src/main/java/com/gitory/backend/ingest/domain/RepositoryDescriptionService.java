package com.gitory.backend.ingest.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.ingest.infra.ConnectedRepositoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 내 저장소의 폴더 설명을 고친다
 * 비우면 GitHub 설명이나 README 문장을 보여 주므로 빈 글은 저장하지 않고 null 로 둔다
 */
@Service
@RequiredArgsConstructor
public class RepositoryDescriptionService {

    private static final int MAX_LENGTH = 120;

    private final RepositoryOwnershipService ownership;
    private final ConnectedRepositoryRepository connections;
    private final RepositoryWorkCounter workCounter;

    /** 남의 저장소는 글이 틀렸어도 없는 저장소와 같이 찾지 못했다고 답해, 그 저장소가 있는지 드러나지 않게 한다 */
    @Transactional
    public RepositorySummary change(UUID publicId, Long userId, String description) {

        Long connectionId = ownership.findOwnedRepository(publicId, userId, AuditAction.EDIT_REPO)
                .orElseThrow(ConnectedRepositoryNotFoundException::new);
        String stored = DescriptionText.stripped(description);
        if (stored != null) {
            check(stored);
        }

        connections.findById(connectionId).orElseThrow().describe(stored);
        RepositorySummary changed = connections.findSummary(publicId, userId)
                .orElseThrow(ConnectedRepositoryNotFoundException::new);

        return workCounter.fill(List.of(changed)).get(0);

    }

    private static void check(String description) {

        if (DescriptionText.length(description) > MAX_LENGTH) {
            throw new InvalidRepositoryDescriptionException("폴더 설명은 " + MAX_LENGTH + "자까지 쓸 수 있어요.");
        }
        if (description.indexOf('\u0000') >= 0) {
            throw new InvalidRepositoryDescriptionException("폴더 설명에 쓸 수 없는 글자가 있어요.");
        }

    }
}
