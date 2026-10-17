package com.gitory.backend.consent.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.consent.infra.GithubCountClient;
import com.gitory.backend.consent.infra.GithubRepositoryClient;
import com.gitory.backend.consent.port.GithubOwnerResponse;
import com.gitory.backend.consent.port.GithubRepositoryResponse;
import com.gitory.backend.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b"
})
@AutoConfigureMockMvc
@Testcontainers
class ContributedRepositoryListTest {

    private static final long TEAM_REPO_ID = 1340154160L;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    GithubRepositoryClient githubRepositories;

    @MockitoBean
    GithubCountClient counter;

    private Long myUserId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        myUserId = fixtures.insertUser(1L, "grow22");
        fixtures.insertGithubConnection(myUserId, "gho_StubbedAccessTokenExample1234567");

    }

    @Test
    @DisplayName("기여해서 찾은 조직 저장소가 저장소 목록에 뜨고 내 저장소로 연결되며 분석 요청이 접수된다")
    void contributedOrganizationRepositoryCanBeAnalyzed() throws Exception {

        given(githubRepositories.fetchRepositories(anyString())).willReturn(List.of(new GithubRepositoryResponse(
                100L, "gitory-notes", new GithubOwnerResponse("grow22"), false, "Java", "main", null, null)));
        given(githubRepositories.fetchContributedRepositories(anyString())).willReturn(List.of(new GithubRepositoryResponse(
                TEAM_REPO_ID, "ktc4-kyungpook-1", new GithubOwnerResponse("kakaotechcampus-4"), false, "Java", "develop",
                Instant.parse("2026-09-10T03:00:00Z"), Instant.parse("2026-10-17T10:12:00Z"))));

        String body = mvc.perform(get("/api/repos").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        List<String> teamRepoIds = JsonPath.read(body, "$.data[?(@.name == 'ktc4-kyungpook-1')].id");

        assertThat(teamRepoIds).hasSize(1);
        Map<String, Object> stored = jdbc.queryForMap("""
                SELECT r.owner_login, r.visibility, r.default_branch, ur.user_id
                FROM user_repository ur JOIN repository r ON r.id = ur.repository_id
                WHERE r.github_repo_id = ?""", TEAM_REPO_ID);
        assertThat(stored).containsEntry("owner_login", "kakaotechcampus-4")
                .containsEntry("visibility", "PUBLIC")
                .containsEntry("default_branch", "develop")
                .containsEntry("user_id", myUserId);
        mvc.perform(post("/api/repos/{id}/analyze", teamRepoIds.get(0)).with(loggedIn()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("QUEUED"));

    }

    private RequestPostProcessor loggedIn() {

        LoginUser principal = new LoginUser(myUserId, "grow22", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
