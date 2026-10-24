package com.gitory.backend.common.infra.ratelimit;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.CandidateCardFixtures;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b",
        "gitory.cards.ai-draft.enabled=false",
        "gitory.rate-limit.enabled=true",
        "gitory.rate-limit.card-draft.per-day=1"
})
@AutoConfigureMockMvc
@Testcontainers
class RateLimitSwitchOffApiTest {

    private static final UUID MY_REPO = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redisContainer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    private TestFixtures fixtures;
    private Long myUserId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        redis.delete(redis.keys("rate:*"));

        myUserId = fixtures.insertUser(1L, "grow22");

    }

    @Test
    @DisplayName("카드 초안 스위치가 꺼져 있으면 LLM 을 부르지 않으므로 카드 만들기를 세지 않는다")
    void cardCreationIsNotCountedWhenDraftIsOff() throws Exception {

        CandidateCardFixtures candidates = new CandidateCardFixtures(jdbc);
        Long myRepoId = fixtures.insertUserRepository(MY_REPO, myUserId, fixtures.insertRepository(1L, "grow22", "gitory"));
        List<UUID> ids = List.of(candidates.publicIdOf(candidates.insertNewCandidate(myRepoId, "가")),
                candidates.publicIdOf(candidates.insertNewCandidate(myRepoId, "나")));

        mvc.perform(post("/api/repos/{id}/cards", MY_REPO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateIds\":[\"" + ids.get(0) + "\",\"" + ids.get(1) + "\"]}")
                        .with(loggedInAs()).with(csrf()))
                .andExpect(status().isOk());

        assertThat(redis.keys("rate:*")).isEmpty();

    }

    private RequestPostProcessor loggedInAs() {

        LoginUser principal = new LoginUser(myUserId, "grow22", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
