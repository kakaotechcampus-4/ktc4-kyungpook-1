package com.gitory.backend.ingest.domain;

import com.gitory.backend.ingest.infra.ConnectedRepositoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** 다른 모듈이 저장소 연결의 공개 id 와 이름을 ingest 표를 직접 읽지 않고 얻게 한다 */
@Service
@RequiredArgsConstructor
public class RepositoryReferenceService {

    private final ConnectedRepositoryRepository connections;

    public Optional<RepositoryReference> find(Long userRepositoryId) {

        return connections.findReference(userRepositoryId);

    }
}
