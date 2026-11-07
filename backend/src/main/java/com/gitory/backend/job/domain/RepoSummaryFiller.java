package com.gitory.backend.job.domain;

import com.gitory.backend.agent.port.RepoSummaryPort;
import com.gitory.backend.agent.port.RepoSummaryRequest;
import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.ingest.domain.ReadmeRefresh;
import com.gitory.backend.ingest.domain.RepositoryReadmeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * README 에서 소개 문장을 못 골랐고 다른 설명도 없는 저장소에 AI 가 만든 한 문장을 채운다
 * 소개 문장은 보조 정보라 AI 를 부르지 못해도 예외를 던지지 않고 분석을 이어 가게 둔다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepoSummaryFiller {

    private final GithubCollectionAccessPort github;
    private final RepoSummaryPort summaries;
    private final RepositoryReadmeService readmes;

    public void fill(Long userId, Long userRepositoryId, ReadmeRefresh refresh) {

        if (!refresh.needsAiSummary()) {
            return;
        }

        try {
            List<String> topLevelFiles = github.topLevelPaths(userId, refresh.ownerLogin(), refresh.name());
            // AI 계약의 readme_head 는 0~3,000자 글이라 README 가 없으면 null 대신 빈 글로 보낸다
            String readmeHead = refresh.readmeHead() == null ? "" : refresh.readmeHead();
            RepoSummaryRequest request = new RepoSummaryRequest(userRepositoryId, refresh.ownerLogin(), refresh.name(),
                    refresh.language(), topLevelFiles, readmeHead);

            summaries.summarize(request).ifPresent(sentence -> readmes.recordAiSummary(userRepositoryId, sentence));
        } catch (RuntimeException failed) {
            log.warn("저장소 {} 의 소개 문장을 AI 로 만들지 못해 비워 두고 분석을 이어 간다: {}",
                    userRepositoryId, failed.getClass().getSimpleName());
        }

    }
}
