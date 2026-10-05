package com.gitory.backend.consent.infra;

import com.gitory.backend.consent.port.GithubCountTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@RestClientTest(GithubCountClient.class)
class GithubCountClientTest {

    private static final String TOKEN = "gho_test";
    private static final String VIEWER_ID = "U_kgDOCa4RLw";
    private static final String GRAPHQL_URL = "https://api.github.com/graphql";

    @Autowired
    GithubCountClient client;

    @Autowired
    MockRestServiceServer server;

    @Test
    @DisplayName("토큰 주인의 GitHub id 를 받고, 받지 못하면 예외가 난다")
    void readsViewerId() {

        server.expect(requestTo(GRAPHQL_URL))
                .andRespond(withSuccess("{\"data\": {\"viewer\": {\"id\": \"U_kgDOCa4RLw\"}}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(GRAPHQL_URL))
                .andRespond(withSuccess("{\"data\": null, \"errors\": [{\"message\": \"Bad credentials\"}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.viewerId(TOKEN)).isEqualTo(VIEWER_ID);
        assertThatThrownBy(() -> client.viewerId(TOKEN)).isInstanceOf(IllegalStateException.class);

    }

    @Test
    @DisplayName("여러 저장소를 GraphQL 한 번으로 묻고, 저장소마다 전체·내 커밋 수와 전체 PR 수를 받는다")
    void countsSeveralRepositoriesAtOnce() {

        server.expect(requestTo(GRAPHQL_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(jsonPath("$.variables.me").value(VIEWER_ID))
                .andExpect(jsonPath("$.variables.o0").value("grow22"))
                .andExpect(jsonPath("$.variables.n0").value("gitory"))
                .andExpect(jsonPath("$.variables.n1").value("empty"))
                .andRespond(withSuccess("""
                        {"data": {
                          "r0": {"pullRequests": {"totalCount": 58},
                                 "defaultBranchRef": {"target": {"all": {"totalCount": 197}, "mine": {"totalCount": 52}}}},
                          "r1": {"pullRequests": {"totalCount": 0}, "defaultBranchRef": null}
                        }}
                        """, MediaType.APPLICATION_JSON));

        Map<Long, GithubRepositoryTotals> totals = client.countCommits(TOKEN, VIEWER_ID, List.of(
                new GithubCountTarget(100L, "grow22", "gitory"), new GithubCountTarget(200L, "grow22", "empty")));

        assertThat(totals)
                .containsEntry(100L, new GithubRepositoryTotals(197, 52, 58))
                .containsEntry(200L, new GithubRepositoryTotals(0, 0, 0));
        server.verify();

    }

    @Test
    @DisplayName("GitHub 이 null 로 돌려준 저장소(지워짐·권한 잃음)는 결과에서 빠진다")
    void skipsRepositoryReturnedAsNull() {

        server.expect(requestTo(GRAPHQL_URL))
                .andRespond(withSuccess("{\"data\": {\"r0\": null}, \"errors\": [{\"type\": \"NOT_FOUND\"}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.countCommits(TOKEN, VIEWER_ID, List.of(new GithubCountTarget(100L, "grow22", "gone"))))
                .isEmpty();

    }

    @Test
    @DisplayName("검색에 걸린 PR 을 저장소 이름별로 세고, 한 페이지 100개가 꽉 차면 다음 페이지를 부른다")
    void countsSearchedPullRequestsByRepository() {

        server.expect(requestTo(allOf(containsString("/search/issues?q="), containsString("page=1"))))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andRespond(withSuccess(itemsOf("kakaotechcampus-4/ktc4-kyungpook-1", 100), MediaType.APPLICATION_JSON));
        server.expect(requestTo(allOf(containsString("/search/issues?q="), containsString("page=2"))))
                .andRespond(withSuccess(itemsOf("Grow22/Algo", 3), MediaType.APPLICATION_JSON));

        Map<String, Integer> counts = client.countPullRequests(TOKEN, "is:pr author:Grow22");

        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(
                "kakaotechcampus-4/ktc4-kyungpook-1", 100,
                "grow22/algo", 3));
        server.verify();

    }

    private static String itemsOf(String repository, int count) {

        return IntStream.range(0, count)
                .mapToObj(i -> "{\"repository_url\": \"https://api.github.com/repos/" + repository + "\"}")
                .collect(Collectors.joining(",", "{\"items\": [", "]}"));

    }
}
