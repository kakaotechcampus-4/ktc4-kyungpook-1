package com.gitory.backend.ingest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;

import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Optional;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(RepositoryReadmeService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RepositoryReadmeServiceTest {

    private static final String PREVIOUS = "지난번에 고른 소개 문장이에요.";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    RepositoryReadmeService service;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    GithubCollectionAccessPort github;

    private Long userId;
    private Long connectionId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        userId = fixtures.insertUser(1L, "grow22");
        connectionId = fixtures.insertUserRepository(userId, fixtures.insertRepository(100L, "grow22", "gitory"));
        jdbc.update("UPDATE user_repository SET readme_summary = ? WHERE id = ?", PREVIOUS, connectionId);

    }

    @Test
    @DisplayName("받은 README 에서 고른 소개 문장을 저장하고, 다시 받으면 새 문장으로 바꾼다")
    void storesPickedSummary() {

        given(github.readme(userId, "grow22", "gitory"))
                .willReturn(Optional.of("# gitory\n\n커밋 기록으로 경험 카드를 만들어 주는 서비스예요. 둘째 문장이에요."))
                .willReturn(Optional.of("# gitory\n\n저장소를 분석해 경험 카드 후보를 보여 주는 서비스예요."));

        service.refresh(userId, connectionId);
        assertThat(readmeSummary()).isEqualTo("커밋 기록으로 경험 카드를 만들어 주는 서비스예요.");
        assertThat(readmeSummarySource()).isEqualTo("RULE");

        service.refresh(userId, connectionId);
        assertThat(readmeSummary()).isEqualTo("저장소를 분석해 경험 카드 후보를 보여 주는 서비스예요.");

    }

    @Test
    @DisplayName("README 가 없으면 지난번 소개 문장을 그대로 둔다")
    void keepsSummaryWhenReadmeIsMissing() {

        given(github.readme(userId, "grow22", "gitory")).willReturn(Optional.empty());

        service.refresh(userId, connectionId);

        assertThat(readmeSummary()).isEqualTo(PREVIOUS);

    }

    @Test
    @DisplayName("GitHub 에서 README 를 받지 못해도 예외를 던지지 않고 지난번 소개 문장을 그대로 둔다")
    void keepsSummaryWhenGithubFails() {

        given(github.readme(userId, "grow22", "gitory")).willThrow(new ResourceAccessException("timeout"));

        assertThatCode(() -> service.refresh(userId, connectionId)).doesNotThrowAnyException();

        assertThat(readmeSummary()).isEqualTo(PREVIOUS);

    }

    @Test
    @DisplayName("README 를 받았는데 고를 문장이 없으면 옛 README 의 문장이 남지 않게 비운다")
    void clearsSummaryWhenNothingCanBePicked() {

        given(github.readme(userId, "grow22", "gitory")).willReturn(Optional.of("# gitory\n\n- 목록뿐인 README"));

        service.refresh(userId, connectionId);

        assertThat(readmeSummary()).isNull();

    }

    @Test
    @DisplayName("사용자 설명·GitHub 설명·README 문장이 셋 다 비면 AI 요약이 필요하다고 알리고, 저장소 정보와 README 앞 3,000자를 돌려준다")
    void asksForAiSummaryWhenNothingToShow() {

        jdbc.update("UPDATE repository SET primary_language = 'Java' WHERE github_repo_id = 100");
        String readme = "# gitory\n\n- " + "a".repeat(5_000);
        given(github.readme(userId, "grow22", "gitory")).willReturn(Optional.of(readme));

        ReadmeRefresh refresh = service.refresh(userId, connectionId);

        assertThat(refresh).isEqualTo(new ReadmeRefresh(true, "grow22", "gitory", "Java", readme.substring(0, 3_000)));
        assertThat(readmeSummary()).isNull();

    }

    @ParameterizedTest
    @ValueSource(strings = {"github_description", "description"})
    @DisplayName("GitHub 설명이나 사용자가 쓴 설명이 있으면 README 문장이 없어도 AI 요약이 필요 없다")
    void needsNoAiSummaryWhenDescriptionExists(String column) {

        jdbc.update("UPDATE user_repository SET " + column + " = '이미 있는 설명' WHERE id = ?", connectionId);
        given(github.readme(userId, "grow22", "gitory")).willReturn(Optional.of("# gitory\n\n- 목록뿐인 README"));

        assertThat(service.refresh(userId, connectionId).needsAiSummary()).isFalse();

    }

    @Test
    @DisplayName("README 에서 소개 문장을 골랐으면 AI 요약이 필요 없다")
    void needsNoAiSummaryWhenRulePicksSentence() {

        given(github.readme(userId, "grow22", "gitory"))
                .willReturn(Optional.of("# gitory\n\n커밋 기록으로 경험 카드를 만들어 주는 서비스예요."));

        assertThat(service.refresh(userId, connectionId).needsAiSummary()).isFalse();

    }

    @Test
    @DisplayName("README 가 없어도 지난번에 AI 가 만든 문장이 남아 있으면 AI 요약을 다시 요청하지 않고 README 앞부분은 비어 있다")
    void keepsEarlierAiSentenceWhenReadmeIsMissing() {

        jdbc.update("UPDATE user_repository SET readme_summary = ?, readme_summary_source = 'AI' WHERE id = ?",
                "지난번에 AI 가 만든 소개 문장이에요.", connectionId);
        given(github.readme(userId, "grow22", "gitory")).willReturn(Optional.empty());

        ReadmeRefresh refresh = service.refresh(userId, connectionId);

        assertThat(refresh.needsAiSummary()).isFalse();
        assertThat(refresh.readmeHead()).isNull();
        assertThat(readmeSummary()).isEqualTo("지난번에 AI 가 만든 소개 문장이에요.");

    }

    @Test
    @DisplayName("README 를 읽다 실패하면 예외 없이 AI 요약도 요청하지 않는다")
    void asksForNoAiSummaryWhenGithubFails() {

        jdbc.update("UPDATE user_repository SET readme_summary = NULL WHERE id = ?", connectionId);
        given(github.readme(userId, "grow22", "gitory")).willThrow(new ResourceAccessException("timeout"));

        assertThat(service.refresh(userId, connectionId).needsAiSummary()).isFalse();

    }

    @Test
    @DisplayName("AI 가 만든 문장은 출처 AI 로 저장한다")
    void recordsAiSentenceWithAiSource() {

        service.recordAiSummary(connectionId, "커밋 기록으로 경험 카드를 만들어 주는 서비스예요.");

        assertThat(readmeSummary()).isEqualTo("커밋 기록으로 경험 카드를 만들어 주는 서비스예요.");
        assertThat(readmeSummarySource()).isEqualTo("AI");

    }

    private String readmeSummary() {

        return jdbc.queryForObject("SELECT readme_summary FROM user_repository WHERE id = ?", String.class, connectionId);

    }

    private String readmeSummarySource() {

        return jdbc.queryForObject("SELECT readme_summary_source FROM user_repository WHERE id = ?", String.class, connectionId);

    }
}
