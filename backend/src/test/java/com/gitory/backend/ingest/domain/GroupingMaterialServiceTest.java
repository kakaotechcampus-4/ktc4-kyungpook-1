package com.gitory.backend.ingest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@Import(GroupingMaterialService.class)
class GroupingMaterialServiceTest {

    private static final Instant MONDAY = Instant.parse("2026-09-28T09:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    GroupingMaterialService service;

    @Autowired
    JdbcTemplate jdbc;

    private Long userRepositoryId;
    private Long repositoryId;
    private Long otherRepositoryId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        // 앞 테스트가 되돌린 행의 빈자리에 새 행이 들어가지 않게 표를 비워, 행이 넣은 순서대로 놓이게 한다
        fixtures.clear();
        Long userId = fixtures.insertUser(1L, "grow22");
        otherRepositoryId = fixtures.insertRepository(1L, "grow22", "other");
        repositoryId = fixtures.insertRepository(2L, "grow22", "gitory");
        jdbc.update("UPDATE repository SET default_branch = 'develop'");
        userRepositoryId = fixtures.insertUserRepository(userId, repositoryId);

    }

    @Test
    @DisplayName("작성자 로그인이 요청한 사람과 같은 커밋만 모은다 — 대소문자는 가리지 않고, 남의 커밋과 로그인이 없는 커밋은 뺀다")
    void collectsOnlyMyCommits() {

        commit("a1", "grow22", MONDAY);
        commit("a2", "GROW22", MONDAY.plusSeconds(60));
        commit("a3", "taehun0208", MONDAY.plusSeconds(120));
        jdbc.update("""
                INSERT INTO git_commit (repository_id, sha, author_name, message, authored_at)
                VALUES (?, ?, '장성준', 'feat: 계정 없는 커밋', ?)
                """, repositoryId, sha("a4"), Timestamp.from(MONDAY.plusSeconds(180)));

        GroupingMaterial material = service.load(userRepositoryId, "Grow22", Set.of(), Set.of());

        assertThat(shasOf(material)).containsExactly(sha("a1"), sha("a2"));

    }

    @Test
    @DisplayName("봇·머지·lock 파일만 바꾼 커밋처럼 제외 사유가 있는 커밋은 내 커밋이어도 뺀다")
    void skipsExcludedCommits() {

        commit("a1", "grow22", MONDAY);
        insertCommit(repositoryId, sha("a2"), "grow22", "Merge branch 'feature/login'", MONDAY, "MERGE_COMMIT", null);
        insertCommit(repositoryId, sha("a3"), "grow22", "chore: lock 갱신", MONDAY, "LOCKFILE_ONLY", null);
        insertCommit(repositoryId, sha("a4"), "grow22", "build: 라이브러리 올림", MONDAY, "BOT", null);

        assertThat(shasOf(load())).containsExactly(sha("a1"));

    }

    @Test
    @DisplayName("다른 저장소에 저장된 커밋·PR·이슈는 같은 sha·번호여도 쓰지 않는다")
    void readsOnlyThisRepository() {

        insertCommit(repositoryId, sha("a1"), "grow22", "fix: #7 #5 정리", MONDAY, null, "{7,5}");
        insertCommit(otherRepositoryId, sha("b1"), "grow22", "feat: 다른 저장소", MONDAY, null, null);
        pullRequest(otherRepositoryId, 5, "grow22", "develop", "feature/5", "{}", sha("a1"));
        issue(otherRepositoryId, 7);

        GroupingMaterial material = load();

        assertThat(material.commits())
                .extracting(MaterialCommit::sha, MaterialCommit::pullRequestNumber, MaterialCommit::issueNumbers)
                .containsExactly(tuple(sha("a1"), null, List.of()));
        assertThat(material.pullRequests()).isEmpty();
        assertThat(material.issues()).isEmpty();

    }

