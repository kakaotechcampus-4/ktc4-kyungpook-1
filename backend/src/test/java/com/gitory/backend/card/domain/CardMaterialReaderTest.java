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
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b"
})
@Testcontainers
class CardMaterialReaderTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    CardMaterialReader reader;

    @Autowired
    JdbcTemplate jdbc;

    private TestFixtures fixtures;
    private Long myUserId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        myUserId = fixtures.insertUser(1L, "grow22");

    }

    @Test
    @DisplayName("작성 중인 카드·지운 카드·남의 카드는 읽지 않고, 확정 카드가 없으면 빈 목록이다")
    void readsOnlyMyLiveConfirmedCards() {

        Long mine = confirmedCard("2026-03-01T00:00:00Z", "내 카드");
        Long draft = fixtures.insertCard(UUID.randomUUID(), myUserId);
        fixtures.insertStatement(draft, 1, "A", "작성 중 행동", "USER_STATED", null);
        Long deleted = confirmedCard("2026-03-02T00:00:00Z", "지운 카드");
        jdbc.update("UPDATE card SET deleted_at = now() WHERE id = ?", deleted);
        Long othersUser = fixtures.insertUser(2L, "taehun0208");
        Long others = fixtures.insertCard(UUID.randomUUID(), othersUser);
        fixtures.insertStatement(others, 1, "A", "남의 행동", "USER_STATED", null);
        fixtures.confirmCard(others);

        List<CardMaterial> read = reader.read(myUserId, 100);
        jdbc.update("UPDATE card SET deleted_at = now() WHERE id = ?", mine);

        assertThat(read).extracting(CardMaterial::id).containsExactly(mine);
        assertThat(reader.read(myUserId, 100)).isEmpty();

    }

    @Test
    @DisplayName("지금 버전의 문장을 상황·과제·행동·결과 순서로 읽고, 카드 공개 id·제목·종류·기간을 싣는다")
    void readsCurrentVersionInStarOrder() {

        UUID publicId = UUID.randomUUID();
        Long card = fixtures.insertCard(publicId, myUserId);
        fixtures.insertStatement(card, 1, "A", "옛 행동", "USER_STATED", null);
        fixtures.insertCardVersion(card, 2, "USER_EDIT", true);
        fixtures.insertStatement(card, 2, "R", "결과", "USER_STATED", null);
        fixtures.insertStatement(card, 2, "S", "상황", "USER_STATED", null);
        fixtures.insertStatement(card, 2, "A", "새 행동", "USER_STATED", null);
        fixtures.insertStatement(card, 2, "T", "과제", "USER_STATED", null);
        fixtures.confirmCard(card);
        jdbc.update("UPDATE card SET title = '배포 시간 단축', period = '2025.09~2025.10', card_type = 'TECH' WHERE id = ?", card);

        CardMaterial material = reader.read(myUserId, 100).getFirst();

        assertThat(material.publicId()).isEqualTo(publicId);
        assertThat(material.title()).isEqualTo("배포 시간 단축");
        assertThat(material.kind()).isEqualTo(CardKind.TECH);
        assertThat(material.period()).isEqualTo("2025.09~2025.10");
        assertThat(material.statements()).extracting(CardMaterialStatement::slot, CardMaterialStatement::text)
                .containsExactly(tuple(StarField.S, "상황"), tuple(StarField.T, "과제"), tuple(StarField.A, "새 행동"),
                        tuple(StarField.R, "결과"));

    }

    @Test
    @DisplayName("제목과 문장에 카드의 가리기를 적용하고, 가린 뒤 빈 문장은 빼며, 제목이 모두 가려지면 '제목을 가린 카드' 로 보낸다")
    void appliesMaskToTitleAndStatements() {

        Long card = confirmedCard("2026-03-01T00:00:00Z", "그린랩스");
        fixtures.insertStatement(card, 1, "S", "그린랩스 서버를 만들었다", "USER_STATED", null);
        fixtures.insertStatement(card, 1, "R", "그린랩스", "USER_STATED", null);
        jdbc.update("UPDATE card SET mask_rules = ?::jsonb WHERE id = ?", "[{\"from\":\"그린랩스\",\"to\":\"\"}]", card);

        CardMaterial material = reader.read(myUserId, 100).getFirst();

        assertThat(material.title()).isEqualTo("제목을 가린 카드");
        assertThat(material.statements()).extracting(CardMaterialStatement::text)
                .containsExactly(" 서버를 만들었다", "행동");

    }

    @Test
    @DisplayName("가리기로 길어진 문장은 AI 가 받는 1,000자로 자른다")
    void cutsStatementsLongerThanTheAiLimitAfterMasking() {

        Long card = confirmedCard("2026-03-01T00:00:00Z", "카드");
        fixtures.insertStatement(card, 1, "S", "가".repeat(950) + "회사", "USER_STATED", null);
        jdbc.update("UPDATE card SET mask_rules = ?::jsonb WHERE id = ?",
                "[{\"from\":\"회사\",\"to\":\"" + "나".repeat(100) + "\"}]", card);

        CardMaterial material = reader.read(myUserId, 100).getFirst();

        assertThat(material.statements().getFirst().text()).hasSize(1_000);

    }

    @Test
    @DisplayName("상한을 넘으면 최근에 확정한 카드부터 남긴다")
    void keepsNewestConfirmedCardsWithinLimit() {

        Long january = confirmedCard("2026-01-01T00:00:00Z", "1월");
        Long march = confirmedCard("2026-03-01T00:00:00Z", "3월");
        Long february = confirmedCard("2026-02-01T00:00:00Z", "2월");

        assertThat(reader.read(myUserId, 2)).extracting(CardMaterial::id).containsExactly(march, february);
        assertThat(reader.read(myUserId, 3)).extracting(CardMaterial::id).containsExactly(march, february, january);

    }

    @Test
    @DisplayName("출처는 저장소 카드면 owner/이름, 활동 카드면 활동 이름이고, 어디에도 속하지 않으면 없다")
    void sourceNameComesFromRepositoryOrActivity() {

        Long repoCard = confirmedCard("2026-03-03T00:00:00Z", "저장소 카드");
        Long repository = fixtures.insertRepository(10L, "grow22", "gitory");
        jdbc.update("UPDATE card SET user_repository_id = ? WHERE id = ?",
                fixtures.insertUserRepository(myUserId, repository), repoCard);
        Long activityCard = confirmedCard("2026-03-02T00:00:00Z", "활동 카드");
        jdbc.update("UPDATE card SET activity_id = ? WHERE id = ?",
                fixtures.insertActivity(UUID.randomUUID(), myUserId, "교내 개발 동아리", "CLUB", "2025-03-01"), activityCard);
        confirmedCard("2026-03-01T00:00:00Z", "어디에도 없는 카드");

        assertThat(reader.read(myUserId, 100)).extracting(CardMaterial::sourceName)
                .containsExactly("grow22/gitory", "교내 개발 동아리", null);

    }

    private Long confirmedCard(String confirmedAt, String title) {

        Long card = fixtures.insertCard(UUID.randomUUID(), myUserId);
        fixtures.insertStatement(card, 1, "A", "행동", "USER_STATED", null);
        fixtures.confirmCard(card);
        jdbc.update("UPDATE card SET confirmed_at = ?::timestamptz, title = ? WHERE id = ?", confirmedAt, title, card);

        return card;

    }
}
