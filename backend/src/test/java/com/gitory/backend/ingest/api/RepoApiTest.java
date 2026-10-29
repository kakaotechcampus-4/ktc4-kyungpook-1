package com.gitory.backend.ingest.api;

import com.gitory.backend.consent.domain.GithubNotConnectedException;
import com.gitory.backend.consent.domain.GithubRateLimitedException;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.consent.port.GithubCountTarget;
import com.gitory.backend.consent.port.GithubOwnerResponse;
import com.gitory.backend.consent.port.GithubRepositoryCount;
import com.gitory.backend.consent.port.GithubRepositoryResponse;
import com.gitory.backend.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.client.HttpServerErrorException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b",
        // 상세의 예상 시간이 경험 그룹화 설정을 따르는지 보려고 켠다
        "gitory.analysis.grouping.enabled=true"
})
@AutoConfigureMockMvc
@Testcontainers
class RepoApiTest {

    private static final Instant CREATED = Instant.parse("2026-09-10T03:00:00Z");
    private static final Instant PUSHED = Instant.parse("2026-10-05T01:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    GithubCollectionAccessPort github;

    private TestFixtures fixtures;
    private Long myUserId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        myUserId = fixtures.insertUser(1L, "grow22");

    }

    @Test
    @DisplayName("로그인하지 않으면 401 이고 GitHub 을 부르지 않는다")
    void requiresLogin() throws Exception {

        mvc.perform(get("/api/repos"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        verifyNoInteractions(github);

    }

    @Test
    @DisplayName("다른 사용자가 같은 저장소를 연결했어도 내 연결 하나만 내 id 로 내려온다")
    void showsOnlyMyConnection() throws Exception {

        Long otherUserId = fixtures.insertUser(2L, "taehun0208");
        githubReturns(repo(100L, "team", "team-repo"));
        repos(otherUserId);

        repos(myUserId)
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(publicIdOf(myUserId).toString()));

    }

    @Test
    @DisplayName("부를 때마다 GitHub 목록을 다시 받아, 그사이 GitHub 에 새로 만든 저장소도 목록에 나온다")
    void syncsOnEveryCall() throws Exception {

        githubReturns(repo(100L, "grow22", "gitory"));
        repos(myUserId).andExpect(jsonPath("$.data.length()").value(1));

        githubReturns(repo(100L, "grow22", "gitory"), repo(200L, "grow22", "algo"));

        repos(myUserId)
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[*].name", containsInAnyOrder("gitory", "algo")));

    }

    @Test
    @DisplayName("GitHub 목록에서 빠진 저장소는 응답에서 빠지지만 DB 의 연결은 남는다")
    void hidesRepositoryMissingFromGithub() throws Exception {

        githubReturns(repo(100L, "grow22", "kept"), repo(200L, "grow22", "deleted"));
        repos(myUserId);

        githubReturns(repo(100L, "grow22", "kept"));

        repos(myUserId)
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("kept"));
        assertThat(countOf("user_repository")).isEqualTo(2);

    }

