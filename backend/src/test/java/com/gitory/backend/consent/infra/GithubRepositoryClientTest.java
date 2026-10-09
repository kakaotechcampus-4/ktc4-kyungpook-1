package com.gitory.backend.consent.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gitory.backend.consent.port.GithubOwnerResponse;
import com.gitory.backend.consent.port.GithubRepositoryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@RestClientTest(GithubRepositoryClient.class)
class GithubRepositoryClientTest {

    private static final String TOKEN = "gho_test";
    private static final String PAGE_URL = "https://api.github.com/user/repos?per_page=100&page=";

    @Autowired
    GithubRepositoryClient client;

    @Autowired
    MockRestServiceServer server;

    @Test
    @DisplayName("GitHub 응답의 저장소 값이 칸마다 담기고 요청에는 토큰과 API 버전 헤더가 붙는다")
    void mapsRepositoryFields() {

        server.expect(requestTo(PAGE_URL + 1))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andRespond(withSuccess("""
                        [{"id": 123, "name": "gitory", "owner": {"login": "grow22"},
                          "private": true, "language": null, "default_branch": "develop", "fork": false,
                          "created_at": "2026-09-10T03:00:00Z", "pushed_at": "2026-10-05T01:00:00Z"}]
                        """, MediaType.APPLICATION_JSON));

        List<GithubRepositoryResponse> repositories = client.fetchRepositories(TOKEN);

        assertThat(repositories).containsExactly(new GithubRepositoryResponse(
                123L, "gitory", new GithubOwnerResponse("grow22"), true, null, "develop",
                Instant.parse("2026-09-10T03:00:00Z"), Instant.parse("2026-10-05T01:00:00Z")));
        server.verify();
    }

    @Test
    @DisplayName("한 페이지가 100개로 꽉 차면 다음 페이지를 부르고 100개보다 적으면 멈춘다")
    void fetchesUntilLastPage() {

        server.expect(requestTo(PAGE_URL + 1)).andRespond(withSuccess(pageOf(1, 100), MediaType.APPLICATION_JSON));
        server.expect(requestTo(PAGE_URL + 2)).andRespond(withSuccess(pageOf(101, 101), MediaType.APPLICATION_JSON));

        List<GithubRepositoryResponse> repositories = client.fetchRepositories(TOKEN);

        assertThat(repositories).hasSize(101);
        server.verify();
    }

    @Test
    @DisplayName("중간 페이지에서 GitHub 이 실패하면 앞 페이지를 받았어도 목록을 돌려주지 않고 예외가 난다")
    void failsWholeFetchWhenAPageFails() {

        server.expect(requestTo(PAGE_URL + 1)).andRespond(withSuccess(pageOf(1, 100), MediaType.APPLICATION_JSON));
        server.expect(requestTo(PAGE_URL + 2)).andRespond(withServerError());

        assertThatThrownBy(() -> client.fetchRepositories(TOKEN))
                .isInstanceOf(HttpServerErrorException.class);
    }

    private static String pageOf(int fromId, int toId) {

        return IntStream.rangeClosed(fromId, toId)
                .mapToObj(id -> """
                        {"id": %d, "name": "repo-%d", "owner": {"login": "grow22"}, "private": false}"""
                        .formatted(id, id))
                .collect(Collectors.joining(",", "[", "]"));
    }
}