    @Test
    @DisplayName("넘겨받은 손댄 후보의 커밋 sha 는 뺀다")
    void skipsLockedCommits() {

        commit("a1", "grow22", MONDAY);
        commit("a2", "grow22", MONDAY.plusSeconds(60));
        commit("a3", "grow22", MONDAY.plusSeconds(120));

        GroupingMaterial material = service.load(userRepositoryId, "grow22", Set.of(sha("a2")), Set.of());

        assertThat(shasOf(material)).containsExactly(sha("a1"), sha("a3"));

    }

    @Test
    @DisplayName("커밋의 PR 이 넘겨받은 손댄 후보의 PR 이면 빼고, 손댄 PR 에도 들었지만 더 좁은 다른 PR 이 커밋의 PR 이면 모은다")
    void skipsCommitsWhosePullRequestIsLocked() {

        commit("a1", "grow22", MONDAY);
        commit("a2", "grow22", MONDAY.plusSeconds(60));
        commit("a3", "grow22", MONDAY.plusSeconds(120));
        commit("a4", "grow22", MONDAY.plusSeconds(180));
        featurePullRequest(10, "grow22", sha("a1"), sha("a2"), sha("a3"));
        featurePullRequest(12, "grow22", sha("a2"));

        GroupingMaterial material = service.load(userRepositoryId, "grow22", Set.of(), Set.of(10));

        assertThat(material.commits())
                .extracting(MaterialCommit::sha, MaterialCommit::pullRequestNumber)
                .containsExactly(tuple(sha("a2"), 12), tuple(sha("a4"), null));

    }

    @Test
    @DisplayName("PR 커밋 목록에 없는 커밋이 메시지로 손댄 후보의 PR 에 붙으면 그 커밋도 뺀다")
    void skipsCommitsAttachedToLockedPullRequestByMessage() {

        commit("b1", "grow22", MONDAY);
        insertCommit(repositoryId, sha("a1"), "grow22", "fix: PR #20 후속 수정", MONDAY.plusSeconds(60), null, "{20}");
        commit("a2", "grow22", MONDAY.plusSeconds(120));
        featurePullRequest(20, "grow22", sha("b1"));

        GroupingMaterial material = service.load(userRepositoryId, "grow22", Set.of(), Set.of(20));

        assertThat(shasOf(material)).containsExactly(sha("a2"));

    }

    @Test
    @DisplayName("작성 시각이 없는 커밋은 뺀다")
    void skipsCommitsWithoutAuthoredTime() {

        insertCommit(repositoryId, sha("a1"), "grow22", "feat: 시각 없음", null, null, null);
        commit("a2", "grow22", MONDAY);

        assertThat(shasOf(load())).containsExactly(sha("a2"));

    }

    @Test
    @DisplayName("커밋을 담은 PR 이 여럿이면 커밋이 가장 적은 PR 을, 커밋 수가 같으면 번호가 작은 PR 을 커밋의 PR 로 쓴다")
    void picksNarrowestPullRequest() {

        commit("c1", "grow22", MONDAY);
        commit("c2", "grow22", MONDAY.plusSeconds(60));
        commit("c4", "grow22", MONDAY.plusSeconds(120));
        featurePullRequest(30, "grow22", sha("c1"), sha("c2"), sha("c3"));
        featurePullRequest(32, "grow22", sha("c1"));
        featurePullRequest(41, "grow22", sha("c4"), sha("c6"));
        featurePullRequest(40, "grow22", sha("c4"), sha("c5"));
        readInInsertedOrder();

        assertThat(load().commits())
                .extracting(MaterialCommit::sha, MaterialCommit::pullRequestNumber)
                .containsExactly(tuple(sha("c1"), 32), tuple(sha("c2"), 30), tuple(sha("c4"), 40));

    }

