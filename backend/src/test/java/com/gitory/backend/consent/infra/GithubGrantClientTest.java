package com.gitory.backend.consent.infra;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

@RestClientTest(value = GithubGrantClient.class, properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret"
})
class GithubGrantClientTest {

    @Autowired
    GithubGrantClient client;

    @Autowired
    MockRestServiceServer server;

    @Test
    @DisplayName("앱 권한 삭제는 앱 id·secret 으로 인증해 DELETE /applications/{앱 id}/grant 에 지울 토큰을 담아 보낸다")
    void deletesGrantWithAppCredentials() {

        String basic = Base64.getEncoder()
                .encodeToString("test-client-id:test-client-secret".getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo("https://api.github.com/applications/test-client-id/grant"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Basic " + basic))
                .andExpect(jsonPath("$.access_token").value("gho_test"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.deleteGrant("gho_test");

        server.verify();

    }
}
