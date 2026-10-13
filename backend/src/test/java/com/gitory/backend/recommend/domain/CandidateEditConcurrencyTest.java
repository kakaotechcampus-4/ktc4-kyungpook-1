package com.gitory.backend.recommend.domain;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.CandidateBoardFixtures;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b"
})
@AutoConfigureMockMvc
@Testcontainers
class CandidateEditConcurrencyTest {

    private static final String FINGERPRINT = "f".repeat(64);

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CandidateEditService edits;

    @Autowired
    CandidateStoreService store;

    @Autowired
    PlatformTransactionManager transactionManager;

    private CandidateBoardFixtures board;
    private ExecutorService requests;
    private Long myUserId;
    private Long teammateUserId;
    private Long repositoryId;
    private Long myRepoId;
    private Long teammateRepoId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        board = new CandidateBoardFixtures(jdbc);
        fixtures.clear();
        requests = Executors.newFixedThreadPool(8);

        myUserId = fixtures.insertUser(1L, "grow22");
        teammateUserId = fixtures.insertUser(2L, "teammate");
        repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        myRepoId = fixtures.insertUserRepository(UUID.randomUUID(), myUserId, repositoryId);
        teammateRepoId = fixtures.insertUserRepository(UUID.randomUUID(), teammateUserId, repositoryId);

    }

    @AfterEach
    void tearDown() {

        requests.shutdownNow();

    }

    @Test
    @DisplayName("다른 트랜잭션이 남의 후보를 잠가 두어도 내 요청은 기다리지 않고 404 를 답한다")
    void doesNotWaitForForeignCandidateLocks() throws Exception {

        Long foreign = cluster(teammateRepoId, "팀원 묶음", 'a');

        try (Connection locker = jdbc.getDataSource().getConnection()) {
            locker.setAutoCommit(false);
            try {
                lock(locker, foreign);

                requests.submit(() -> exclude(myUserId, foreign)).get(10, TimeUnit.SECONDS)
                        .andExpect(status().isNotFound());
            } finally {
                locker.rollback();
            }
        }

        assertThat(statusOf(foreign)).isEqualTo("NEW");

    }

    @Test
    @DisplayName("판단 전에 그 후보 한 행만 잠가서, 같은 후보에 온 두 요청은 잠금이 풀릴 때까지 기다렸다가 차례로 처리되어 둘 다 반영된다")
    void waitsForTheCandidateLockAndAppliesBothEdits() throws Exception {

        Long candidate = cluster(myRepoId, "묶음", 'a', 'b');
        Long other = cluster(myRepoId, "다른 묶음", 'c');
        Future<ResultActions> statusEdit;
        Future<ResultActions> commitEdit;

        try (Connection locker = jdbc.getDataSource().getConnection()) {
            locker.setAutoCommit(false);
            try {
                lock(locker, candidate);

                statusEdit = requests.submit(() -> exclude(myUserId, candidate));
                commitEdit = requests.submit(() -> removeCommit(myUserId, candidate, sha('a')));
                requests.submit(() -> exclude(myUserId, other)).get(10, TimeUnit.SECONDS)
                        .andExpect(status().isOk());

                assertThatThrownBy(() -> statusEdit.get(1500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                assertThat(commitEdit.isDone()).isFalse();
            } finally {
                locker.rollback();
            }
        }

        statusEdit.get(10, TimeUnit.SECONDS).andExpect(status().isOk());
        commitEdit.get(10, TimeUnit.SECONDS).andExpect(status().isOk());
        assertThat(statusOf(candidate)).isEqualTo("EXCLUDED");
        assertThat(includedOf(candidate)).containsExactly(false, true);

    }

    @Test
    @DisplayName("잠금을 기다리는 사이 다시 분석 저장이 그 후보를 지우면 404 이고 거절 기록도 남기지 않는다")
    void answers404WhenTheCandidateIsDeletedWhileWaiting() throws Exception {

        Long candidate = cluster(myRepoId, "묶음", 'a');
        Future<ResultActions> edit;

        try (Connection locker = jdbc.getDataSource().getConnection()) {
            locker.setAutoCommit(false);
            try {
                lock(locker, candidate);
                edit = requests.submit(() -> exclude(myUserId, candidate));
                assertThatThrownBy(() -> edit.get(1500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

                try (PreparedStatement delete = locker.prepareStatement("DELETE FROM candidate WHERE id = ?")) {
                    delete.setLong(1, candidate);
                    delete.executeUpdate();
                }
                locker.commit();
            } finally {
                locker.rollback();
            }
        }

        edit.get(10, TimeUnit.SECONDS).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event", Long.class)).isZero();

    }

    @Test
    @DisplayName("수정이 후보를 잠근 동안 다시 분석 저장은 기다렸다가 그 후보를 손댄 후보로 남기고 그 후보에 포함된 커밋이 든 새 묶음은 버린다")
    void storeWaitsForEditAndKeepsEditedCandidate() throws Exception {

        Long edited = cluster(myRepoId, "수정할 묶음", 'a');
        cluster(myRepoId, "손대지 않은 묶음", 'b');
        board.insertCommitBy(repositoryId, sha('c'), "grow22", null);
        UUID publicId = board.publicIdOf(edited);
        CountDownLatch editedInside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Future<?> edit = requests.submit(() -> transaction.executeWithoutResult(status -> {
            edits.edit(publicId, myUserId, "EXCLUDED", null);
            editedInside.countDown();
            awaitQuietly(release);
        }));
        assertThat(editedInside.await(10, TimeUnit.SECONDS)).isTrue();

        Future<?> stored = requests.submit(() -> store.store(myRepoId, FINGERPRINT,
                List.of(group("2026-09-01", sha('a')), group("2026-09-02", sha('c')))));
        assertThatThrownBy(() -> stored.get(1500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        release.countDown();
        edit.get(10, TimeUnit.SECONDS);
        stored.get(10, TimeUnit.SECONDS);

        assertThat(statusOf(edited)).isEqualTo("EXCLUDED");
        assertThat(jdbc.queryForObject("SELECT is_user_modified FROM candidate WHERE id = ?", Boolean.class, edited)).isTrue();
        assertThat(jdbc.queryForList("SELECT title FROM candidate WHERE id <> ? ORDER BY id", String.class, edited))
                .containsExactly("2026-09-02 작업");
        assertThat(includedOf(edited)).containsExactly(true);

    }

    @Test
    @DisplayName("여러 후보를 동시에 되돌려도 모두 200 이고 서로 기다리다 멈추지 않는다")
    void restoresManyCandidatesAtOnce() throws Exception {

        List<Long> candidates = new ArrayList<>();
        for (int n = 0; n < 6; n++) {
            Long candidate = board.insertCandidate(myRepoId, "PR", n + 1, "EXCLUDED", "후보 " + n, "0.5");
            candidates.add(candidate);
        }

        List<Future<ResultActions>> restores = candidates.stream()
                .map(candidate -> requests.submit(() -> restore(myUserId, candidate)))
                .toList();

        for (Future<ResultActions> restored : restores) {
            restored.get(30, TimeUnit.SECONDS).andExpect(status().isOk());
        }
        assertThat(jdbc.queryForList("SELECT status FROM candidate", String.class)).containsOnly("NEW").hasSize(6);

    }

    private static void lock(Connection connection, Long candidateId) throws Exception {

        try (PreparedStatement lock = connection.prepareStatement("SELECT id FROM candidate WHERE id = ? FOR UPDATE")) {
            lock.setLong(1, candidateId);
            lock.executeQuery();
        }

    }

    private static void awaitQuietly(CountDownLatch latch) {

        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

    }

    private static GroupedCandidate group(String ref, String sha) {

        return new GroupedCandidate(CandidateType.COMMIT_CLUSTER, ref, null, List.of(), List.of(), ref + " 작업",
                "같은 날 이어진 작업", 0.5, false, List.of(sha));

    }

    /** 점수 있는 커밋 묶음 후보를 만들고 글자마다 내 커밋 하나씩을 포함된 AI 출처로 잇는다 */
    private Long cluster(Long connectionId, String title, char... shaChars) {

        Long candidate = board.insertCandidate(connectionId, "COMMIT_CLUSTER", null, "NEW", title, "0.5");
        for (char c : shaChars) {
            Long commit = board.insertCommitBy(repositoryId, sha(c), "grow22", null);
            board.insertCandidateCommit(candidate, commit, true, "src/" + c + ".java");
        }

        return candidate;

    }

    private ResultActions exclude(Long userId, Long candidateId) throws Exception {

        return edit(userId, candidateId, "{\"status\":\"EXCLUDED\"}");

    }

    private ResultActions restore(Long userId, Long candidateId) throws Exception {

        return edit(userId, candidateId, "{\"status\":\"NEW\"}");

    }

    private ResultActions removeCommit(Long userId, Long candidateId, String sha) throws Exception {

        return edit(userId, candidateId, "{\"excludedShas\":[\"" + sha + "\"]}");

    }

    private ResultActions edit(Long userId, Long candidateId, String content) throws Exception {

        return mvc.perform(patch("/api/candidates/{id}", board.publicIdOf(candidateId))
                .with(loggedInAs(userId))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(content));

    }

    private RequestPostProcessor loggedInAs(Long userId) {

        String login = userId.equals(myUserId) ? "grow22" : "teammate";
        LoginUser principal = new LoginUser(userId, login, null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }

    private static String sha(char c) {

        return String.valueOf(c).repeat(40);

    }

    private String statusOf(Long candidateId) {

        return jdbc.queryForObject("SELECT status FROM candidate WHERE id = ?", String.class, candidateId);

    }

    private List<Boolean> includedOf(Long candidateId) {

        return new ArrayList<>(jdbc.queryForList(
                "SELECT is_included FROM candidate_commit WHERE candidate_id = ? ORDER BY id", Boolean.class, candidateId));

    }
}
