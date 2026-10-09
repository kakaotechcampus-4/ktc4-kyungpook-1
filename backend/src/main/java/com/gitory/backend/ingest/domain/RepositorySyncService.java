package com.gitory.backend.ingest.domain;

import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.consent.port.GithubRepositoryResponse;
import com.gitory.backend.ingest.infra.ConnectedRepositoryRepository;
import com.gitory.backend.ingest.infra.GithubRepoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 사용자의 GitHub 저장소 목록을 받아 repository·user_repository 에 반영한다
 * GitHub 호출이 끝난 뒤에 트랜잭션을 열어, 응답을 기다리는 동안 DB 커넥션을 붙잡지 않는다
 */
@Service
@RequiredArgsConstructor
public class RepositorySyncService {

    private final GithubCollectionAccessPort github;
    private final GithubRepoRepository repositories;
    private final ConnectedRepositoryRepository connections;
    private final TransactionTemplate transaction;

    /** 이번에 GitHub 이 돌려준 저장소들의 GitHub id 를 돌려준다 */
    public List<Long> sync(Long userId) {

        List<GithubRepositoryResponse> fetched = github.repositories(userId);

        transaction.executeWithoutResult(status -> fetched.forEach(repository -> save(userId, repository)));

        return fetched.stream().map(GithubRepositoryResponse::id).toList();

    }

    private void save(Long userId, GithubRepositoryResponse repository) {

        repositories.upsert(repository.id(), repository.owner().login(), repository.name(),
                RepositoryVisibility.of(repository.privateRepository()).name(),
                repository.language(), repository.defaultBranch(),
                repository.createdAt(), repository.pushedAt());
        connections.connectIfAbsent(userId, repository.id());

    }
}
