package com.gitory.backend.ingest.domain;

import com.gitory.backend.consent.domain.GithubTokenService;
import com.gitory.backend.ingest.infra.ConnectedRepositoryRepository;
import com.gitory.backend.ingest.infra.GithubRepoRepository;
import com.gitory.backend.ingest.infra.GithubRepositoryClient;
import com.gitory.backend.ingest.infra.GithubRepositoryResponse;
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

    private final GithubTokenService githubTokens;
    private final GithubRepositoryClient githubClient;
    private final GithubRepoRepository repositories;
    private final ConnectedRepositoryRepository connections;
    private final TransactionTemplate transaction;

    public void sync(Long userId) {

        String token = githubTokens.activeTokenOf(userId);
        List<GithubRepositoryResponse> fetched = githubClient.fetchRepositories(token);

        transaction.executeWithoutResult(status -> fetched.forEach(repository -> save(userId, repository)));
    }

    private void save(Long userId, GithubRepositoryResponse repository) {

        repositories.upsert(repository.id(), repository.owner().login(), repository.name(),
                RepositoryVisibility.of(repository.privateRepository()).name(),
                repository.language(), repository.defaultBranch());
        connections.connectIfAbsent(userId, repository.id());
    }
}
