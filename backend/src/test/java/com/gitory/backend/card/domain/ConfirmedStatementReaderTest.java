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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b"
})
@Testcontainers
class ConfirmedStatementReaderTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    ConfirmedStatementReader reader;

    @Autowired
    JdbcTemplate jdbc;

    private TestFixtures fixtures;
    private Long myUserId;
    private Long othersUserId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        myUserId = fixtures.insertUser(1L, "grow22");
        othersUserId = fixtures.insertUser(2L, "someone-else");

    }

    @Test
    @DisplayName("작성 중인 카드·지운 카드·남의 카드는 읽지 않고, 확정 카드가 없으면 없다고 알린다")
    void ignoresDraftDeletedAndOthersCards() {

        confirmedCard(myUserId, "2026-03-01T00:00:00Z", "행동", "결과");
        Long draft = fixtures.insertCard(UUID.randomUUID(), myUserId);
        fixtures.insertStatement(draft, 1, "A", "작성 중 행동", "USER_STATED", null);
        Long deleted = confirmedCard(myUserId, "2026-03-02T00:00:00Z", "지운 행동", "지운 결과");
        jdbc.update("UPDATE card SET deleted_at = now() WHERE id = ?", deleted);
        confirmedCard(othersUserId, "2026-03-03T00:00:00Z", "남의 행동", "남의 결과");

        ConfirmedStatementSet mine = reader.read(myUserId, 200);
        jdbc.update("UPDATE card SET deleted_at = now() WHERE user_id = ?", myUserId);
        ConfirmedStatementSet afterDelete = reader.read(myUserId, 200);

        assertThat(mine.hasConfirmedCard()).isTrue();
        assertThat(mine.statements()).extracting(ConfirmedStatement::body).containsExactly("행동", "결과");
        assertThat(afterDelete.hasConfirmedCard()).isFalse();
        assertThat(afterDelete.statements()).isEmpty();

    }

    @Test
    @DisplayName("확정 카드의 지금 버전에서 행동(A)·결과(R) 칸만 읽는다")
    void readsActionAndResultOfCurrentVersionOnly() {

        Long card = fixtures.insertCard(UUID.randomUUID(), myUserId);
        fixtures.insertStatement(card, 1, "A", "옛 행동", "USER_STATED", null);
        fixtures.insertCardVersion(card, 2, "USER_EDIT", true);
        fixtures.insertStatement(card, 2, "S", "상황", "USER_STATED", null);
        fixtures.insertStatement(card, 2, "T", "과제", "USER_STATED", null);
        fixtures.insertStatement(card, 2, "A", "새 행동", "USER_STATED", null);
        Long result = fixtures.insertStatement(card, 2, "R", "새 결과", "USER_STATED", null);
        fixtures.confirmCard(card);

        ConfirmedStatementSet set = reader.read(myUserId, 200);

        assertThat(set.statements()).extracting(ConfirmedStatement::field, ConfirmedStatement::body)
                .containsExactly(tuple(StarField.A, "새 행동"),
                        tuple(StarField.R, "새 결과"));
        assertThat(set.statements().get(1).statementId()).isEqualTo(result);

    }

    @Test
    @DisplayName("카드의 가리기를 적용하고, 가린 뒤 비는 문장은 뺀다")
    void appliesMaskAndDropsEmptySentences() {

        Long card = confirmedCard(myUserId, "2026-03-01T00:00:00Z", "그린랩스 서버를 만들었다", "그린랩스");
        jdbc.update("UPDATE card SET mask_rules = ?::jsonb WHERE id = ?",
                "[{\"from\":\"그린랩스\",\"to\":\"\"}]", card);

        ConfirmedStatementSet set = reader.read(myUserId, 200);

        assertThat(set.hasConfirmedCard()).isTrue();
        assertThat(set.statements()).extracting(ConfirmedStatement::body).containsExactly(" 서버를 만들었다");

    }

    @Test
    @DisplayName("확정 카드는 있는데 읽을 문장이 없으면 카드가 있다고 알리고 문장은 비워 둔다")
    void reportsConfirmedCardWithoutStatements() {

        Long card = fixtures.insertCard(UUID.randomUUID(), myUserId);
        fixtures.insertStatement(card, 1, "S", "상황", "USER_STATED", null);
        fixtures.confirmCard(card);

        ConfirmedStatementSet set = reader.read(myUserId, 200);

        assertThat(set.hasConfirmedCard()).isTrue();
        assertThat(set.statements()).isEmpty();

    }

    @Test
    @DisplayName("상한을 넘으면 최근에 확정한 카드의 문장부터 남기고, 가린 뒤 빈 문장은 상한 칸을 차지하지 않는다")
    void keepsNewestConfirmedCardsWithinLimit() {

        confirmedCard(myUserId, "2026-01-01T00:00:00Z", "1월 행동", "1월 결과");
        Long newest = confirmedCard(myUserId, "2026-03-01T00:00:00Z", "그린랩스", "3월 결과");
        jdbc.update("UPDATE card SET mask_rules = ?::jsonb WHERE id = ?", "[{\"from\":\"그린랩스\",\"to\":\"\"}]", newest);
        confirmedCard(myUserId, "2026-02-01T00:00:00Z", "2월 행동", "2월 결과");

        ConfirmedStatementSet set = reader.read(myUserId, 3);

        assertThat(set.statements()).extracting(ConfirmedStatement::body)
                .containsExactly("3월 결과", "2월 행동", "2월 결과");

    }

    private Long confirmedCard(Long userId, String confirmedAt, String action, String result) {

        Long card = fixtures.insertCard(UUID.randomUUID(), userId);
        fixtures.insertStatement(card, 1, "A", action, "USER_STATED", null);
        fixtures.insertStatement(card, 1, "R", result, "USER_STATED", null);
        fixtures.confirmCard(card);
        jdbc.update("UPDATE card SET confirmed_at = ?::timestamptz WHERE id = ?", confirmedAt, card);

        return card;

    }
}
