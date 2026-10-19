package com.gitory.backend.consent.infra;

import com.gitory.backend.consent.port.GithubOwnerResponse;
import com.gitory.backend.consent.port.GithubRepositoryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** GitHub 에서 사용자가 접근할 수 있는 저장소 목록과 커밋을 남긴 남의 공개 저장소를 받아 온다 */
@Slf4j
@Component
@EnableConfigurationProperties(GithubApiProperties.class)
public class GithubRepositoryClient {

    private static final int PAGE_SIZE = 100;

    // GitHub 이 결과를 정렬 인자와 상관없이 id 오름차순으로 줘서 앞쪽만 받으면 최근 저장소가 잘리므로 여러 쪽을 받고, 상한에 닿아도 남으면 경고를 남긴다
    private static final int MAX_CONTRIBUTED_PAGES = 3;

    private static final String CONTRIBUTED_QUERY = """
            query($after: String) {
              viewer {
                repositoriesContributedTo(first: %d, after: $after, includeUserRepositories: false,
                                          contributionTypes: [COMMIT], privacy: PUBLIC) {
                  pageInfo { hasNextPage endCursor }
                  nodes {
                    fullDatabaseId name owner { login } isPrivate
                    primaryLanguage { name } defaultBranchRef { name } createdAt pushedAt
                  }
                }
              }
            }
            """.formatted(PAGE_SIZE);

    private final RestClient restClient;

