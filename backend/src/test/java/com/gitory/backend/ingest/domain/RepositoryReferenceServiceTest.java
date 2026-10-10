package com.gitory.backend.ingest.domain;

import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(RepositoryReferenceService.class)
class RepositoryReferenceServiceTest {

    private static final UUID GITORY = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID DOCS = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    RepositoryReferenceService service;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("저장소 연결 내부 id 로 그 연결의 공개 id 와 저장소 owner·이름을 돌려주고, 없는 id 면 빈 값이다")
    void findsReferenceByConnectionId() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        Long userId = fixtures.insertUser(1L, "grow22");
        // 저장소 id 와 연결 id 가 서로 달라야 어느 id 로 찾았는지 가려진다
        fixtures.insertRepository(9L, "someone", "not-connected");
        Long gitory = fixtures.insertUserRepository(GITORY, userId, fixtures.insertRepository(1L, "grow22", "gitory"));
        Long docs = fixtures.insertUserRepository(DOCS, userId,
                fixtures.insertRepository(2L, "kakaotechcampus-4", "docs"));

        assertThat(service.find(gitory)).contains(new RepositoryReference(GITORY, "grow22", "gitory"));
        assertThat(service.find(docs)).contains(new RepositoryReference(DOCS, "kakaotechcampus-4", "docs"));
        assertThat(service.find(docs + 1000)).isEmpty();

    }
}