    @Test
    @DisplayName("쓸 수 있는 GitHub 연결이 없으면 200 으로 저장된 저장소를 주인·이름순으로 주고, 저장된 개수와 센 시각 그대로이며 개수를 다시 세지 않는다")
    void withoutConnectionListsStoredRepositories() throws Exception {

        connectCounted(100L, "grow22", "gitory");
        connectCounted(300L, "kakao", "board");
        fixtures.insertUserRepository(myUserId, fixtures.insertRepository(400L, "grow22", "algo"));
        given(github.repositories(anyLong())).willThrow(new GithubNotConnectedException());

        repos(myUserId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data[*].name", contains("algo", "gitory", "board")))
                .andExpect(jsonPath("$.data[0].countedAt").value(nullValue()))
                .andExpect(jsonPath("$.data[1].contribution.mine").value(43))
                .andExpect(jsonPath("$.data[1].countedAt").isString())
                .andExpect(jsonPath("$.data[1].cardCount").value(0));

        verify(github, never()).countActivity(anyLong(), any());

    }

    @Test
    @DisplayName("쓸 수 있는 연결이 없고 저장된 저장소도 없으면 200 빈 배열이다")
    void withoutConnectionAndStoredRepositoriesListsNothing() throws Exception {

        given(github.repositories(anyLong())).willThrow(new GithubNotConnectedException());

        repos(myUserId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

    }

    @Test
    @DisplayName("다른 사용자의 저장소는 연결이 없는 사용자의 저장된 목록에 섞이지 않는다")
    void storedListHoldsOnlyMyRepositories() throws Exception {

        Long otherUserId = fixtures.insertUser(2L, "taehun0208");
        fixtures.insertUserRepository(otherUserId, fixtures.insertRepository(300L, "team", "team-repo"));
        connectCounted(100L, "grow22", "gitory");
        given(github.repositories(anyLong())).willThrow(new GithubNotConnectedException());

        repos(myUserId)
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("gitory"));

    }

    @Test
    @DisplayName("GitHub 이 사용 한도로 거절해도 200 으로 저장된 저장소를 주고 개수를 다시 세지 않는다")
    void rateLimitedListsStoredRepositories() throws Exception {

        connectCounted(100L, "grow22", "gitory");
        given(github.repositories(anyLong())).willThrow(new GithubRateLimitedException());

        repos(myUserId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("gitory"))
                .andExpect(jsonPath("$.data[0].contribution.mine").value(43));

        verify(github, never()).countActivity(anyLong(), any());

    }

    @Test
    @DisplayName("그 밖의 이유로 GitHub 호출이 실패하면 500 INTERNAL_ERROR 다")
    void otherGithubFailureIsServerError() throws Exception {

        given(github.repositories(anyLong())).willThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY));