    public GithubRepositoryClient(RestClient.Builder builder, GithubApiProperties properties) {
        this.restClient = builder
                .baseUrl(properties.apiBaseUrl())
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

    /**
     * 조직이 승인하지 않은 앱에는 '/user/repos' 가 조직 소유 저장소를 주지 않아, 내가 커밋을 남긴 남의 공개 저장소를 GraphQL 로 따로 찾는다
     * id·기본 브랜치가 없거나 오류가 가리킨 저장소는 여러 사용자가 같이 쓰는 저장소 행을 빈 값으로 덮지 않도록 뺀다
     * 저장소 목록이 아예 없는 응답이면 GithubResponseShapeException 을 던진다
     */
    public List<GithubRepositoryResponse> fetchContributedRepositories(String token) {

        List<GithubRepositoryResponse> repositories = new ArrayList<>();
        Set<String> errorTypes = new TreeSet<>();
        int skipped = 0;
        String cursor = null;
        for (int page = 1; page <= MAX_CONTRIBUTED_PAGES; page++) {
            JsonNode response = fetchContributedPage(token, cursor);
            JsonNode contributed = response.path("data").path("viewer").path("repositoriesContributedTo");
            JsonNode nodes = contributed.path("nodes");
            Set<Integer> failed = failedNodes(response.path("errors"), errorTypes);
            if (!nodes.isArray()) {
                throw new GithubResponseShapeException("GitHub 이 기여한 저장소 목록을 주지 않았다");
            }
            for (int i = 0; i < nodes.size(); i++) {
                JsonNode node = nodes.get(i);
                if (failed.contains(i) || !isUsable(node)) {
                    skipped++;
                    continue;
                }
                repositories.add(toResponse(node));
            }

            JsonNode pageInfo = contributed.path("pageInfo");
            if (!pageInfo.path("hasNextPage").asBoolean(false)) {
                break;
            }
            if (page == MAX_CONTRIBUTED_PAGES) {
                log.warn("기여한 저장소가 {}개를 넘어 뒤쪽(최근 저장소)은 목록에 넣지 못했다", PAGE_SIZE * MAX_CONTRIBUTED_PAGES);
                break;
            }
            JsonNode endCursor = pageInfo.path("endCursor");
            if (!endCursor.isString() || endCursor.asString().isEmpty()) {
                log.warn("GitHub 이 다음 쪽이 있다면서 커서를 주지 않아 기여한 저장소는 {}쪽까지만 넣었다", page);
                break;
            }
            cursor = endCursor.asString();
        }
        if (skipped > 0) {
            log.warn("기여한 저장소 {}개는 값이 모자라 목록에 넣지 않았다: 오류 종류 {}", skipped, errorTypes);
        }

        return repositories;

    }

    private List<GithubRepositoryResponse> fetchPage(String token, int pageNumber) {

        return restClient.get()
                .uri("/user/repos?per_page={size}&page={page}", PAGE_SIZE, pageNumber)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    private JsonNode fetchContributedPage(String token, String cursor) {

        Map<String, Object> variables = cursor == null ? Map.of() : Map.of("after", cursor);
        JsonNode response = restClient.post()
                .uri("/graphql")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", CONTRIBUTED_QUERY, "variables", variables))
                .retrieve()
                .body(JsonNode.class);
        if (response == null) {
            throw new GithubResponseShapeException("GitHub 이 기여한 저장소 응답을 비워 보냈다");
        }

        return response;

    }

    /** 오류 경로가 저장소 하나(viewer.repositoriesContributedTo.nodes.i)를 가리키면 그 저장소만 빼고, 경로가 없거나 다른 곳을 가리키면 목록 전체를 믿을 수 없어 던진다 */
    private static Set<Integer> failedNodes(JsonNode errors, Set<String> errorTypes) {

        Set<Integer> failed = new HashSet<>();
        for (JsonNode error : errors) {
            errorTypes.add(error.path("type").asString("UNKNOWN"));
            JsonNode path = error.path("path");
            boolean pointsToNode = path.size() >= 4
                    && "viewer".equals(path.get(0).asString(""))
                    && "repositoriesContributedTo".equals(path.get(1).asString(""))
                    && "nodes".equals(path.get(2).asString(""))
                    && path.get(3).isInt();
            if (!pointsToNode) {
                throw new GithubResponseShapeException("GitHub 이 기여한 저장소 목록에 오류를 알렸다: " + errorTypes);
            }
            failed.add(path.get(3).asInt());
        }

        return failed;

    }

    /** 여러 사용자가 같이 쓰는 저장소 행을 덮으므로 id·이름·주인·기본 브랜치가 비었거나, 공개가 아니거나, 시각을 읽을 수 없는 저장소는 쓰지 않는다 */
    private static boolean isUsable(JsonNode node) {

        JsonNode id = node.path("fullDatabaseId");
        JsonNode isPrivate = node.path("isPrivate");

        return id.isString() && id.asString().matches("\\d{1,18}")
                && isFilled(node.path("name"))
                && isFilled(node.path("owner").path("login"))
                && isFilled(node.path("defaultBranchRef").path("name"))
                && isPrivate.isBoolean() && !isPrivate.asBoolean()
                && isReadableTime(node.path("createdAt"))
                && isReadableTime(node.path("pushedAt"));

    }

    private static boolean isFilled(JsonNode value) {

        return value.isString() && !value.asString().isBlank();

    }

    private static boolean isReadableTime(JsonNode value) {

        if (value.isMissingNode() || value.isNull()) {
            return true;
        }
        if (!value.isString()) {
            return false;
        }
        try {
            Instant.parse(value.asString());
            return true;
        } catch (DateTimeParseException unreadable) {
            return false;
        }

    }

    private static GithubRepositoryResponse toResponse(JsonNode node) {

        JsonNode language = node.path("primaryLanguage").path("name");

        return new GithubRepositoryResponse(
                Long.parseLong(node.path("fullDatabaseId").asString()),
                node.path("name").asString(),
                new GithubOwnerResponse(node.path("owner").path("login").asString()),
                false,
                language.isString() ? language.asString() : null,
                node.path("defaultBranchRef").path("name").asString(),
                instantOrNull(node.path("createdAt")),
                instantOrNull(node.path("pushedAt")));

    }

    private static Instant instantOrNull(JsonNode value) {

        return value.isString() ? Instant.parse(value.asString()) : null;

    }
}
