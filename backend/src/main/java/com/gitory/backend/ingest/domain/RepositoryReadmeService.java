package com.gitory.backend.ingest.domain;

import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.ingest.infra.ConnectedRepositoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * 분석을 시작할 때 저장소의 README 에서 소개 문장을 골라 user_repository 에 남기고, 보여 줄 설명이 하나도 없으면 AI 로 문장을 만들 재료를 돌려준다
 * 소개 문장은 보조 정보라 README 를 받지 못해도 분석을 막지 않고 지난번에 남긴 문장을 그대로 둔다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepositoryReadmeService {

    // AI 가 한 문장을 만드는 데는 README 앞부분이면 충분해서 보내는 양을 줄이려고 자른다
    private static final int README_HEAD_LENGTH = 3_000;

    private final GithubCollectionAccessPort github;
    private final ConnectedRepositoryRepository connections;
    private final TransactionTemplate transaction;

    /**
     * README 가 없으면 칸을 그대로 두고, 받았는데 고를 문장이 없으면 옛 README 의 문장이 남지 않게 칸을 비운다
     * 사용자 설명·GitHub 설명·소개 문장이 이 정리 뒤에도 모두 비어 있을 때만 needsAiSummary 가 참이라, 지난번에 AI 가 만든 문장이 남아 있으면 AI 를 다시 부르지 않는다
     */
    public ReadmeRefresh refresh(Long userId, Long userRepositoryId) {

        try {
            RepositoryReference repository = connections.findReference(userRepositoryId).orElseThrow();
            Optional<String> readme = github.readme(userId, repository.ownerLogin(), repository.name());
            readme.ifPresent(markdown -> {
                String summary = ReadmeSummary.pick(markdown).orElse(null);
                transaction.executeWithoutResult(status -> connections.findById(userRepositoryId).orElseThrow()
                        .recordReadmeSummary(summary, ReadmeSummarySource.RULE));
            });
            RepositorySummary current = connections.findSummary(repository.publicId(), userId).orElseThrow();
            String readmeHead = readme.map(markdown -> DescriptionText.cut(markdown, README_HEAD_LENGTH)).orElse(null);

            return new ReadmeRefresh(current.descriptionSource() == null, current.ownerLogin(), current.name(),
                    current.language(), readmeHead);
        } catch (RuntimeException failed) {
            log.warn("저장소 {} 의 README 를 읽지 못해 소개 문장을 그대로 두고 분석을 이어 간다: {}",
                    userRepositoryId, failed.getClass().getSimpleName());

            return new ReadmeRefresh(false, null, null, null, null);
        }

    }

    public void recordAiSummary(Long userRepositoryId, String sentence) {

        transaction.executeWithoutResult(status -> connections.findById(userRepositoryId).orElseThrow()
                .recordReadmeSummary(sentence, ReadmeSummarySource.AI));

    }
}
