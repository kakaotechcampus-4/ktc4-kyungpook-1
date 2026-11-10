package com.gitory.backend.matching.domain;

import com.gitory.backend.matching.infra.PostingAlertRunRepository;
import jakarta.persistence.EntityManager;
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

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PostingAlertRunStoreTest {

    private static final Instant FIRST = Instant.parse("2026-11-07T05:00:00Z");
    private static final Instant SECOND = Instant.parse("2026-11-08T05:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    PostingAlertRunRepository runs;

    @Autowired
    EntityManager em;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("확인한 시각은 한 줄로 저장되고 옮기면 같은 줄이 바뀌어 다시 읽힌다")
    void checkedTimeIsStoredAsOneRowAndMovesForward() {

        runs.saveAndFlush(PostingAlertRun.through(FIRST));
        em.clear();
        PostingAlertRun stored = runs.findById(PostingAlertRun.ONLY_ID).orElseThrow();
        assertThat(stored.getCheckedThrough()).isEqualTo(FIRST);

        stored.advance(SECOND);
        runs.saveAndFlush(stored);
        em.clear();

        assertThat(runs.findById(PostingAlertRun.ONLY_ID).orElseThrow().getCheckedThrough()).isEqualTo(SECOND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posting_alert_run", Integer.class)).isEqualTo(1);

    }
}
