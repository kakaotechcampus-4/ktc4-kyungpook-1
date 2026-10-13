package com.gitory.backend.recommend.domain;

import com.gitory.backend.ingest.domain.CollectionStatsService;
import com.gitory.backend.ingest.domain.ConnectedRepositoryNotFoundException;
import com.gitory.backend.support.BoardVerdictFixtures;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({CandidateEmptyReasons.class, CollectionStatsService.class})
class CandidateEmptyReasonsTest {

    private static final UUID MY_REPO = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant COUNTED_AT = Instant.parse("2026-10-02T00:00:00Z");
    private static final String UNCOUNTED = "내 커밋 수를 아직 세지 못했어요";
    private static final String NO_PR = "PR 참여 없음";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    CandidateEmptyReasons reasons;

    @Autowired
    JdbcTemplate jdbc;

    private BoardVerdictFixtures fixtures;
    private Long myUserId;
    private Long repositoryId;
    private Long myRepoId;
    private Long otherRepositoryId;
    private int shaSeed;

    @BeforeEach
    void setUp() {

        TestFixtures base = new TestFixtures(jdbc);
        fixtures = new BoardVerdictFixtures(jdbc);
        base.clear();
        myUserId = base.insertUser(1L, "grow22");
        repositoryId = base.insertRepository(1L, "grow22", "gitory");
        otherRepositoryId = base.insertRepository(2L, "grow22", "geojero");
        myRepoId = base.insertUserRepository(MY_REPO, myUserId, repositoryId);
        shaSeed = 0;

    }

    @Test
    @DisplayName("개수를 센 저장소는 내 커밋과 팀 커밋을 GitHub 에서 센 숫자로, 팀은 전체에서 내 커밋을 뺀 값으로 보인다")
    void showsCommitLineFromGithubCounts() {

        fixtures.setCounts(myRepoId, COUNTED_AT, 197, 52, 58, 23, 11);

        assertThat(of("grow22")).startsWith("내 커밋 52개 / 팀 145개");

    }

    @Test
    @DisplayName("개수를 세지 못한 저장소는 0 을 보이지 않고 '내 커밋 수를 아직 세지 못했어요' 와 PR 줄 없이 끝난다")
    void doesNotPrintUncountedZero() {

        fixtures.setCounts(myRepoId, null, 10, 5, 4, 2, 1);

        assertThat(of("grow22")).containsExactly(UNCOUNTED);

    }

    @Test
    @DisplayName("커밋 메시지 평균은 첫 줄만 센다")
    void countsOnlyTheFirstLineOfAMessage() {

        commit("grow22", "abc\n둘째 줄은 아주 길어도 세지 않는다", null);
        commit("grow22", "abcde\r\n윈도 줄바꿈", null);

        assertThat(of("grow22")).contains("커밋 메시지 평균 4글자");

    }

    @Test
    @DisplayName("메시지가 없는 커밋은 0글자로 평균에 들어간다")
    void countsMissingMessageAsEmpty() {

        commit("grow22", "abcd", null);
        commit("grow22", null, null);

        assertThat(of("grow22")).contains("커밋 메시지 평균 2글자");

    }

    @Test
    @DisplayName("메시지가 없는 커밋뿐이어도 평균 줄은 0글자로 나온다")
    void showsZeroWhenOnlyMissingMessages() {

        commit("grow22", null, null);

        assertThat(of("grow22")).contains("커밋 메시지 평균 0글자");

    }

    @ParameterizedTest
    @CsvSource({"10, 11, 11, 11", "1, 2, 0, 2", "3, 2, 2, 2"})
    @DisplayName("평균 글자 수는 반올림한다")
    void roundsTheAverage(int first, int second, int third, int expected) {

        commit("grow22", "a".repeat(first), null);
        commit("grow22", "a".repeat(second), null);
        if (third > 0) {
            commit("grow22", "a".repeat(third), null);
        }

        assertThat(of("grow22")).contains("커밋 메시지 평균 " + expected + "글자");

    }

    @Test
    @DisplayName("이모지처럼 두 칸을 차지하는 글자도 한 글자로 센다")
    void countsCodePoints() {

        commit("grow22", "😀😀😀", null);

        assertThat(of("grow22")).contains("커밋 메시지 평균 3글자");

    }

    @Test
    @DisplayName("팀원의 커밋과 제외된 커밋과 다른 저장소의 내 커밋은 평균에 넣지 않는다")
    void ignoresTeammateExcludedAndOtherRepositoryCommits() {

        commit("grow22", "abcd", null);
        commit("teammate", "a".repeat(100), null);
        commit("grow22", "a".repeat(100), "BOT");
        fixtures.insertCommit(otherRepositoryId, sha(), "grow22", "a".repeat(100), null);

        assertThat(of("grow22")).contains("커밋 메시지 평균 4글자");

    }