    @Test
    @DisplayName("기본 브랜치를 다른 브랜치로 올리는 릴리스 PR 은 커밋이 가장 적어도 고르지 않고, 릴리스 PR 에만 든 커밋은 PR 없이 모은다")
    void neverPicksReleasePullRequest() {

        commit("r1", "grow22", MONDAY);
        commit("r3", "grow22", MONDAY.plusSeconds(60));
        pullRequest(repositoryId, 50, "taehun0208", "main", "develop", "{}", sha("r1"), sha("r3"));
        featurePullRequest(51, "grow22", sha("r1"), sha("r2"), sha("r4"));

        assertThat(load().commits())
                .extracting(MaterialCommit::sha, MaterialCommit::pullRequestNumber)
                .containsExactly(tuple(sha("r1"), 51), tuple(sha("r3"), null));

    }

    @Test
    @DisplayName("head 만 기본 브랜치 이름인 포크 PR 과, 기본 브랜치가 아닌 브랜치를 main 으로 올린 PR 은 릴리스 PR 이 아니다")
    void releaseNeedsBothBranchConditions() {

        commit("f1", "grow22", MONDAY);
        commit("f2", "grow22", MONDAY.plusSeconds(60));
        pullRequest(repositoryId, 54, "grow22", "develop", "develop", "{}", sha("f1"));
        pullRequest(repositoryId, 53, "grow22", "main", "hotfix/login", "{}", sha("f2"));

        assertThat(load().commits())
                .extracting(MaterialCommit::sha, MaterialCommit::pullRequestNumber)
                .containsExactly(tuple(sha("f1"), 54), tuple(sha("f2"), 53));

    }

    @Test
    @DisplayName("PR 커밋 목록에 없는 커밋은 메시지 속 '#숫자' 중 처음 나오는 내 PR 을 커밋의 PR 로 쓰고 그 PR 의 제목·본문도 모은다 — 남의 PR·릴리스 PR 은 건너뛰고 작성자 로그인은 대소문자를 가리지 않는다")
    void attachesFirstOwnPullRequestFromMessage() {

        insertCommit(repositoryId, sha("m1"), "grow22", "fix: #60 #61 #62 #63 정리", MONDAY, null, "{60,61,62,63}");
        insertCommit(repositoryId, sha("m2"), "grow22", "fix: #63 후속, #62 참고", MONDAY.plusSeconds(60), null, "{63,62}");
        featurePullRequest(60, "taehun0208", sha("x1"));
        pullRequest(repositoryId, 61, "grow22", "main", "develop", "{}", sha("x2"));
        featurePullRequest(62, "Grow22", sha("x3"));
        featurePullRequest(63, "grow22", sha("x4"));

        GroupingMaterial material = load();

        assertThat(material.commits())
                .extracting(MaterialCommit::sha, MaterialCommit::pullRequestNumber)
                .containsExactly(tuple(sha("m1"), 62), tuple(sha("m2"), 63));
        assertThat(material.pullRequests()).containsExactly(
                new MaterialPullRequest(62, "PR 62 제목", "PR 62 본문", List.of()),
                new MaterialPullRequest(63, "PR 63 제목", "PR 63 본문", List.of()));

    }

    @Test
    @DisplayName("기능 PR 이 담은 커밋은 메시지에 다른 내 PR 번호가 있어도 담은 PR 을 쓰고, 릴리스 PR 에만 든 커밋은 메시지로 PR 을 찾는다")
    void usesMessageOnlyWithoutFeaturePullRequest() {

        insertCommit(repositoryId, sha("m2"), "grow22", "feat: #62 이어서", MONDAY, null, "{62}");
        insertCommit(repositoryId, sha("m3"), "grow22", "fix: #62 후속", MONDAY.plusSeconds(60), null, "{62}");
        featurePullRequest(63, "grow22", sha("m2"));
        featurePullRequest(62, "grow22", sha("x3"));
        pullRequest(repositoryId, 61, "taehun0208", "main", "develop", "{}", sha("m3"));

        assertThat(load().commits())
                .extracting(MaterialCommit::sha, MaterialCommit::pullRequestNumber)
                .containsExactly(tuple(sha("m2"), 63), tuple(sha("m3"), 62));

    }

