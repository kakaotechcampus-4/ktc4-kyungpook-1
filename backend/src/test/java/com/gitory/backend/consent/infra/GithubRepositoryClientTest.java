package com.gitory.backend.consent.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gitory.backend.consent.port.GithubOwnerResponse;
import com.gitory.backend.consent.port.GithubRepositoryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@RestClientTest(GithubRepositoryClient.class)
@ExtendWith(OutputCaptureExtension.class)
class GithubRepositoryClientTest {

    private static final String TOKEN = "gho_test";
    private static final String PAGE_URL = "https://api.github.com/user/repos?per_page=100&page=";
    private static final String GRAPHQL_URL = "https://api.github.com/graphql";
    private static final String TEAM_REPO = node("1340154160", "kakaotechcampus-4", "ktc4-kyungpook-1", "\"develop\"");

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

    @Test
    @DisplayName("기여한 저장소는 토큰을 붙여 GraphQL 로 묻고, 질의는 내 소유를 빼고 공개 저장소의 커밋 기여만 100개씩 받는다")
    void queriesContributedRepositoriesWithToken() {

        server.expect(requestTo(GRAPHQL_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(header("Accept", "application/vnd.github+json"))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andExpect(jsonPath("$.query").value(allOf(
                        containsString("query($after: String)"),
                        containsString("repositoriesContributedTo(first: 100, after: $after"),
                        containsString("includeUserRepositories: false"),
                        containsString("contributionTypes: [COMMIT]"),
                        containsString("privacy: PUBLIC"))))
                .andExpect(jsonPath("$.variables.after").doesNotExist())
                .andRespond(withSuccess(contributedPage(false, null, TEAM_REPO), MediaType.APPLICATION_JSON));

        assertThat(client.fetchContributedRepositories(TOKEN)).hasSize(1);
        server.verify();

    }

    @Test
    @DisplayName("기여한 저장소의 값이 '/user/repos' 와 같은 칸으로 담기고 언어와 마지막 push 시각은 비어 있어도 된다")
    void mapsContributedRepositoryFields() {

        String docs = """
                {"fullDatabaseId": "1227299271", "name": "docs", "owner": {"login": "2026-ITEC0401"}, "isPrivate": false,
                 "primaryLanguage": null, "defaultBranchRef": {"name": "main"},
                 "createdAt": "2026-05-02T13:44:46Z", "pushedAt": null}""";
        server.expect(requestTo(GRAPHQL_URL))
                .andRespond(withSuccess(contributedPage(false, null, TEAM_REPO, docs), MediaType.APPLICATION_JSON));

        List<GithubRepositoryResponse> repositories = client.fetchContributedRepositories(TOKEN);

        assertThat(repositories).containsExactly(
                new GithubRepositoryResponse(1340154160L, "ktc4-kyungpook-1", new GithubOwnerResponse("kakaotechcampus-4"),
                        false, "Java", "develop", Instant.parse("2026-09-10T03:00:00Z"), Instant.parse("2026-10-17T10:12:00Z")),
                new GithubRepositoryResponse(1227299271L, "docs", new GithubOwnerResponse("2026-ITEC0401"),
                        false, null, "main", Instant.parse("2026-05-02T13:44:46Z"), null));

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unusableContributedNodes")
    @DisplayName("빈 노드·id·이름·주인·기본 브랜치가 비었거나 비공개이거나 시각을 읽을 수 없는 노드와 오류가 가리킨 노드는 빼고 나머지 저장소만 돌려준다")
    void skipsUnusableContributedNodes(String reason, String unusable, String errors) {

        server.expect(requestTo(GRAPHQL_URL)).andRespond(withSuccess("""
                {"data": {"viewer": {"repositoriesContributedTo": {
                  "pageInfo": {"hasNextPage": false, "endCursor": null}, "nodes": [%s, %s]}}}, "errors": %s}
                """.formatted(unusable, TEAM_REPO, errors), MediaType.APPLICATION_JSON));

        assertThat(client.fetchContributedRepositories(TOKEN))
                .extracting(GithubRepositoryResponse::id)
                .containsExactly(1340154160L);

    }

    @Test
    @DisplayName("뺀 저장소가 있으면 뺀 수와 오류 종류만 경고로 남기고 오류 메시지는 남기지 않는다")
    void warnsSkippedCountAndErrorTypesOnly(CapturedOutput output) {

        server.expect(requestTo(GRAPHQL_URL)).andRespond(withSuccess("""
                {"data": {"viewer": {"repositoriesContributedTo": {
                  "pageInfo": {"hasNextPage": false, "endCursor": null}, "nodes": [null, %s]}}},
                 "errors": [{"type": "FORBIDDEN", "message": "secret-message", "path": ["viewer", "repositoriesContributedTo", "nodes", 0]}]}
                """.formatted(TEAM_REPO), MediaType.APPLICATION_JSON));

        client.fetchContributedRepositories(TOKEN);

        assertThat(output.getOut() + output.getErr())
                .contains("기여한 저장소 1개는 값이 모자라 목록에 넣지 않았다: 오류 종류 [FORBIDDEN]")
                .doesNotContain("secret-message")
                .doesNotContain(TOKEN);

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"data\": null, \"errors\": [{\"type\": \"RATE_LIMITED\", \"message\": \"limit\"}]}",
            "{\"data\": {\"viewer\": null}, \"errors\": [{\"type\": \"FORBIDDEN\", \"path\": [\"viewer\"]}]}",
            "{\"data\": {\"viewer\": {\"repositoriesContributedTo\": null}}}",
            "",
            "{\"data\": {\"viewer\": {\"repositoriesContributedTo\": {\"nodes\": []}}}, \"errors\": [{\"type\": \"INTERNAL\"}]}",
            "{\"data\": {\"viewer\": {\"repositoriesContributedTo\": {\"nodes\": []}}}, \"errors\": [{\"type\": \"FORBIDDEN\", \"path\": [\"viewer\", \"login\"]}]}"
    })
    @DisplayName("저장소 목록이 없거나 본문이 비었거나 저장소를 가리키지 않는 오류가 섞인 응답이면 빈 목록으로 넘기지 않고 응답 모양 예외를 던진다")
    void throwsWhenContributedListIsMissing(String body) {

        server.expect(requestTo(GRAPHQL_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchContributedRepositories(TOKEN))
                .isInstanceOf(GithubResponseShapeException.class);

    }

    @Test
    @DisplayName("다음 쪽이 있으면 커서를 넘겨 이어 받고, 3쪽을 받은 뒤에도 남아 있으면 더 묻지 않고 경고를 남긴다")
    void followsCursorUpToThreePages(CapturedOutput output) {

        server.expect(requestTo(GRAPHQL_URL))
                .andExpect(jsonPath("$.variables.after").doesNotExist())
                .andRespond(withSuccess(contributedPage(true, "c1", node("1", "org-a", "one", "\"main\"")),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(GRAPHQL_URL))
                .andExpect(jsonPath("$.variables.after").value("c1"))
                .andRespond(withSuccess(contributedPage(true, "c2", node("2", "org-a", "two", "\"main\"")),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(GRAPHQL_URL))
                .andExpect(jsonPath("$.variables.after").value("c2"))
                .andRespond(withSuccess(contributedPage(true, "c3", node("3", "org-a", "three", "\"main\"")),
                        MediaType.APPLICATION_JSON));

        List<GithubRepositoryResponse> repositories = client.fetchContributedRepositories(TOKEN);

        assertThat(repositories).extracting(GithubRepositoryResponse::id).containsExactly(1L, 2L, 3L);
        assertThat(output.getOut() + output.getErr()).contains("기여한 저장소가 300개를 넘어");
        server.verify();

    }

    @Test
    @DisplayName("다음 쪽이 있다면서 커서가 비어 있으면 더 묻지 않고 받은 쪽까지만 돌려주며 경고를 남긴다")
    void stopsWhenNextPageHasNoCursor(CapturedOutput output) {

        server.expect(requestTo(GRAPHQL_URL))
                .andRespond(withSuccess(contributedPage(true, null, TEAM_REPO), MediaType.APPLICATION_JSON));

        assertThat(client.fetchContributedRepositories(TOKEN))
                .extracting(GithubRepositoryResponse::id)
                .containsExactly(1340154160L);
        assertThat(output.getOut() + output.getErr()).contains("커서를 주지 않아 기여한 저장소는 1쪽까지만 넣었다");
        server.verify();

    }

    private static String pageOf(int fromId, int toId) {

        return IntStream.rangeClosed(fromId, toId)
                .mapToObj(id -> """
                        {"id": %d, "name": "repo-%d", "owner": {"login": "grow22"}, "private": false}"""
                        .formatted(id, id))
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static Stream<Arguments> unusableContributedNodes() {

        return Stream.of(
                Arguments.of("빈 노드", "null", "[]"),
                Arguments.of("id 없는 노드", node(null, "org-a", "no-id", "\"main\""), "[]"),
                Arguments.of("기본 브랜치 없는 노드", node("10", "org-a", "empty", null), "[]"),
                Arguments.of("이름이 빈 노드", node("12", "org-a", "", "\"main\""), "[]"),
                Arguments.of("주인 없는 노드", node("13", "org-a", "no-owner", "\"main\"")
                        .replace("{\"login\": \"org-a\"}", "null"), "[]"),
                Arguments.of("비공개 노드", node("14", "org-a", "secret", "\"main\"")
                        .replace("\"isPrivate\": false", "\"isPrivate\": true"), "[]"),
                Arguments.of("시각을 읽을 수 없는 노드", node("15", "org-a", "bad-time", "\"main\"")
                        .replace("2026-09-10T03:00:00Z", "yesterday"), "[]"),
                Arguments.of("오류가 가리킨 노드", node("11", "org-a", "forbidden", "\"main\""),
                        "[{\"type\": \"FORBIDDEN\", \"path\": [\"viewer\", \"repositoriesContributedTo\", \"nodes\", 0, \"defaultBranchRef\"]}]"));

    }

    private static String contributedPage(boolean hasNextPage, String endCursor, String... nodes) {

        return """
                {"data": {"viewer": {"repositoriesContributedTo": {
                  "pageInfo": {"hasNextPage": %s, "endCursor": %s}, "nodes": [%s]}}}}
                """.formatted(hasNextPage, endCursor == null ? "null" : "\"" + endCursor + "\"", String.join(",", nodes));

    }

    private static String node(String id, String owner, String name, String defaultBranch) {

        return """
                {"fullDatabaseId": %s, "name": "%s", "owner": {"login": "%s"}, "isPrivate": false,
                 "primaryLanguage": {"name": "Java"}, "defaultBranchRef": %s,
                 "createdAt": "2026-09-10T03:00:00Z", "pushedAt": "2026-10-17T10:12:00Z"}"""
                .formatted(id == null ? "null" : "\"" + id + "\"", name, owner,
                        defaultBranch == null ? "null" : "{\"name\": " + defaultBranch + "}");

    }
}
