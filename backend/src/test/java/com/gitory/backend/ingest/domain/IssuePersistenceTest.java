package com.gitory.backend.ingest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gitory.backend.support.TestFixtures;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class IssuePersistenceTest {

    private static final Instant OPENED_AT = Instant.parse("2026-09-19T08:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    EntityManager em;

    @Autowired
    JdbcTemplate jdbc;

    private Long repositoryId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
    }

    @Test
    @DisplayName("이슈의 필드마다 맞는 값이 저장된다")
    void savesEveryField() {

        Issue saved = persist(42, GithubState.CLOSED);

        em.clear();
        Issue found = em.find(Issue.class, saved.getId());

        assertThat(found.getGithubIssueNumber()).isEqualTo(42);
        assertThat(found.getTitle()).isEqualTo("세션 조회가 느립니다");
        assertThat(found.getBodyExcerpt()).isEqualTo("응답 시간이 오래 걸립니다.");
        assertThat(found.getState()).isEqualTo(GithubState.CLOSED);
        assertThat(found.getAuthorLogin()).isEqualTo("reporter");
        assertThat(found.getLabels()).containsExactly("performance", "bug");
        assertThat(found.getOpenedAt()).isEqualTo(OPENED_AT);
        assertThat(found.getClosedAt()).isEqualTo(Instant.parse("2026-09-21T11:00:00Z"));
    }

    @Test
    @DisplayName("이슈 상태 두 가지가 모두 저장된다")
    void savesEveryState() {

        assertThat(persist(1, GithubState.OPEN).getState()).isEqualTo(GithubState.OPEN);
        assertThat(persist(2, GithubState.CLOSED).getState()).isEqualTo(GithubState.CLOSED);
    }

    @Test
    @DisplayName("같은 저장소에 같은 이슈 번호를 두 번 저장할 수 없다")
    void rejectsDuplicateNumberInSameRepository() {

        persist(42, GithubState.OPEN);

        assertThatThrownBy(() -> persist(42, GithubState.OPEN))
                .isInstanceOf(ConstraintViolationException.class);
    }

    private Issue persist(int number, GithubState state) {

        Issue issue = Issue.collected(repositoryId, null, number, "세션 조회가 느립니다",
                "응답 시간이 오래 걸립니다.", state, "reporter", List.of("performance", "bug"),
                OPENED_AT, Instant.parse("2026-09-21T11:00:00Z"));

        em.persist(issue);
        em.flush();

        return issue;
    }
}