    @Test
    @DisplayName("이슈 번호는 issue 표에 있는 번호만 저장된 순서대로 모으고 아직 채우지 않은 커밋은 빈 목록이다 — PR 의 연결 이슈도 같다")
    void keepsOnlyIssueNumbersInIssueTable() {

        issue(repositoryId, 7);
        issue(repositoryId, 9);
        insertCommit(repositoryId, sha("i1"), "grow22", "fix: #9 PR #60 #7 #99", MONDAY, null, "{9,60,7,99}");
        commit("i2", "grow22", MONDAY.plusSeconds(60));
        pullRequest(repositoryId, 60, "taehun0208", "develop", "feature/60", "{7,61,100}", sha("i2"));

        GroupingMaterial material = load();

        assertThat(material.commits())
                .extracting(MaterialCommit::sha, MaterialCommit::pullRequestNumber, MaterialCommit::issueNumbers)
                .containsExactly(tuple(sha("i1"), null, List.of(9, 7)), tuple(sha("i2"), 60, List.of()));
        assertThat(material.pullRequests())
                .extracting(MaterialPullRequest::number, MaterialPullRequest::linkedIssueNumbers)
                .containsExactly(tuple(60, List.of(7)));

    }

    @Test
    @DisplayName("커밋의 메시지·작성자·숫자 칸·부모 수와 바뀐 파일은 저장된 값 그대로이고, 파일은 저장한 순서대로 모은다")
    void copiesStoredValues() {

        Long full = jdbc.queryForObject("""
                INSERT INTO git_commit (repository_id, sha, author_login, author_name, message, authored_at, additions,
                                        deletions, changed_files, parent_count, issue_numbers)
                VALUES (?, ?, 'grow22', '장성준', ?, ?, 12, 3, 4, 1, '{}')
                RETURNING id
                """, Long.class, repositoryId, sha("s1"), "feat: 세션 조회 개선\n\n인덱스 추가", Timestamp.from(MONDAY));
        jdbc.update("""
                INSERT INTO git_commit (repository_id, sha, author_login, message, authored_at, parent_count)
                VALUES (?, ?, 'grow22', 'init', ?, 0)
                """, repositoryId, sha("s2"), Timestamp.from(MONDAY.plusSeconds(60)));
        Long others = insertCommit(repositoryId, sha("t1"), "taehun0208", "feat: 남의 커밋", MONDAY, null, "{}");
        // id 를 넣은 순서·경로 순서와 다르게 주어, 표에 놓인 순서나 경로 인덱스 순서가 아니라 저장 순서(id)로 읽는지 본다
        file(900_030L, full, "backend/b/Session.java", "MODIFIED", 10, 2);
        file(900_015L, others, "backend/m/Other.java", "MODIFIED", 1, 1);
        file(900_010L, full, "backend/c/index.sql", "ADDED", 2, 1);
        file(900_020L, full, "backend/a/Schema.sql", "REMOVED", 0, 7);

        assertThat(load().commits()).containsExactly(
                new MaterialCommit(sha("s1"), "feat: 세션 조회 개선\n\n인덱스 추가", "grow22", "장성준", MONDAY, 1, 12, 3, 4,
                        List.of(new MaterialFile("backend/c/index.sql", FileChangeStatus.ADDED, 2, 1),
                                new MaterialFile("backend/a/Schema.sql", FileChangeStatus.REMOVED, 0, 7),
                                new MaterialFile("backend/b/Session.java", FileChangeStatus.MODIFIED, 10, 2)),
                        null, List.of()),
                new MaterialCommit(sha("s2"), "init", "grow22", null, MONDAY.plusSeconds(60), 0, null, null, null,
                        List.of(), null, List.of()));

    }

    @Test
    @DisplayName("메시지가 없는 커밋은 빈 글자로 모은다")
    void sendsEmptyTextForMissingMessage() {

        insertCommit(repositoryId, sha("a1"), "grow22", null, MONDAY, null, null);

        assertThat(load().commits()).extracting(MaterialCommit::message).containsExactly("");

    }

