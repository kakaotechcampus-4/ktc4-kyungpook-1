package com.gitory.backend.ingest.domain;

import com.gitory.backend.ingest.infra.ConnectedRepositoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 저장소 한 개의 목록 정보와 분석 전 안내를 돌려준다
 * 바로 앞의 목록 조회가 GitHub 과 DB 를 맞춰 두므로 GitHub 을 다시 부르지 않고 DB 만 읽는다
 */
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(IngestLimits.class)
public class RepositoryDetailService {

    private final ConnectedRepositoryRepository connections;
    private final IngestLimits limits;

    public RepositoryDetail detail(UUID publicId, Long userId) {

        RepositorySummary summary = connections.findSummary(publicId, userId)
                .orElseThrow(ConnectedRepositoryNotFoundException::new);

        return new RepositoryDetail(summary, Disclosure.of(summary, limits));

    }
}
