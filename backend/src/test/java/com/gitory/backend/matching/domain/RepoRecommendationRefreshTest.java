package com.gitory.backend.matching.domain;

import com.gitory.backend.agent.port.MatchJudgementPort;
import com.gitory.backend.card.domain.CardConfirmService;
import com.gitory.backend.card.domain.CardDeleteService;
import com.gitory.backend.card.domain.CardMaskService;
import com.gitory.backend.card.domain.CardMetadataService;
import com.gitory.backend.card.domain.CardReopenService;
import com.gitory.backend.card.domain.MaskRule;
import com.gitory.backend.matching.infra.RepoRecommendationRepository;
import com.gitory.backend.profile.domain.ProfileInput;
import com.gitory.backend.profile.domain.ProfileService;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b"
})
@Testcontainers
class RepoRecommendationRefreshTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RepoRecommendationRepository recommendations;

    @Autowired
    RepoRecommendationTrigger trigger;

    @Autowired
    CardConfirmService confirm;

    @Autowired
    CardReopenService reopen;

    @Autowired
    CardMetadataService metadata;

    @Autowired
    CardMaskService mask;

    @Autowired
    CardDeleteService delete;

    @Autowired
    ProfileService profiles;

    @Autowired
    Clock clock;

    @MockitoBean
    MatchJudgementPort port;

    private TestFixtures fixtures;
    private Long userId;
    private Long repo;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        userId = fixtures.insertUser(1L, "grow22");
        repo = fixtures.insertUserRepository(userId, fixtures.insertRepository(10L, "grow22", "gitory"));

    }

    @Test
    @DisplayName("확정·다시 열기·확정 카드 제목 바꾸기·가리기·삭제가 커밋되면 그 레포를 '다시 계산 필요' 로 표시한다")
    void marksRepositoryStaleWhenConfirmedCardsChange() {

        List<Consumer<UUID>> changes = List.of(
                card -> confirm.confirm(card, userId),
                card -> reopen.reopen(card, userId),
                card -> metadata.edit(card, userId, "새 제목", null, null),
                card -> mask.mask(card, userId, List.of(new MaskRule("그린랩스", "회사"))),
                card -> delete.delete(card, userId));
        for (int i = 0; i < changes.size(); i++) {
            jdbc.update("DELETE FROM repo_recommendation");
            UUID card = card(i > 0);

            changes.get(i).accept(card);

            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM repo_recommendation
                    WHERE user_repository_id = ? AND user_id = ? AND status = 'COMPUTING' AND stale_since IS NOT NULL
                    """, Integer.class, repo, userId)).as("변경 %d", i).isEqualTo(1);
        }

    }

    @Test
    @DisplayName("작성 중 카드의 제목만 바뀌면 추천에 쓰이지 않는 카드라 표시하지 않는다")
    void doesNotMarkWhenOnlyDraftChanges() {

        UUID draft = card(false);

        metadata.edit(draft, userId, "새 제목", null, null);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM repo_recommendation", Integer.class)).isZero();

    }

    @Test
    @DisplayName("마지막 변경 뒤 1분이 지난 레포만 계산하고, 계산하려고 집으면 표시를 지운다")
    void computesOnlyRepositoriesQuietForAMinute() {

        Long quietRepo = repo;
        Long busyRepo = fixtures.insertUserRepository(userId, fixtures.insertRepository(11L, "grow22", "blog"));
        Instant now = Instant.now(clock);
        stale(quietRepo, now.minusSeconds(61));
        stale(busyRepo, now.minusSeconds(30));
        RepoRecommendationService service = mock(RepoRecommendationService.class);

        new RepoRecommendationWorker(recommendations, service, clock, Runnable::run).drain();

        verify(service).compute(userId, quietRepo);
        verify(service, never()).compute(userId, busyRepo);
        assertThat(staleSince(quietRepo)).isNull();
        assertThat(staleSince(busyRepo)).isNotNull();

    }

    @Test
    @DisplayName("한쪽이 이미 집은 표시는 다시 집지 못해, 같은 레포를 두 번 계산하지 않는다")
    void claimsAStaleMarkOnlyOnce() {

        Instant now = Instant.now(clock);
        stale(repo, now.minusSeconds(120));
        Long id = recommendations.findByUserRepositoryId(repo).orElseThrow().getId();

        assertThat(recommendations.claim(id, now.minusSeconds(60))).isEqualTo(1);
        assertThat(recommendations.claim(id, now.minusSeconds(60))).isZero();

    }

    @Test
    @DisplayName("계산이 예상하지 못한 이유로 멈추면 그 레포 추천을 실패로 둔다")
    void marksFailedWhenComputeBreaks() {

        stale(repo, Instant.now(clock).minusSeconds(61));
        RepoRecommendationService service = mock(RepoRecommendationService.class);
        doThrow(new IllegalStateException("고장")).when(service).compute(anyLong(), anyLong());

        new RepoRecommendationWorker(recommendations, service, clock, Runnable::run).drain();

        verify(service).markFailed(userId, repo);

    }

    @Test
    @DisplayName("인적사항을 저장하면 그 사용자가 받은 레포 추천을 모두 다시 계산할 차례로 두고, 다른 사용자 것은 그대로다")
    void marksEveryRecommendationOfTheUserStaleWhenProfileChanges() {

        Long blog = fixtures.insertUserRepository(userId, fixtures.insertRepository(11L, "grow22", "blog"));
        Long otherUser = fixtures.insertUser(2L, "other");
        Long otherRepo = fixtures.insertUserRepository(otherUser, fixtures.insertRepository(12L, "other", "shop"));
        ready(userId, repo);
        ready(userId, blog);
        ready(otherUser, otherRepo);

        profiles.replace(userId, new ProfileInput(List.of(), List.of(), List.of(), List.of(), List.of("BACKEND")));

        assertThat(jdbc.queryForList("""
                SELECT user_repository_id FROM repo_recommendation WHERE status = 'COMPUTING' AND stale_since IS NOT NULL
                """, Long.class)).containsExactlyInAnyOrder(repo, blog);
        assertThat(staleSince(otherRepo)).isNull();

    }

    @Test
    @DisplayName("초안 Job 이 끝나 바로 계산을 부탁하면 1분을 기다리지 않고 다음 차례에 계산한다")
    void requestNowIsComputedOnTheNextRound() {

        trigger.requestNow(userId, repo);
        RepoRecommendationService service = mock(RepoRecommendationService.class);

        new RepoRecommendationWorker(recommendations, service, clock, Runnable::run).drain();

        verify(service).compute(userId, repo);

    }

    // 상황·행동 문장이 있는 카드를 이 레포에 만들고, confirmed 면 확정해 둔다
    private UUID card(boolean confirmed) {

        UUID publicId = UUID.randomUUID();
        Long card = fixtures.insertCard(publicId, userId);
        jdbc.update("UPDATE card SET user_repository_id = ? WHERE id = ?", repo, card);
        fixtures.insertStatement(card, 1, "S", "그린랩스 결제가 두 번 나갔다", "USER_STATED", null);
        fixtures.insertStatement(card, 1, "A", "멱등 키를 두었다", "USER_STATED", null);
        if (confirmed) {
            fixtures.confirmCard(card);
        }
        return publicId;

    }

    private void ready(Long owner, Long userRepositoryId) {

        jdbc.update("""
                INSERT INTO repo_recommendation (user_id, user_repository_id, status, companies, stale_since)
                VALUES (?, ?, 'READY', '[]', NULL)
                """, owner, userRepositoryId);

    }

    private void stale(Long userRepositoryId, Instant at) {

        jdbc.update("""
                INSERT INTO repo_recommendation (user_id, user_repository_id, status, companies, stale_since)
                VALUES (?, ?, 'COMPUTING', '[]', ?)
                """, userId, userRepositoryId, Timestamp.from(at));

    }

    private Timestamp staleSince(Long userRepositoryId) {

        return jdbc.queryForObject("SELECT stale_since FROM repo_recommendation WHERE user_repository_id = ?",
                Timestamp.class, userRepositoryId);

    }
}