    @Test
    @DisplayName("PR·이슈의 제목·본문은 모으는 커밋과 PR 이 가리키는 번호만 번호순으로 모은다")
    void collectsOnlyReferencedContexts() {

        issue(repositoryId, 8);
        issue(repositoryId, 9);
        issue(repositoryId, 10);
        issue(repositoryId, 11);
        issue(repositoryId, 12);
        insertCommit(repositoryId, sha("c1"), "grow22", "feat: #9 시작", MONDAY, null, "{9}");
        insertCommit(repositoryId, sha("c2"), "grow22", "feat: #8 화면", MONDAY.plusSeconds(60), null, "{8}");
        insertCommit(repositoryId, sha("c3"), "grow22", "fix: #11 정리", MONDAY.plusSeconds(120), null, "{11}");
        commit("t1", "taehun0208", MONDAY.plusSeconds(180));
        pullRequest(repositoryId, 72, "grow22", "develop", "feature/72", "{12}", sha("c1"));
        pullRequest(repositoryId, 70, "grow22", "develop", "feature/70", "{8}", sha("c2"));
        pullRequest(repositoryId, 71, "taehun0208", "develop", "feature/71", "{10}", sha("t1"));
        pullRequest(repositoryId, 73, "grow22", "develop", "feature/73", "{}", sha("c3"));
        pullRequest(repositoryId, 74, "taehun0208", "main", "develop", "{}", sha("c1"), sha("c2"), sha("c3"));
        readInInsertedOrder();

        GroupingMaterial material = service.load(userRepositoryId, "grow22", Set.of(sha("c3")), Set.of());

        assertThat(material.pullRequests()).containsExactly(
                new MaterialPullRequest(70, "PR 70 제목", "PR 70 본문", List.of(8)),
                new MaterialPullRequest(72, "PR 72 제목", "PR 72 본문", List.of(12)));
        assertThat(material.issues()).containsExactly(
                new MaterialIssue(8, "이슈 8 제목", "이슈 8 본문"),
                new MaterialIssue(9, "이슈 9 제목", "이슈 9 본문"),
                new MaterialIssue(12, "이슈 12 제목", "이슈 12 본문"));

    }

    @Test
    @DisplayName("커밋은 작성 시각 순으로, 시각이 같으면 sha 순으로 모아 저장된 값이 같으면 늘 같은 순서다")
    void ordersCommitsByTimeThenSha() {

        commit("d0", "grow22", MONDAY.plusSeconds(120));
        commit("b0", "grow22", MONDAY.plusSeconds(60));
        commit("a0", "grow22", MONDAY.plusSeconds(60));
        commit("c0", "grow22", MONDAY.plusSeconds(60));
        commit("e0", "grow22", MONDAY);
        readInInsertedOrder();

        assertThat(shasOf(load())).containsExactly(sha("e0"), sha("a0"), sha("b0"), sha("c0"), sha("d0"));

    }

    @Test
    @DisplayName("40자보다 짧게 저장된 sha 도 뒤 공백 없이 모으고, PR 커밋 목록·넘겨받은 sha 와 같은 커밋으로 맞춰 본다")
    void matchesShortShas() {

        insertCommit(repositoryId, "abc1234567890", "grow22", "feat: 짧은 sha", MONDAY, null, null);
        insertCommit(repositoryId, "def1234567890", "grow22", "feat: 손댄 짧은 sha", MONDAY, null, null);
        featurePullRequest(80, "grow22", "abc1234567890");

        GroupingMaterial material = service.load(userRepositoryId, "grow22", Set.of("def1234567890"), Set.of());

        assertThat(material.commits())
                .extracting(MaterialCommit::sha, MaterialCommit::pullRequestNumber)
                .containsExactly(tuple("abc1234567890", 80));

    }

    @Test
    @DisplayName("분석 전이라 저장된 커밋이 없으면 커밋·PR·이슈 모두 빈 목록이다")
    void returnsEmptyBeforeAnalysis() {

        GroupingMaterial material = load();

        assertThat(material.commits()).isEmpty();
        assertThat(material.pullRequests()).isEmpty();
        assertThat(material.issues()).isEmpty();

    }

