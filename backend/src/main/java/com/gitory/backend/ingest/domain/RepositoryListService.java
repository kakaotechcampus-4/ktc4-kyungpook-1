package com.gitory.backend.ingest.domain;

import com.gitory.backend.ingest.infra.ConnectedRepositoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 사용자의 GitHub 저장소 목록을 DB 에 반영한 뒤, 이번에 GitHub 이 돌려준 저장소만 읽어 온다
 * GitHub 목록에서 빠진 저장소는 그 저장소로 만든 Job·카드가 가리키고 있어 DB 에는 남기고 응답에서만 뺀다
 */
@Service
@RequiredArgsConstructor
public class RepositoryListService {

    private final RepositorySyncService sync;
    private final RepositoryContributionService contribution;
    private final ConnectedRepositoryRepository connections;

    public List<RepositorySummary> list(Long userId) {

        List<Long> githubRepoIds = sync.sync(userId);
        contribution.refresh(userId, githubRepoIds);

        return connections.findSummaries(userId, githubRepoIds);

    }
}