    @Test
    @DisplayName("내 커밋이 하나도 없으면 평균 줄을 뺀다")
    void leavesOutAverageWithoutMyCommits() {

        commit("teammate", "abcd", null);
        fixtures.insertCommit(otherRepositoryId, sha(), "grow22", "abcd", null);
        commit(null, "abcd", null);

        assertThat(of("grow22")).noneMatch(line -> line.startsWith("커밋 메시지 평균"));

    }

    @ParameterizedTest
    @CsvSource({"Grow22, grow22", "grow22, GROW22"})
    @DisplayName("작성자 로그인은 대소문자를 구분하지 않고 비교한다")
    void matchesLoginIgnoringCase(String authorLogin, String login) {

        commit(authorLogin, "abcd", null);

        assertThat(of(login)).contains("커밋 메시지 평균 4글자");

    }

    @Test
    @DisplayName("PR 과 리뷰가 모두 0이면 'PR 참여 없음' 이다")
    void showsNoParticipationWhenBothAreZero() {

        fixtures.setCounts(myRepoId, COUNTED_AT, 10, 5, 4, 0, 0);

        assertThat(of("grow22")).contains(NO_PR);

    }

    @ParameterizedTest
    @CsvSource({"3, 0, PR 3건 · 리뷰 0건", "0, 2, PR 0건 · 리뷰 2건"})
    @DisplayName("PR 이나 리뷰 중 하나라도 있으면 둘의 건수를 보인다")
    void showsBothCountsWhenEitherIsPresent(int ownPrs, int reviewedPrs, String line) {

        fixtures.setCounts(myRepoId, COUNTED_AT, 10, 5, 4, ownPrs, reviewedPrs);

        assertThat(of("grow22")).contains(line).doesNotContain(NO_PR);

    }

    @Test
    @DisplayName("계정이 연결되지 않은 커밋이 없으면 그 줄을 뺀다")
    void leavesOutUnlinkedLineWhenNone() {

        commit("grow22", "abcd", null);
        commit("teammate", "abcd", null);

        assertThat(of("grow22")).noneMatch(line -> line.contains("계정에 연결되지 않은"));

    }

    @Test
    @DisplayName("계정이 연결되지 않은 커밋은 제외 사유가 있어도 센 개수를 마지막 줄에 보이고 다른 저장소의 것은 세지 않는다")
    void countsUnlinkedCommitsOfThisRepositoryAsLastLine() {

        fixtures.setCounts(myRepoId, COUNTED_AT, 197, 52, 58, 23, 11);
        commit("grow22", "abcd", null);
        commit(null, "한 번", null);
        commit(null, "두 번", "BOT");
        fixtures.insertCommit(otherRepositoryId, sha(), null, "다른 저장소", null);

        assertThat(of("grow22")).containsExactly("내 커밋 52개 / 팀 145개", "커밋 메시지 평균 4글자",
                "PR 23건 · 리뷰 11건", "계정에 연결되지 않은 커밋 2개는 내 커밋으로 세지 않았어요");

    }

    @Test
    @DisplayName("같은 GitHub 저장소를 연결한 다른 사용자의 숫자는 읽지 않고, 내 연결이 아니면 없는 저장소와 같이 거절한다")
    void readsOnlyMyConnectionAndRejectsOthers() {

        TestFixtures base = new TestFixtures(jdbc);
        Long teammateId = base.insertUser(2L, "teammate");
        UUID teammateRepo = UUID.fromString("44444444-4444-4444-8444-444444444444");
        Long teammateConnectionId = base.insertUserRepository(teammateRepo, teammateId, repositoryId);
        fixtures.setCounts(myRepoId, COUNTED_AT, 197, 52, 58, 23, 11);
        fixtures.setCounts(teammateConnectionId, COUNTED_AT, 197, 100, 58, 40, 30);

        assertThat(of("grow22")).startsWith("내 커밋 52개 / 팀 145개");
        assertThatThrownBy(() -> reasons.of(teammateRepo, myUserId, "grow22"))
                .isInstanceOf(ConnectedRepositoryNotFoundException.class);

    }

    private List<String> of(String login) {

        return reasons.of(MY_REPO, myUserId, login);

    }

    private void commit(String authorLogin, String message, String exclusionReason) {

        fixtures.insertCommit(repositoryId, sha(), authorLogin, message, exclusionReason);

    }

    private String sha() {

        return String.format("%040d", ++shaSeed);

    }
}
