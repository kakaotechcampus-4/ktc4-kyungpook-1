package com.gitory.backend.ingest.domain;

import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.ingest.infra.ConnectedRepositoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 분석을 시작할 때 저장소의 README 에서 소개 문장을 골라 user_repository 에 남긴다
 * 소개 문장은 보조 정보라 README 를 받지 못해도 분석을 막지 않고 지난번에 남긴 문장을 그대로 둔다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepositoryReadmeService {

    private final GithubCollectionAccessPort github;
    private final ConnectedRepositoryRepository connections;
    private final TransactionTemplate transaction;

    /** README 가 없으면 칸을 그대로 두고, 받았는데 고를 문장이 없으면 옛 README 의 문장이 남지 않게 칸을 비운다 */
    public void refresh(Long userId, Long userRepositoryId) {

        try {
            RepositoryReference repository = connections.findReference(userRepositoryId).orElseThrow();
            github.readme(userId, repository.ownerLogin(), repository.name()).ifPresent(markdown -> {
                String summary = ReadmeSummary.pick(markdown).orElse(null);
                transaction.executeWithoutResult(status ->
                        connections.findById(userRepositoryId).orElseThrow().recordReadmeSummary(summary));
            });
        } catch (RuntimeException failed) {
            log.warn("저장소 {} 의 README 를 읽지 못해 소개 문장을 그대로 두고 분석을 이어 간다: {}",
                    userRepositoryId, failed.getClass().getSimpleName());
        }

    }
}
