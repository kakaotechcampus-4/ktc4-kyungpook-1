package com.gitory.backend.consent.infra;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GithubApiBaseUrlTest {

    private static final GithubApiProperties FAKE = new GithubApiProperties("http://fake.test:9000");

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    @Test
    @DisplayName("주소 설정이 없으면 api.github.com 을 쓴다")
    void defaultsToGithub() {

        assertThat(new GithubApiProperties(null).apiBaseUrl()).isEqualTo("https://api.github.com");
        assertThat(new GithubApiProperties(" ").apiBaseUrl()).isEqualTo("https://api.github.com");

    }

    @Test
    @DisplayName("주소 끝의 / 는 있어도 없어도 같은 주소가 된다")
    void ignoresTrailingSlash() {

        assertThat(new GithubApiProperties("http://fake.test:9000/").apiBaseUrl()).isEqualTo("http://fake.test:9000");
        assertThat(new GithubApiProperties("http://fake.test:9000").apiBaseUrl()).isEqualTo("http://fake.test:9000");

    }

    @Test
    @DisplayName("저장소 목록 클라이언트는 설정한 주소로 요청한다")
    void repositoryClientUsesConfiguredUrl() {

        server.expect(requestTo("http://fake.test:9000/user/repos?per_page=100&page=1"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        new GithubRepositoryClient(builder, FAKE).fetchRepositories("gho_test");

        server.verify();

    }

    @Test
    @DisplayName("개수 세기 클라이언트는 설정한 주소의 /graphql 로 요청한다")
    void countClientUsesConfiguredUrl() {

        server.expect(requestTo("http://fake.test:9000/graphql"))
                .andRespond(withSuccess("{\"data\":{\"viewer\":{\"id\":\"U_1\"}}}", MediaType.APPLICATION_JSON));

        new GithubCountClient(builder, FAKE).viewerId("gho_test");

        server.verify();

    }

    @Test
    @DisplayName("권한 삭제 클라이언트는 설정한 주소로 요청한다")
    void grantClientUsesConfiguredUrl() {

        server.expect(requestTo("http://fake.test:9000/applications/id/grant"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        new GithubGrantClient(builder, FAKE, "id", "secret").deleteGrant("gho_test");

        server.verify();

    }

    @Test
    @DisplayName("호출 한도 클라이언트는 설정한 주소로 요청한다")
    void rateLimitClientUsesConfiguredUrl() {

        server.expect(requestTo("http://fake.test:9000/rate_limit"))
                .andRespond(withSuccess("{\"resources\":{\"core\":{\"remaining\":0,\"reset\":1893456000}}}",
                        MediaType.APPLICATION_JSON));

        new GithubRateLimitClient(builder, FAKE).resetAt("gho_test");

        server.verify();

    }
}
