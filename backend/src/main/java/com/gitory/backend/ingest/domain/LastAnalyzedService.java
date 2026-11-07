package com.gitory.backend.ingest.domain;

import com.gitory.backend.ingest.infra.ConnectedRepositoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 다른 모듈이 저장소 연결을 마지막으로 분석한 시각을 ingest 표를 직접 읽지 않고 얻게 한다 */
@Service
@RequiredArgsConstructor
public class LastAnalyzedService {

    private final ConnectedRepositoryRepository connections;

    /** 한 번도 분석하지 않았으면 빈 값이다 */
    public Optional<Instant> lastAnalyzedAt(Long userRepositoryId) {

        return connections.findById(userRepositoryId).map(ConnectedRepository::getLastAnalyzedAt);

    }

    /** GitHub 을 새로 부르지 않고 저장된 마지막 push 시각만 보며, 마지막 push 가 최근인 저장소부터 돌려준다 */
    public List<RepositoryPushedAfterAnalysis> pushedAfterAnalysis(Long userId) {

        return connections.findPushedAfterAnalysis(userId);

    }
}
