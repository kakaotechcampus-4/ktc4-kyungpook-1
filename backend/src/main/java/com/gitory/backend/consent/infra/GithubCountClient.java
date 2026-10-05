package com.gitory.backend.consent.infra;

import com.gitory.backend.consent.port.GithubCountTarget;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** GitHub 에서 저장소의 커밋·PR 개수를 센다 */
@Component
public class GithubCountClient {

    private static final int SEARCH_PAGE_SIZE = 100;

    // GitHub 검색은 결과를 1000개까지만 준다
    private static final int SEARCH_MAX_PAGES = 10;

    private static final String COUNT_FIELDS = """
            fragment counts on Repository {
              pullRequests { totalCount }
              defaultBranchRef { target { ... on Commit {
                all: history { totalCount }
                mine: history(author: {id: $me}) { totalCount }
              } } }
            }
            """;

    private final RestClient restClient;

    public GithubCountClient(RestClient.Builder builder) {
        this.restClient = builder
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /** 내 커밋은 GitHub 사용자 id 로 거르므로 토큰 주인의 id 를 먼저 받는다 */
    String viewerId(String token) {

        String id = graphql(token, "query { viewer { id } }", Map.of()).path("viewer").path("id").asString();
        if (id.isBlank()) {
            throw new IllegalStateException("GitHub 사용자 id 를 받지 못했다");
        }

        return id;

    }

    /** 저장소를 한 번에 묻고, GitHub 이 null 로 돌려준 저장소(지워짐·권한 잃음)는 결과에서 뺀다 */
    Map<Long, GithubRepositoryTotals> countCommits(String token, String viewerId, List<GithubCountTarget> targets) {

        StringBuilder variables = new StringBuilder("$me: ID!");
        StringBuilder fields = new StringBuilder();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("me", viewerId);
        for (int i = 0; i < targets.size(); i++) {
            variables.append(", $o").append(i).append(": String!, $n").append(i).append(": String!");
            fields.append("r").append(i).append(": repository(owner: $o").append(i)
                    .append(", name: $n").append(i).append(") { ...counts } ");
            values.put("o" + i, targets.get(i).owner());
            values.put("n" + i, targets.get(i).name());
        }

        JsonNode data = graphql(token, "query(" + variables + ") { " + fields + "} " + COUNT_FIELDS, values);

        Map<Long, GithubRepositoryTotals> totals = new HashMap<>();
        for (int i = 0; i < targets.size(); i++) {
            JsonNode repository = data.path("r" + i);
            if (repository.isMissingNode() || repository.isNull()) {
                continue;
            }
            JsonNode history = repository.path("defaultBranchRef").path("target");
            totals.put(targets.get(i).githubRepoId(), new GithubRepositoryTotals(
                    history.path("all").path("totalCount").asInt(0),
                    history.path("mine").path("totalCount").asInt(0),
                    repository.path("pullRequests").path("totalCount").asInt(0)));
        }

        return totals;

    }

    /** 검색에 걸린 PR 을 저장소 이름(nameKey)별로 센다 */
    Map<String, Integer> countPullRequests(String token, String query) {

        Map<String, Integer> counts = new HashMap<>();
        int page = 1;
        int fetched;
        do {
            JsonNode items = restClient.get()
                    .uri("/search/issues?q={q}&per_page={size}&page={page}", query, SEARCH_PAGE_SIZE, page++)
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .body(JsonNode.class)
                    .path("items");
            fetched = items.size();
            items.forEach(item -> counts.merge(nameOf(item.path("repository_url").asString()), 1, Integer::sum));
        } while (fetched == SEARCH_PAGE_SIZE && page <= SEARCH_MAX_PAGES);

        return counts;

    }

    /** GitHub 은 주인·저장소 이름의 대소문자를 구분하지 않는다 */
    static String nameKey(String owner, String name) {

        return (owner + "/" + name).toLowerCase(Locale.ROOT);

    }

    private JsonNode graphql(String token, String query, Map<String, Object> variables) {

        return restClient.post()
                .uri("/graphql")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", query, "variables", variables))
                .retrieve()
                .body(JsonNode.class)
                .path("data");

    }

    private static String nameOf(String repositoryUrl) {

        return repositoryUrl.substring(repositoryUrl.indexOf("/repos/") + "/repos/".length()).toLowerCase(Locale.ROOT);

    }
}
