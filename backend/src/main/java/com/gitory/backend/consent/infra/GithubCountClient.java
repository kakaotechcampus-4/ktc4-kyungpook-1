package com.gitory.backend.consent.infra;

import com.gitory.backend.consent.port.GithubCountTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** GitHub 에서 저장소의 커밋·PR 개수를 센다 */
@Slf4j
@Component
public class GithubCountClient {

    private static final int SEARCH_PAGE_SIZE = 100;

    // GitHub 검색은 결과를 1000개까지만 준다
    private static final int SEARCH_MAX_PAGES = 10;

    // 내 커밋 목록은 한 번에 100개씩 오고, 저장소마다 최근 1000개까지만 머지인지 본다
    private static final int MAX_HISTORY_PAGES = 10;

    private static final String COUNT_FIELDS = """
            fragment counts on Repository {
              pullRequests { totalCount }
              defaultBranchRef { target { ... on Commit {
                oid
                all: history { totalCount }
                mine: history(author: {id: $me}, first: 100) {
                  nodes { parents { totalCount } }
                  pageInfo { hasNextPage endCursor }
                }
              } } }
            }
            """;

    private static final String NEXT_HISTORY_PAGE = """
            query($me: ID!, $owner: String!, $name: String!, $oid: GitObjectID!, $after: String!) {
              repository(owner: $owner, name: $name) {
                object(oid: $oid) { ... on Commit {
                  mine: history(author: {id: $me}, first: 100, after: $after) {
                    nodes { parents { totalCount } }
                    pageInfo { hasNextPage endCursor }
                  }
                } }
              }
            }
            """;

