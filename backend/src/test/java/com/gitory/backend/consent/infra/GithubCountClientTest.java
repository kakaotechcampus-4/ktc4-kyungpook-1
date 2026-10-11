package com.gitory.backend.consent.infra;

import com.gitory.backend.consent.port.GithubCountTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@RestClientTest(GithubCountClient.class)
class GithubCountClientTest {

    private static final String TOKEN = "gho_test";
    private static final String VIEWER_ID = "U_kgDOCa4RLw";
    private static final String GRAPHQL_URL = "https://api.github.com/graphql";
    private static final List<GithubCountTarget> BIG_AND_SMALL_TARGETS = List.of(
            new GithubCountTarget(100L, "grow22", "big"), new GithubCountTarget(200L, "grow22", "small"));
    private static final String BIG_AND_SMALL = """
            {"data": {
              "r0": {"pullRequests": {"totalCount": 0},
                     "defaultBranchRef": {"target": {"oid": "big-head", "all": {"totalCount": 150}, "mine": %s}}},
              "r1": {"pullRequests": {"totalCount": 0},
                     "defaultBranchRef": {"target": {"oid": "small-head", "all": {"totalCount": 9}, "mine": %s}}}
            }}
            """.formatted(mine(100, 1, true, "big-100"), mine(1, 0, false, "small-1"));

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
                                 "defaultBranchRef": {"target": {"all": {"totalCount": 197}, "mine": %s}}},
                          "r1": {"pullRequests": {"totalCount": 0}, "defaultBranchRef": null}
                        }}
                        """.formatted(mine(52, 0, false, "c52")), MediaType.APPLICATION_JSON));

        Map<Long, GithubRepositoryTotals> totals = client.countCommits(TOKEN, VIEWER_ID, List.of(
                new GithubCountTarget(100L, "grow22", "gitory"), new GithubCountTarget(200L, "grow22", "empty")));

        assertThat(totals)
                .containsEntry(100L, new GithubRepositoryTotals(197, 52, 58, 0))
                .containsEntry(200L, new GithubRepositoryTotals(0, 0, 0, 0));
        server.verify();

    }

    @Test
    @DisplayName("GitHub 이 NOT_FOUND 오류와 함께 null 로 돌려준 저장소(지워짐·권한 잃음)는 결과에서 빠진다")
    void skipsRepositoryReturnedAsNull() {

        server.expect(requestTo(GRAPHQL_URL))
                .andRespond(withSuccess("{\"data\": {\"r0\": null}, \"errors\": [{\"type\": \"NOT_FOUND\", \"path\": [\"r0\"]}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.countCommits(TOKEN, VIEWER_ID, List.of(new GithubCountTarget(100L, "grow22", "gone"))))
                .isEmpty();

    }

    @Test
    @DisplayName("GitHub 이 오류를 알린 저장소는 결과에서 빼고, 오류 없이 커밋이 0개인 빈 저장소는 0 으로 남긴다")
    void skipsRepositoryReportedInErrors() {

        server.expect(requestTo(GRAPHQL_URL))
                .andRespond(withSuccess("""
                        {"data": {
                          "r0": {"pullRequests": {"totalCount": 58},
                                 "defaultBranchRef": {"target": {"all": {"totalCount": 197}, "mine": %s}}},
                          "r1": {"pullRequests": {"totalCount": 3}, "defaultBranchRef": {"target": null}},
                          "r2": {"pullRequests": {"totalCount": 0}, "defaultBranchRef": null}
                        },
                        "errors": [{"message": "timeout", "path": ["r1", "defaultBranchRef", "target", "mine"]}]}
                        """.formatted(mine(52, 0, false, "c52")), MediaType.APPLICATION_JSON));

        Map<Long, GithubRepositoryTotals> totals = client.countCommits(TOKEN, VIEWER_ID, List.of(
                new GithubCountTarget(100L, "grow22", "gitory"),
                new GithubCountTarget(200L, "grow22", "failed"),
                new GithubCountTarget(300L, "grow22", "empty")));

        assertThat(totals).containsOnlyKeys(100L, 300L)
                .containsEntry(100L, new GithubRepositoryTotals(197, 52, 58, 0))
                .containsEntry(300L, new GithubRepositoryTotals(0, 0, 0, 0));

    }

    @Test
    @DisplayName("어느 저장소인지 적히지 않은 오류가 오면 같이 물은 저장소 전체를 실패로 던진다")
    void failsWholeBatchOnErrorWithoutPath() {

        server.expect(requestTo(GRAPHQL_URL))
                .andRespond(withSuccess("""
                        {"data": null, "errors": [{"type": "RATE_LIMITED", "message": "API rate limit exceeded"}]}
                        """, MediaType.APPLICATION_JSON));

        List<GithubCountTarget> batch = List.of(new GithubCountTarget(100L, "grow22", "gitory"));
        assertThatThrownBy(() -> client.countCommits(TOKEN, VIEWER_ID, batch)).isInstanceOf(IllegalStateException.class);

    }

    @Test
    @DisplayName("검색 결과가 덜 왔거나(incomplete_results) 목록이 없으면 센 만큼을 돌려주지 않고 예외를 던진다")
    void failsOnIncompleteSearch() {

        server.expect(requestTo(containsString("/search/issues?q=")))
                .andRespond(withSuccess("""
                        {"total_count": 250, "incomplete_results": true,
                         "items": [{"repository_url": "https://api.github.com/repos/grow22/gitory"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/search/issues?q=")))
                .andRespond(withSuccess("{\"total_count\": 3, \"incomplete_results\": false}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.countPullRequests(TOKEN, "is:pr author:Grow22"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.countPullRequests(TOKEN, "is:pr reviewed-by:Grow22 -author:Grow22"))
                .isInstanceOf(IllegalStateException.class);
        server.verify();

    }

    @Test
    @DisplayName("내 커밋 목록에서 부모가 2개 이상인 커밋을 내가 누른 머지 커밋으로 센다")
    void countsMergeCommitsFromMyCommitList() {

        server.expect(requestTo(GRAPHQL_URL))
                .andExpect(jsonPath("$.query").value(allOf(
                        containsString("mine: history(author: {id: $me}, first: 100)"),
                        containsString("nodes { parents { totalCount } }"))))
                .andRespond(withSuccess("""
                        {"data": {"r0": {"pullRequests": {"totalCount": 2},
                          "defaultBranchRef": {"target": {"all": {"totalCount": 10}, "mine": {
                            "nodes": [{"parents": {"totalCount": 1}}, {"parents": {"totalCount": 2}},
                                      {"parents": {"totalCount": 1}}, {"parents": {"totalCount": 3}}],
                            "pageInfo": {"hasNextPage": false, "endCursor": "c4"}}}}}}}
                        """, MediaType.APPLICATION_JSON));

        Map<Long, GithubRepositoryTotals> totals = client.countCommits(TOKEN, VIEWER_ID,
                List.of(new GithubCountTarget(100L, "taehun0208", "SnapCal-be")));

        assertThat(totals).containsEntry(100L, new GithubRepositoryTotals(10, 4, 2, 2));

    }

    @Test
    @DisplayName("묶음 질의는 내 커밋 수(totalCount)를 묻지 않고 내 커밋 목록만 받는다")
    void batchQueryDoesNotAskOwnCommitTotal() {

        server.expect(requestTo(GRAPHQL_URL))
                .andExpect(jsonPath("$.query").value(containsString("first: 100) {\n      nodes { parents { totalCount } }")))
                .andRespond(withSuccess("{\"data\": {}}", MediaType.APPLICATION_JSON));

        client.countCommits(TOKEN, VIEWER_ID, List.of(new GithubCountTarget(100L, "grow22", "gitory")));
        server.verify();

    }

    @Test
    @DisplayName("내 커밋이 100개를 넘는 저장소는 그 저장소만 이어서 받아 머지 커밋을 센다")
    void continuesOnlyRepositoryWithMoreCommits() {

        server.expect(requestTo(GRAPHQL_URL))
                .andRespond(withSuccess(BIG_AND_SMALL, MediaType.APPLICATION_JSON));
        server.expect(requestTo(GRAPHQL_URL))
                .andExpect(jsonPath("$.query").value(allOf(
                        containsString("history(author: {id: $me}, first: 100, after: $after)"),
                        containsString("nodes { parents { totalCount } }"))))
                .andExpect(jsonPath("$.variables.me").value(VIEWER_ID))
                .andExpect(jsonPath("$.variables.owner").value("grow22"))
                .andExpect(jsonPath("$.variables.name").value("big"))
                .andExpect(jsonPath("$.variables.after").value("big-100"))
                .andRespond(withSuccess(historyPage(mine(50, 1, false, "big-150")), MediaType.APPLICATION_JSON));

        Map<Long, GithubRepositoryTotals> totals = client.countCommits(TOKEN, VIEWER_ID, BIG_AND_SMALL_TARGETS);

        assertThat(totals)
                .containsEntry(100L, new GithubRepositoryTotals(150, 150, 0, 2))
                .containsEntry(200L, new GithubRepositoryTotals(9, 1, 0, 0));
        server.verify();

    }

    @Test
    @DisplayName("내 커밋 목록은 저장소마다 10번(최근 1000개)까지만 받아 그 안의 머지 커밋만 빼고, 내 커밋 수는 따로 묻는다")
    void stopsAtHistoryPageLimit() {

        server.expect(requestTo(GRAPHQL_URL))
                .andRespond(withSuccess("""
                        {"data": {"r0": {"pullRequests": {"totalCount": 0},
                          "defaultBranchRef": {"target": {"oid": "huge-head", "all": {"totalCount": 5000}, "mine": %s}}}}}
                        """.formatted(mine(100, 1, true, "page-1")), MediaType.APPLICATION_JSON));
        for (int page = 2; page <= 10; page++) {
            server.expect(requestTo(GRAPHQL_URL))
                    .andExpect(jsonPath("$.variables.after").value("page-" + (page - 1)))
                    .andRespond(withSuccess(historyPage(mine(100, 1, true, "page-" + page)), MediaType.APPLICATION_JSON));
        }
        server.expect(requestTo(GRAPHQL_URL))
                .andExpect(jsonPath("$.query").value(containsString("mine: history(author: {id: $me}) { totalCount }")))
                .andRespond(withSuccess(historyPage("{\"totalCount\": 5000}"), MediaType.APPLICATION_JSON));

        Map<Long, GithubRepositoryTotals> totals = client.countCommits(TOKEN, VIEWER_ID,
                List.of(new GithubCountTarget(100L, "grow22", "huge")));

        assertThat(totals).containsEntry(100L, new GithubRepositoryTotals(5000, 5000, 0, 10));
        server.verify();

    }

    @Test
    @DisplayName("이어 받기와 내 커밋 수는 첫 질의 때의 맨 위 커밋(oid) 기준으로 물어, 그 사이 push 가 내 커밋 수에만 들어가지 않는다")
    void pinsFollowUpQueriesToFirstHeadCommit() {

        server.expect(requestTo(GRAPHQL_URL))
                .andExpect(jsonPath("$.query").value(containsString("... on Commit {\n    oid\n")))
                .andRespond(withSuccess("""
                        {"data": {"r0": {"pullRequests": {"totalCount": 0},
                          "defaultBranchRef": {"target": {"oid": "head-at-first", "all": {"totalCount": 1500}, "mine": %s}}}}}
                        """.formatted(mine(100, 0, true, "page-1")), MediaType.APPLICATION_JSON));
        for (int page = 2; page <= 10; page++) {
            server.expect(requestTo(GRAPHQL_URL))
                    .andExpect(jsonPath("$.query").value(containsString("object(oid: $oid)")))
                    .andExpect(jsonPath("$.variables.oid").value("head-at-first"))
                    .andRespond(withSuccess(historyPage(mine(100, 0, true, "page-" + page)), MediaType.APPLICATION_JSON));
        }
        server.expect(requestTo(GRAPHQL_URL))
                .andExpect(jsonPath("$.query").value(allOf(
                        containsString("object(oid: $oid)"),
                        containsString("mine: history(author: {id: $me}) { totalCount }"))))
                .andExpect(jsonPath("$.variables.oid").value("head-at-first"))
                .andRespond(withSuccess(historyPage("{\"totalCount\": 1500}"), MediaType.APPLICATION_JSON));

        Map<Long, GithubRepositoryTotals> totals = client.countCommits(TOKEN, VIEWER_ID,
                List.of(new GithubCountTarget(100L, "grow22", "solo")));

        assertThat(totals).containsEntry(100L, new GithubRepositoryTotals(1500, 1500, 0, 0));
        server.verify();

    }

    @Test
    @DisplayName("내 커밋 목록을 이어 받지 못한 저장소는 결과에서 빼고, 같이 물은 다른 저장소는 남긴다")
    void skipsRepositoryWhoseNextPageFailed() {

        server.expect(requestTo(GRAPHQL_URL))
                .andRespond(withSuccess(BIG_AND_SMALL, MediaType.APPLICATION_JSON));
        server.expect(requestTo(GRAPHQL_URL))
                .andRespond(withSuccess("""
                        {"data": {"repository": null}, "errors": [{"type": "SERVICE_UNAVAILABLE", "path": ["repository"]}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.countCommits(TOKEN, VIEWER_ID, BIG_AND_SMALL_TARGETS)).containsOnlyKeys(200L);

    }

    @Test
    @DisplayName("내 커밋 목록을 이어 받다 GitHub 이 오류 상태(502)로 답해도 그 저장소만 결과에서 뺀다")
    void skipsRepositoryWhoseNextPageReturnedServerError() {

        server.expect(requestTo(GRAPHQL_URL))
                .andRespond(withSuccess(BIG_AND_SMALL, MediaType.APPLICATION_JSON));
        server.expect(requestTo(GRAPHQL_URL))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThat(client.countCommits(TOKEN, VIEWER_ID, BIG_AND_SMALL_TARGETS)).containsOnlyKeys(200L);

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

    private static String historyPage(String mine) {

        return """
                {"data": {"repository": {"object": {"mine": %s}}}}
                """.formatted(mine);

    }

    private static String mine(int commits, int mergeCommits, boolean hasNextPage, String endCursor) {

        return """
                {"nodes": %s, "pageInfo": {"hasNextPage": %s, "endCursor": "%s"}}
                """.formatted(commits(commits, mergeCommits), hasNextPage, endCursor);

    }

    private static String commits(int count, int mergeCommits) {

        return IntStream.range(0, count)
                .mapToObj(i -> "{\"parents\": {\"totalCount\": " + (i < mergeCommits ? 2 : 1) + "}}")
                .collect(Collectors.joining(",", "[", "]"));

    }

    private static String itemsOf(String repository, int count) {

        return IntStream.range(0, count)
                .mapToObj(i -> "{\"repository_url\": \"https://api.github.com/repos/" + repository + "\"}")
                .collect(Collectors.joining(",", "{\"items\": [", "]}"));

    }
}
