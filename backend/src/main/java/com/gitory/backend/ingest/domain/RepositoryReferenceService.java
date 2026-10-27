package com.gitory.backend.ingest.domain;

import com.gitory.backend.ingest.infra.ConnectedRepositoryRepository;
import com.gitory.backend.ingest.infra.ConnectedRepositoryRepository.ReferenceRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** 다른 모듈이 저장소 연결의 공개 id 와 이름을 ingest 표를 직접 읽지 않고 얻게 한다 */
@Service
@RequiredArgsConstructor
public class RepositoryReferenceService {

    private final ConnectedRepositoryRepository connections;

    public Optional<RepositoryReference> find(Long userRepositoryId) {

        return connections.findReference(userRepositoryId);

    }

    /** 카드 목록 전체가 저장소를 한 번에 읽도록 연결 id 마다 하나씩 묶어 돌려주며, 없는 연결은 빠진다 */
    public Map<Long, RepositoryReference> findAll(Collection<Long> userRepositoryIds) {

        return connections.findReferences(userRepositoryIds).stream()
                .collect(Collectors.toMap(ReferenceRow::getId,
                        row -> new RepositoryReference(row.getPublicId(), row.getOwnerLogin(), row.getName())));

    }
}