    private static final String OWN_COMMIT_TOTAL = """
            query($me: ID!, $owner: String!, $name: String!, $oid: GitObjectID!) {
              repository(owner: $owner, name: $name) {
                object(oid: $oid) { ... on Commit {
                  mine: history(author: {id: $me}) { totalCount }
                } }
              }
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

        String id = graphql(token, "query { viewer { id } }", Map.of()).path("data").path("viewer").path("id").asString();
        if (id.isBlank()) {
            throw new IllegalStateException("GitHub 사용자 id 를 받지 못했다");
        }

        return id;

    }

    /** 저장소를 한 번에 묻고, 실패했거나 null 로 돌아온 저장소(지워짐·권한 잃음)는 결과에서 빼 다음 조회 때 다시 세게 한다 */
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

        JsonNode response = graphql(token, "query(" + variables + ") { " + fields + "} " + COUNT_FIELDS, values);
        Set<String> failed = failedAliases(response.path("errors"));
        JsonNode data = response.path("data");

        Map<Long, GithubRepositoryTotals> totals = new HashMap<>();
        for (int i = 0; i < targets.size(); i++) {
            JsonNode repository = data.path("r" + i);
            if (failed.contains("r" + i) || repository.isMissingNode() || repository.isNull()) {
                continue;
            }
            GithubCountTarget target = targets.get(i);
            JsonNode history = repository.path("defaultBranchRef").path("target");
            Optional<GithubOwnCommits> own = ownCommits(token, viewerId, target, history);
            if (own.isEmpty()) {
                continue;
            }
            totals.put(target.githubRepoId(), new GithubRepositoryTotals(
                    history.path("all").path("totalCount").asInt(0),
                    own.get().commitCount(),
                    repository.path("pullRequests").path("totalCount").asInt(0),
                    own.get().mergeCommitCount()));
        }

        return totals;

    }

    /** 검색에 걸린 PR 을 저장소 이름(nameKey)별로 센다 */
    Map<String, Integer> countPullRequests(String token, String query) {

        Map<String, Integer> counts = new HashMap<>();
        int page = 1;
        int fetched;
        do {
            JsonNode response = restClient.get()
                    .uri("/search/issues?q={q}&per_page={size}&page={page}", query, SEARCH_PAGE_SIZE, page++)
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode items = response.path("items");
            // GitHub 은 검색이 시간 안에 안 끝나면 찾은 만큼만 200 으로 주므로, 덜 왔거나 목록이 없는 결과는 저장되지 않게 던진다
            if (response.path("incomplete_results").asBoolean(false) || !items.isArray()) {
                throw new IllegalStateException("GitHub 검색 결과를 다 받지 못했다: " + query);
            }
            fetched = items.size();
            items.forEach(item -> counts.merge(nameOf(item.path("repository_url").asString()), 1, Integer::sum));
        } while (fetched == SEARCH_PAGE_SIZE && page <= SEARCH_MAX_PAGES);

        return counts;

    }

    /** GitHub 은 주인·저장소 이름의 대소문자를 구분하지 않는다 */
    static String nameKey(String owner, String name) {

        return (owner + "/" + name).toLowerCase(Locale.ROOT);

    }

    /**
     * 내 커밋 수는 받은 목록을 세서 구한다, 같은 질의에서 totalCount 까지 물으면 GitHub 이 기록을 두 번 훑어 긴 저장소에서 시간 초과(502)가 난다
     * 목록이 100개를 넘는 저장소만 이어 받고 10번을 넘기면 개수만 따로 물으며, 이어 받기에 실패한 저장소는 이번 결과에서 빼 다음 조회 때 다시 센다
     * 이어 받기와 개수는 첫 질의 때의 맨 위 커밋(oid) 기준으로 물어, 그 사이 push 가 전체 수에는 없고 내 커밋 수에만 들어가지 않게 한다
     */
    private Optional<GithubOwnCommits> ownCommits(String token, String viewerId, GithubCountTarget target, JsonNode history) {

        String oid = history.path("oid").asString();
        JsonNode mine = history.path("mine");
        int commits = mine.path("nodes").size();
        int merges = mergeCommitsIn(mine);
        JsonNode page = mine;
        try {
            for (int fetched = 1; page.path("pageInfo").path("hasNextPage").asBoolean(false); fetched++) {
                if (fetched == MAX_HISTORY_PAGES) {
                    log.info("저장소 {}/{} 의 내 커밋이 많아 최근 커밋 안의 머지만 뺀다", target.owner(), target.name());
                    return Optional.of(new GithubOwnCommits(ownCommitTotal(token, viewerId, target, oid), merges));
                }
                page = nextHistoryPage(token, viewerId, target, oid, page.path("pageInfo").path("endCursor").asString());
                commits += page.path("nodes").size();
                merges += mergeCommitsIn(page);
            }
        } catch (RuntimeException failed) {
            if (isRateLimited(failed)) {
                throw failed;
            }
            log.warn("저장소 {}/{} 의 내 커밋을 이어 받지 못해 다음 조회 때 다시 센다", target.owner(), target.name(), failed);
            return Optional.empty();
        }

        return Optional.of(new GithubOwnCommits(commits, merges));

    }

    private JsonNode nextHistoryPage(String token, String viewerId, GithubCountTarget target, String oid, String after) {

        JsonNode response = graphql(token, NEXT_HISTORY_PAGE,
                Map.of("me", viewerId, "owner", target.owner(), "name", target.name(), "oid", oid, "after", after));
        JsonNode mine = response.path("data").path("repository").path("object").path("mine");
        if (!response.path("errors").isEmpty() || !mine.path("nodes").isArray()) {
            throw new IllegalStateException("GitHub 내 커밋 목록을 이어 받지 못했다");
        }

        return mine;

    }

    private int ownCommitTotal(String token, String viewerId, GithubCountTarget target, String oid) {

        JsonNode response = graphql(token, OWN_COMMIT_TOTAL,
                Map.of("me", viewerId, "owner", target.owner(), "name", target.name(), "oid", oid));
        JsonNode total = response.path("data").path("repository").path("object").path("mine").path("totalCount");
        if (!response.path("errors").isEmpty() || !total.isNumber()) {
            throw new IllegalStateException("GitHub 내 커밋 수를 받지 못했다");
        }

        return total.asInt();

    }

    private static int mergeCommitsIn(JsonNode history) {

        int merges = 0;
        for (JsonNode commit : history.path("nodes")) {
            if (commit.path("parents").path("totalCount").asInt(0) > 1) {
                merges++;
            }
        }

        return merges;

    }

    private JsonNode graphql(String token, String query, Map<String, Object> variables) {

        return restClient.post()
                .uri("/graphql")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", query, "variables", variables))
                .retrieve()
                .body(JsonNode.class);

    }

    /**
     * GitHub 은 일부 저장소가 실패해도 200 으로 답하고 errors 에만 알리므로, 오류 경로의 첫 칸(r0, r1 …)을 실패한 저장소로 본다
     * 경로가 없는 오류는 어느 저장소가 실패했는지 알 수 없어 묶음 전체를 실패로 던진다
     */
    private static Set<String> failedAliases(JsonNode errors) {

        Set<String> aliases = new HashSet<>();
        for (JsonNode error : errors) {
            JsonNode path = error.path("path");
            if (path.isEmpty()) {
                throw new IllegalStateException("GitHub GraphQL 오류: " + error.path("message").asString(""));
            }
            aliases.add(path.get(0).asString());
        }

        return aliases;

    }

    private static String nameOf(String repositoryUrl) {

        return repositoryUrl.substring(repositoryUrl.indexOf("/repos/") + "/repos/".length()).toLowerCase(Locale.ROOT);

    }

    /** GitHub 은 2차 한도를 넘으면 403 이나 429 로 거절하고, 거절된 채로 계속 물으면 막히는 시간이 길어진다 */
    static boolean isRateLimited(RuntimeException failed) {

        return failed instanceof HttpClientErrorException.Forbidden
                || failed instanceof HttpClientErrorException.TooManyRequests;

    }
}