    @Test
    @DisplayName("저장된 수가 아니라 모은 내 커밋·PR·이슈 수를 로그로 남긴다")
    void logsCollectedCounts(CapturedOutput output) {

        issue(repositoryId, 5);
        issue(repositoryId, 6);
        insertCommit(repositoryId, sha("a1"), "grow22", "feat: #5 시작", MONDAY, null, "{5}");
        commit("a2", "grow22", MONDAY.plusSeconds(60));
        commit("a3", "grow22", MONDAY.plusSeconds(120));
        featurePullRequest(90, "grow22", sha("a1"));
        featurePullRequest(91, "grow22", sha("x9"));

        service.load(userRepositoryId, "grow22", Set.of(sha("a3")), Set.of());

        assertThat(output).contains("내 커밋 2개, PR 1개, 이슈 1개");

    }

    private GroupingMaterial load() {

        return service.load(userRepositoryId, "grow22", Set.of(), Set.of());

    }

    private static List<String> shasOf(GroupingMaterial material) {

        return material.commits().stream().map(MaterialCommit::sha).toList();

    }

    private void commit(String shaPrefix, String login, Instant authoredAt) {

        insertCommit(repositoryId, sha(shaPrefix), login, "feat: " + shaPrefix, authoredAt, null, null);

    }

    private Long insertCommit(Long repositoryId, String sha, String login, String message, Instant authoredAt,
                              String exclusionReason, String issueNumbers) {

        return jdbc.queryForObject("""
                INSERT INTO git_commit (repository_id, sha, author_login, message, authored_at, is_excluded,
                                        exclusion_reason, issue_numbers)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::int[])
                RETURNING id
                """, Long.class, repositoryId, sha, login, message,
                authoredAt == null ? null : Timestamp.from(authoredAt), exclusionReason != null, exclusionReason,
                issueNumbers);

    }

    private void featurePullRequest(int number, String author, String... shas) {

        pullRequest(repositoryId, number, author, "develop", "feature/" + number, "{}", shas);

    }

    private void pullRequest(Long repositoryId, int number, String author, String base, String head,
                             String linkedIssueNumbers, String... shas) {

        jdbc.update("""
                INSERT INTO pull_request (repository_id, github_pr_number, title, body_excerpt, state, author_login,
                                          base_branch, head_branch, opened_at, commit_shas, linked_issue_numbers)
                VALUES (?, ?, ?, ?, 'CLOSED', ?, ?, ?, ?, ?::text[], ?::int[])
                """, repositoryId, number, "PR " + number + " 제목", "PR " + number + " 본문", author, base, head,
                Timestamp.from(MONDAY), "{" + String.join(",", shas) + "}", linkedIssueNumbers);

    }

    private void issue(Long repositoryId, int number) {

        jdbc.update("""
                INSERT INTO issue (repository_id, github_issue_number, title, body_excerpt, state, opened_at)
                VALUES (?, ?, ?, ?, 'OPEN', ?)
                """, repositoryId, number, "이슈 " + number + " 제목", "이슈 " + number + " 본문", Timestamp.from(MONDAY));

    }

    private void file(Long id, Long commitId, String path, String status, int additions, int deletions) {

        jdbc.update("""
                INSERT INTO commit_file (id, commit_id, path, status, additions, deletions) OVERRIDING SYSTEM VALUE
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, commitId, path, status, additions, deletions);

    }

    /** 인덱스로 읽으면 번호·sha 순으로 나와 정렬을 빼먹어도 맞을 수 있어, 표에 넣은 순서대로 읽게 한다 */
    private void readInInsertedOrder() {

        jdbc.execute("SET LOCAL enable_indexscan = off");
        jdbc.execute("SET LOCAL enable_indexonlyscan = off");
        jdbc.execute("SET LOCAL enable_bitmapscan = off");

    }

    private static String sha(String prefix) {

        return (prefix + "0".repeat(40)).substring(0, 40);

    }
}
