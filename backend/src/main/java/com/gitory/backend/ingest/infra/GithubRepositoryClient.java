package com.gitory.backend.ingest.infra;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * GitHub 에서 사용자가 접근할 수 있는 저장소 목록을 마지막 페이지까지 받아 온다
 * 모든 페이지를 받은 뒤에 돌려주므로 중간 페이지에서 실패하면 예외만 남고 일부 목록은 나가지 않는다
 */
@Component
public class GithubRepositoryClient {

    private static final int PAGE_SIZE = 100;

    private final RestClient restClient;

    public GithubRepositoryClient(RestClient.Builder builder) {
        this.restClient = builder
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    public List<GithubRepositoryResponse> fetchRepositories(String token) {

        List<GithubRepositoryResponse> repositories = new ArrayList<>();
        List<GithubRepositoryResponse> page;
        int pageNumber = 1;
        do {
            page = fetchPage(token, pageNumber++);
            repositories.addAll(page);
        } while (page.size() == PAGE_SIZE);

        return repositories;
    }

    private List<GithubRepositoryResponse> fetchPage(String token, int pageNumber) {

        return restClient.get()
                .uri("/user/repos?per_page={size}&page={page}", PAGE_SIZE, pageNumber)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }
}
