package com.gitory.backend.card.domain;

import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b"
})
@Testcontainers
class MatchingCardReaderTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MatchingCardReader reader;

    @Autowired
    JdbcTemplate jdbc;

    private TestFixtures fixtures;
    private Long userId;
    private Long repo;
    private Long otherRepo;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        userId = fixtures.insertUser(1L, "grow22");
        repo = fixtures.insertUserRepository(userId, fixtures.insertRepository(10L, "grow22", "gitory"));
        otherRepo = fixtures.insertUserRepository(userId, fixtures.insertRepository(11L, "grow22", "blog"));

    }

    @Test
    @DisplayName("그 레포의 지우지 않은 작성 중 카드와 확정 카드를 모두, 지금 버전 글에 가리기를 적용해 칸마다 이어 붙이고 기술 이름표와 함께 읽는다")
    void readsLiveDraftAndConfirmedCardsOfTheRepositoryWithMaskedSlots() {

        Long confirmed = card(repo, true);
        fixtures.insertCardVersion(confirmed, 2, "USER_EDIT", true);
        fixtures.insertStatement(confirmed, 2, "A", "그린랩스 결제 API 를 만들었다", "USER_STATED", null);
        jdbc.update("INSERT INTO card_statement (card_id, version_no, star_slot, seq, body, evidence_type) "
                + "VALUES (?, 2, 'A', 2, ?, 'USER_STATED')", confirmed, "멱등 키를 두었다");
        fixtures.insertStatement(confirmed, 2, "R", "중복 0건", "USER_STATED", null);
        jdbc.update("UPDATE card SET mask_rules = ?::jsonb, techs = ARRAY['JAVA','SPRING'] WHERE id = ?",
                "[{\"from\":\"그린랩스\",\"to\":\"회사\"}]", confirmed);
        Long draft = card(repo, false);
        fixtures.insertStatement(draft, 1, "S", "작성 중에도 그린랩스 결제가 두 번 나갔다", "USER_STATED", null);
        card(otherRepo, true);
        Long deleted = card(repo, true);
        jdbc.update("UPDATE card SET deleted_at = now() WHERE id = ?", deleted);

        List<MatchingCard> read = reader.allOf(userId, repo);

        assertThat(read).extracting(MatchingCard::id).containsExactly(confirmed, draft);
        assertThat(read.getFirst()).satisfies(matching -> {
            assertThat(matching.action()).isEqualTo("회사 결제 API 를 만들었다 멱등 키를 두었다");
            assertThat(matching.result()).isEqualTo("중복 0건");
            assertThat(matching.situation()).isNull();
            assertThat(matching.techs()).containsExactly(CardTech.JAVA, CardTech.SPRING);
        });
        assertThat(read.getLast().situation()).isEqualTo("작성 중에도 그린랩스 결제가 두 번 나갔다");

    }

    @Test
    @DisplayName("레포를 가리지 않고 그 사용자의 지우지 않은 확정 카드만 id 순서로 읽고, 남의 확정 카드는 읽지 않는다")
    void readsLiveConfirmedCardsOfEveryRepositoryOfTheUser() {

        Long first = card(repo, true);
        Long second = card(otherRepo, true);
        card(repo, false);
        Long deleted = card(otherRepo, true);
        jdbc.update("UPDATE card SET deleted_at = now() WHERE id = ?", deleted);
        Long others = fixtures.insertCard(UUID.randomUUID(), fixtures.insertUser(2L, "taehun0208"));
        fixtures.confirmCard(others);

        assertThat(reader.confirmedOf(userId)).extracting(MatchingCard::id).containsExactly(first, second);

    }

    private Long card(Long userRepositoryId, boolean confirmed) {

        Long card = fixtures.insertCard(UUID.randomUUID(), userId);
        jdbc.update("UPDATE card SET user_repository_id = ? WHERE id = ?", userRepositoryId, card);
        if (confirmed) {
            fixtures.confirmCard(card);
        }
        return card;

    }
}