        repos(myUserId)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));

    }

    @Test
    @DisplayName("값이 비어 있어도 프론트가 요구하는 키 13개와 countedAt 은 모두 내려간다")
    void keepsEveryContractKey() throws Exception {

        githubReturns(repo(100L, "grow22", "gitory"));

        Map<String, Object> repo = JsonPath.read(bodyOf(repos(myUserId)), "$.data[0]");

        assertThat(repo).containsOnlyKeys(
                "id", "owner", "name", "contribution", "prCount", "reviewCount", "language",
                "activeFrom", "activeTo", "lastAnalyzedAt", "countedAt", "candidateCount", "cardCount", "recommended");

    }

    @Test
    @DisplayName("GitHub 값과 날짜가 담기고, 아직 못 센 개수는 0 · 후보는 null · 카드는 0 · 추천은 false 다")
    void fillsSummaryFields() throws Exception {

        githubReturns(repo(100L, "grow22", "gitory"));

        repos(myUserId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data[0].owner").value("grow22"))
                .andExpect(jsonPath("$.data[0].name").value("gitory"))
                .andExpect(jsonPath("$.data[0].language").value("Java"))
                .andExpect(jsonPath("$.data[0].activeFrom").value("2026-09-10T03:00:00Z"))
                .andExpect(jsonPath("$.data[0].activeTo").value("2026-10-05T01:00:00Z"))
                .andExpect(jsonPath("$.data[0].contribution.mine").value(0))
                .andExpect(jsonPath("$.data[0].contribution.team").value(0))
                .andExpect(jsonPath("$.data[0].contribution.ratio").value(0.0))
                .andExpect(jsonPath("$.data[0].contribution.level").value("NONE"))
                .andExpect(jsonPath("$.data[0].prCount").value(0))
                .andExpect(jsonPath("$.data[0].reviewCount").value(0))
                .andExpect(jsonPath("$.data[0].candidateCount").value(nullValue()))
                .andExpect(jsonPath("$.data[0].cardCount").value(0))
                .andExpect(jsonPath("$.data[0].recommended").value(false));

    }

    @Test
    @DisplayName("센 개수가 응답의 기여 칸·PR 수·리뷰 수·추천에 담기고, 센 시각이 countedAt 으로 내려온다")
    void returnsCountedContribution() throws Exception {

        githubReturns(repo(100L, "grow22", "gitory"));
        given(github.countActivity(anyLong(), any()))
                .willReturn(List.of(new GithubRepositoryCount(100L, 197, 52, 58, 23, 11)));

        repos(myUserId)
                .andExpect(jsonPath("$.data[0].contribution.mine").value(52))
                .andExpect(jsonPath("$.data[0].contribution.team").value(145))
                .andExpect(jsonPath("$.data[0].contribution.ratio").value(closeTo(0.264, 0.001)))
                .andExpect(jsonPath("$.data[0].contribution.level").value("SHARED"))
                .andExpect(jsonPath("$.data[0].prCount").value(23))
                .andExpect(jsonPath("$.data[0].reviewCount").value(11))
                .andExpect(jsonPath("$.data[0].recommended").value(true))
                .andExpect(jsonPath("$.data[0].countedAt").isString());

    }

    @Test
    @DisplayName("개수를 세지 못해도 목록은 200 으로 내려오고, 실제 0 과 구분되게 countedAt 은 null 이다")
    void listsEvenWhenCountFails() throws Exception {

        githubReturns(repo(100L, "grow22", "gitory"));
        given(github.countActivity(anyLong(), any())).willThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY));

        repos(myUserId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("gitory"))
                .andExpect(jsonPath("$.data[0].contribution.mine").value(0))
                .andExpect(jsonPath("$.data[0].countedAt").value(nullValue()));

    }

    @Test
    @DisplayName("센 저장소가 하나도 없으면 최근에 활동한 저장소 20개만 세고 나머지는 다음 조회로 미룬다")
    void firstCountCoversOnlyTwentyRecentRepositories() throws Exception {

        githubReturns(pushedOnDays(25));

        repos(myUserId).andExpect(jsonPath("$.data.length()").value(25));

        assertThat(countedRepoIds()).containsExactlyElementsOf(LongStream.iterate(25, day -> day - 1).limit(20).boxed().toList());

    }

    @Test
    @DisplayName("센 저장소가 하나라도 있으면 아직 안 센 저장소를 모두 센다")
    void laterCountsCoverEveryUncountedRepository() throws Exception {

        connectCounted(500L, "grow22", "counted-before");
        githubReturns(pushedOnDays(25));

        repos(myUserId);

        assertThat(countedRepoIds()).hasSize(25);

    }

    @Test
    @DisplayName("GitHub 에 마지막 push 시각이 없으면 activeTo 는 저장소를 만든 시각이다")
    void activeToFallsBackToCreatedAt() throws Exception {

        githubReturns(new GithubRepositoryResponse(100L, "gitory", new GithubOwnerResponse("grow22"),
                false, "Java", "main", CREATED, null));

        repos(myUserId)
                .andExpect(jsonPath("$.data[0].activeFrom").value("2026-09-10T03:00:00Z"))
                .andExpect(jsonPath("$.data[0].activeTo").value("2026-09-10T03:00:00Z"));

    }

    @Test
    @DisplayName("마지막 분석 시각은 분석 전에는 null 이고, 분석 뒤에는 DB 에 남은 시각이 내려온다")
    void returnsLastAnalyzedAt() throws Exception {

        githubReturns(repo(100L, "grow22", "gitory"));
        repos(myUserId).andExpect(jsonPath("$.data[0].lastAnalyzedAt").value(nullValue()));

        jdbc.update("UPDATE user_repository SET last_analyzed_at = '2026-10-05T02:00:00Z'");

        repos(myUserId).andExpect(jsonPath("$.data[0].lastAnalyzedAt").value("2026-10-05T02:00:00Z"));

    }

    @Test
    @DisplayName("목록의 id 로 분석을 요청하면 그 저장소의 Job 이 만들어진다")
    void idStartsAnalysis() throws Exception {

        githubReturns(repo(100L, "grow22", "gitory"));
        String id = JsonPath.read(bodyOf(repos(myUserId)), "$.data[0].id");

        mvc.perform(post("/api/repos/{id}/analyze", id).with(loggedInAs(myUserId)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("QUEUED"));

    }

    @Test
    @DisplayName("저장소는 주인·이름 가나다순으로 내려온다")
    void sortsByOwnerAndName() throws Exception {

        githubReturns(repo(300L, "kakao", "board"), repo(100L, "grow22", "gitory"), repo(200L, "grow22", "algo"));

        repos(myUserId)
                .andExpect(jsonPath("$.data[*].owner", contains("grow22", "grow22", "kakao")))
                .andExpect(jsonPath("$.data[*].name", contains("algo", "gitory", "board")));

    }

    @Test
    @DisplayName("커밋이 하나도 없다고 센 빈 저장소는 목록에서 빠지고, 내 커밋만 없는 저장소는 남는다")
    void hidesCountedEmptyRepository() throws Exception {

        githubReturns(repo(100L, "grow22", "gitory"), repo(200L, "grow22", "empty"));
        given(github.countActivity(anyLong(), any())).willReturn(List.of(
                new GithubRepositoryCount(100L, 197, 0, 58, 23, 11),
                new GithubRepositoryCount(200L, 0, 0, 0, 0, 0)));

        repos(myUserId).andExpect(jsonPath("$.data[*].name", contains("gitory")));

    }

    @Test
    @DisplayName("내 아이디와 이름이 같은 프로필 저장소는 대소문자가 달라도 목록에서 빠진다")
    void hidesProfileRepository() throws Exception {

        githubReturns(repo(100L, "Grow22", "Grow22"), repo(200L, "Grow22", "gitory"));

        repos(myUserId).andExpect(jsonPath("$.data[*].name", contains("gitory")));

    }

    @Test
    @DisplayName("남의 저장소는 이름이 주인 아이디나 내 아이디와 같아도 프로필 저장소가 아니라 목록에 남는다")
    void keepsSameNamedRepositoryOfOthers() throws Exception {

        githubReturns(repo(100L, "rails", "rails"), repo(200L, "other", "other"), repo(300L, "grow22", "gitory"), repo(400L, "study-org", "grow22"));

        repos(myUserId).andExpect(jsonPath("$.data[*].name", contains("gitory", "other", "rails", "grow22")));

    }

    @Test
    @DisplayName("연결이 없어 저장된 저장소를 줄 때도 빈 저장소와 내 프로필 저장소는 빠진다")
    void storedListHidesEmptyAndProfileRepositories() throws Exception {

        UUID visible = connectCounted(100L, "grow22", "gitory");
        jdbc.update("UPDATE user_repository SET own_commit_count = 0 WHERE public_id = ?", visible);
        UUID empty = UUID.randomUUID();
        fixtures.insertUserRepository(empty, myUserId, fixtures.insertRepository(200L, "grow22", "empty"));
        jdbc.update("UPDATE user_repository SET counted_at = now() WHERE public_id = ?", empty);
        fixtures.insertUserRepository(myUserId, fixtures.insertRepository(300L, "grow22", "Grow22"));
        fixtures.insertUserRepository(myUserId, fixtures.insertRepository(400L, "rails", "rails"));
        fixtures.insertUserRepository(myUserId, fixtures.insertRepository(500L, "study-org", "grow22"));
        given(github.repositories(anyLong())).willThrow(new GithubNotConnectedException());

        repos(myUserId).andExpect(jsonPath("$.data[*].name", contains("gitory", "rails", "grow22")));

    }

    @Test
    @DisplayName("저장 목록은 GitHub 을 부르지 않고, 연결이 없어도 저장된 내 저장소를 최신 목록과 같은 칸으로 준다")
    void savedListReadsOnlyDatabase() throws Exception {

        connectCounted(100L, "grow22", "gitory");
        connectCounted(300L, "kakao", "board");

        Map<String, Object> repo = JsonPath.read(bodyOf(savedRepos()), "$.data[0]");

        assertThat(repo).containsOnlyKeys(
                "id", "owner", "name", "contribution", "prCount", "reviewCount", "language",
                "activeFrom", "activeTo", "lastAnalyzedAt", "countedAt", "candidateCount", "cardCount", "recommended");
        savedRepos()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].name", contains("gitory", "board")))
                .andExpect(jsonPath("$.data[0].contribution.mine").value(43));
        verifyNoInteractions(github);

    }

    @Test
    @DisplayName("로그인하지 않으면 저장 목록도 401 이다")
    void savedListRequiresLogin() throws Exception {

        mvc.perform(get("/api/repos/saved"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

    }

    private void githubReturns(GithubRepositoryResponse... repositories) {

        given(github.repositories(anyLong())).willReturn(List.of(repositories));

    }

    @Test
    @DisplayName("로그인하지 않으면 저장소 상세도 401 이다")
    void detailRequiresLogin() throws Exception {

        mvc.perform(get("/api/repos/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

    }

    @Test
    @DisplayName("내 저장소 상세는 목록 한 줄과 같은 칸에 분석 전 안내(disclosure)를 더하고, 경험 그룹화를 켠 서버의 예상 시간까지 담아 내려온다")
    void detailAddsDisclosureToSummary() throws Exception {

        UUID id = connectCounted(100L, "gitory");

        Map<String, Object> repo = JsonPath.read(bodyOf(detail(id)), "$.data");

        assertThat(repo).containsOnlyKeys(
                "id", "owner", "name", "contribution", "prCount", "reviewCount", "language",
                "activeFrom", "activeTo", "lastAnalyzedAt", "countedAt", "candidateCount", "cardCount", "recommended",
                "disclosure");
        detail(id)
                .andExpect(jsonPath("$.data.id").value(id.toString()))
                .andExpect(jsonPath("$.data.contribution.mine").value(43))
                .andExpect(jsonPath("$.data.disclosure.reads[1]").value("카드에는 내 커밋 43개만 써요"))
                .andExpect(jsonPath("$.data.disclosure.reads[2]")
                        .value("최근 PR 50개·그보다 오래된 내 PR 최대 50개·최근 이슈 50개와 리뷰의 제목·본문 앞부분"))
                .andExpect(jsonPath("$.data.disclosure.skips.length()").value(3))
                .andExpect(jsonPath("$.data.disclosure.estimatedSeconds").value(217));

    }

    @Test
    @DisplayName("상세는 GitHub 을 부르지 않고 DB 에 저장된 값만 읽어, GitHub 목록에서 빠진 저장소도 보여 준다")
    void detailReadsOnlyDatabase() throws Exception {

        UUID id = connectCounted(100L, "deleted-on-github");

        detail(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("deleted-on-github"));
        verifyNoInteractions(github);

    }

    @Test
    @DisplayName("남의 저장소·없는 저장소·UUID 가 아닌 id 는 모두 404 NOT_FOUND 이고, 남의 저장소만 거절 기록 한 줄을 남긴다")
    void detailOfUnknownRepositoryIsNotFound() throws Exception {

        Long otherUserId = fixtures.insertUser(2L, "taehun0208");
        UUID othersId = UUID.randomUUID();
        Long othersConnection = fixtures.insertUserRepository(othersId, otherUserId,
                fixtures.insertRepository(300L, "team", "team-repo"));

        for (String id : List.of(othersId.toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
            mvc.perform(get("/api/repos/{id}", id).with(loggedInAs(myUserId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        }

        assertThat(auditRows()).containsExactly(myUserId + " VIEW_REPO " + othersConnection + " DENIED");

    }

    @Test
    @DisplayName("내 저장소 상세를 읽으면 감사 기록은 남지 않는다")
    void detailOfMyRepositoryLeavesNoAuditRow() throws Exception {

        UUID id = connectCounted(100L, "gitory");

        detail(id).andExpect(status().isOk());

        assertThat(auditRows()).isEmpty();

    }

    @Test
    @DisplayName("아직 기여 개수를 못 센 저장소는 내 커밋 수 없이 안내하고 예상 시간은 60초다")
    void detailOfUncountedRepository() throws Exception {

        UUID id = UUID.randomUUID();
        fixtures.insertUserRepository(id, myUserId, fixtures.insertRepository(100L, "grow22", "gitory"));

        detail(id)
                .andExpect(jsonPath("$.data.countedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.disclosure.reads[1]").value("카드에는 내 커밋만 써요"))
                .andExpect(jsonPath("$.data.disclosure.estimatedSeconds").value(60));

    }

    private UUID connectCounted(long githubRepoId, String name) {

        return connectCounted(githubRepoId, "grow22", name);

    }

    private UUID connectCounted(long githubRepoId, String owner, String name) {

        UUID id = UUID.randomUUID();
        fixtures.insertUserRepository(id, myUserId, fixtures.insertRepository(githubRepoId, owner, name));
        jdbc.update("""
                UPDATE user_repository SET commit_count = 188, own_commit_count = 43, pr_count = 50,
                       own_pr_count = 23, reviewed_pr_count = 11, counted_at = now()
                WHERE public_id = ?
                """, id);
        return id;

    }

    private ResultActions detail(UUID id) throws Exception {

        return mvc.perform(get("/api/repos/{id}", id).with(loggedInAs(myUserId)));

    }

    private static GithubRepositoryResponse[] pushedOnDays(int days) {

        return LongStream.rangeClosed(1, days)
                .mapToObj(day -> new GithubRepositoryResponse(day, "repo-" + day, new GithubOwnerResponse("grow22"),
                        false, "Java", "main", CREATED, CREATED.plus(Duration.ofDays(day))))
                .toArray(GithubRepositoryResponse[]::new);

    }

    @SuppressWarnings("unchecked")
    private List<Long> countedRepoIds() {

        ArgumentCaptor<List<GithubCountTarget>> targets = ArgumentCaptor.forClass(List.class);
        verify(github).countActivity(eq(myUserId), targets.capture());
        return targets.getValue().stream().map(GithubCountTarget::githubRepoId).toList();

    }

    private static GithubRepositoryResponse repo(long githubRepoId, String owner, String name) {

        return new GithubRepositoryResponse(githubRepoId, name, new GithubOwnerResponse(owner),
                false, "Java", "main", CREATED, PUSHED);

    }

    private ResultActions repos(Long userId) throws Exception {

        return mvc.perform(get("/api/repos").with(loggedInAs(userId)));

    }

    private ResultActions savedRepos() throws Exception {

        return mvc.perform(get("/api/repos/saved").with(loggedInAs(myUserId)));

    }

    private RequestPostProcessor loggedInAs(Long userId) {

        LoginUser principal = new LoginUser(userId, "grow22", null);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }

    private String bodyOf(ResultActions actions) throws Exception {

        return actions.andReturn().getResponse().getContentAsString();

    }

    private UUID publicIdOf(Long userId) {

        return jdbc.queryForObject("SELECT public_id FROM user_repository WHERE user_id = ?", UUID.class, userId);

    }

    private List<String> auditRows() {

        return jdbc.query("SELECT user_id, action, subject_id, outcome FROM audit_event ORDER BY id",
                (row, rowNum) -> row.getLong("user_id") + " " + row.getString("action") + " "
                        + row.getLong("subject_id") + " " + row.getString("outcome"));

    }

    private int countOf(String table) {

        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);

    }
}
