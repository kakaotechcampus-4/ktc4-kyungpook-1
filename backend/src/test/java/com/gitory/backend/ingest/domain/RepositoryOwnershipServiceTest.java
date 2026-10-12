package com.gitory.backend.ingest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Optional;
import java.util.UUID;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(RepositoryOwnershipService.class)
class RepositoryOwnershipServiceTest {

    private static final UUID MY_REPO = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHERS_REPO = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    RepositoryOwnershipService service;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    AuditLog auditLog;

    private TestFixtures fixtures;

    private Long myUserId;
    private Long myRepoId;
    private Long othersRepoId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);

        myUserId = fixtures.insertUser(1L, "grow22");
        Long othersUserId = fixtures.insertUser(2L, "taehun0208");
        Long repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");

        myRepoId = fixtures.insertUserRepository(MY_REPO, myUserId, repositoryId);
        othersRepoId = fixtures.insertUserRepository(OTHERS_REPO, othersUserId, repositoryId);
    }

    @Test
    @DisplayName("내 레포의 public_id 로 찾으면 내부 id 가 나온다")
    void findsMyRepository() {

        Optional<Long> found = service.findOwnedRepository(MY_REPO, myUserId, AuditAction.ANALYZE);

        assertThat(found).contains(myRepoId);

    }

    @Test
    @DisplayName("남의 레포는 public_id 가 맞아도 나오지 않는다")
    void doesNotFindOthersRepository() {

        Optional<Long> found = service.findOwnedRepository(OTHERS_REPO, myUserId, AuditAction.ANALYZE);

        assertThat(found).isEmpty();

    }

    @Test
    @DisplayName("없는 public_id 로 찾으면 비어 있다")
    void doesNotFindUnknownRepository() {

        Optional<Long> found = service.findOwnedRepository(UUID.randomUUID(), myUserId, AuditAction.ANALYZE);

        assertThat(found).isEmpty();

    }

    @Test
    @DisplayName("남의 레포를 가리키면 요청한 사람과 넘겨받은 action, 그 레포 연결의 내부 id 로 거절 기록을 남긴다")
    void recordsDeniedForOthersRepository() {

        service.findOwnedRepository(OTHERS_REPO, myUserId, AuditAction.ANALYZE);

        verify(auditLog).denied(myUserId, AuditAction.ANALYZE, othersRepoId);

    }

    @Test
    @DisplayName("내 레포나 없는 public_id 로 찾으면 거절 기록을 남기지 않는다")
    void doesNotRecordMineOrUnknown() {

        service.findOwnedRepository(MY_REPO, myUserId, AuditAction.ANALYZE);
        service.findOwnedRepository(UUID.randomUUID(), myUserId, AuditAction.ANALYZE);

        verifyNoInteractions(auditLog);

    }

}
