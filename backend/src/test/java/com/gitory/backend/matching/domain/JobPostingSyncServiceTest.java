package com.gitory.backend.matching.domain;

import com.gitory.backend.agent.port.ExtractedCriterion;
import com.gitory.backend.agent.port.ExtractedExperience;
import com.gitory.backend.agent.port.IndexPosting;
import com.gitory.backend.agent.port.JobMatchingIndexPort;
import com.gitory.backend.agent.port.JobPostingExtractionPort;
import com.gitory.backend.agent.port.JobPostingExtractionRequest;
import com.gitory.backend.agent.port.JobPostingExtractionResult;
import com.gitory.backend.agent.port.MatchingIndexResult;
import com.gitory.backend.agent.port.MatchingPruneResult;
import com.gitory.backend.agent.port.PostingContentStatus;
import com.gitory.backend.agent.port.PostingCriterionType;
import com.gitory.backend.agent.port.PostingExperienceLevel;
import com.gitory.backend.agent.port.PostingJobCategory;
import com.gitory.backend.matching.infra.CompanySource;
import com.gitory.backend.matching.infra.CompanySourceType;
import com.gitory.backend.matching.infra.GreenhouseClient;
import com.gitory.backend.matching.infra.GreetingClient;
import com.gitory.backend.matching.infra.JobPostingCriterionRepository;
import com.gitory.backend.matching.infra.JobPostingRepository;
import com.gitory.backend.matching.infra.JobPostingSourceRepository;
import com.gitory.backend.matching.infra.KakaoCareersClient;
import com.gitory.backend.matching.infra.KtCareersClient;
import com.gitory.backend.matching.infra.LineCareersClient;
import com.gitory.backend.matching.infra.MatchingProperties;
import com.gitory.backend.matching.infra.PearlAbyssCareersClient;
import com.gitory.backend.matching.infra.PoliteFetchProperties;
import com.gitory.backend.matching.infra.PoliteFetcher;
import com.gitory.backend.matching.infra.SmilegateCareersClient;
import com.gitory.backend.matching.infra.SyncLock;
import com.gitory.backend.matching.infra.TossCareersClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JobPostingSyncServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T05:00:00Z");
    private static final Instant EARLIER = Instant.parse("2026-10-18T05:00:00Z");
    private static final String SEOUL = "Seoul, South Korea";
    private static final CompanySource ACME = new CompanySource("acme", "에이크미", CompanySourceType.GREENHOUSE, "acme");
    private static final CompanySource BETA = new CompanySource("beta", "베타", CompanySourceType.GREENHOUSE, "beta");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JobPostingRepository postings;

    @Autowired
    JobPostingSourceRepository sources;

    @Autowired
    JobPostingCriterionRepository criteria;

    private final Map<String, String> boards = new ConcurrentHashMap<>();
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    private final List<String> hits = new CopyOnWriteArrayList<>();
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private final JobPostingExtractionPort extractionPort = mock(JobPostingExtractionPort.class);
    private final JobMatchingIndexPort indexPort = mock(JobMatchingIndexPort.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private HttpServer server;
    private String base;

    @BeforeEach
    void startServer() throws IOException {

        jdbc.execute("TRUNCATE job_posting, job_posting_source CASCADE");
        when(extractionPort.extract(any()))
                .thenReturn(JobPostingExtractionResult.unchanged("0".repeat(64), PostingContentStatus.READY));
        when(indexPort.index(any())).thenReturn(new MatchingIndexResult(0, 0, 0));
        when(indexPort.prune(any())).thenReturn(new MatchingPruneResult(0, 0));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            hits.add(path);
            if (path.startsWith("/v1/")) {
                queries.add(String.valueOf(exchange.getRequestURI().getRawQuery()));
            }
            String board = path.replaceAll("^/v1/boards/([^/]+)/jobs$", "$1");
            int status = statuses.getOrDefault(board, boards.containsKey(board) ? 200 : 404);
            byte[] body = boards.getOrDefault(board, "").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

    }

    @AfterEach
    void stopServer() {

        server.stop(0);

    }

    @Test
    @DisplayName("한국 개발 공고만 새로 저장하고 회사 이름과 원천 시각을 채운다")
    void storesNewDevelopmentPostings() {

        boards.put("acme", jobs(job(1, "Software Engineer, Backend", SEOUL, "2026-10-18T10:00:00-04:00", null),
                job(2, "Senior Manager, Treasury", SEOUL, null, null),
                job(3, "Software Engineer, Backend", "Singapore", null, null)));

        service(ACME).sync();

        List<JobPosting> saved = reload("acme");
        assertThat(saved).hasSize(1);
        JobPosting posting = saved.getFirst();
        assertThat(posting.getExternalId()).isEqualTo("1");
        assertThat(posting.getCompanyName()).isEqualTo("에이크미");
        assertThat(posting.getTitle()).isEqualTo("Software Engineer, Backend");
        assertThat(posting.getSourceUrl()).isEqualTo("https://jobs.example.com/1");
        assertThat(posting.getSourceUpdatedAt()).isEqualTo(Instant.parse("2026-10-18T14:00:00Z"));
        assertThat(posting.getStatus()).isEqualTo(JobPostingStatus.ACTIVE);
        assertThat(posting.getFirstSeenAt()).isEqualTo(NOW);
        assertThat(posting.getLastSeenAt()).isEqualTo(NOW);

    }

    @Test
    @DisplayName("이미 있는 공고가 다시 보이면 새로 만들지 않고 마지막 확인 시각과 원천 값만 바꾼다")
    void refreshesPostingSeenAgain() {

        seed("acme", "1", "Software Engineer", JobPostingStatus.ACTIVE, null);
        boards.put("acme", jobs(job(1, "Software Engineer, Backend", SEOUL, null, null)));

        service(ACME).sync();

        List<JobPosting> saved = reload("acme");
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getTitle()).isEqualTo("Software Engineer, Backend");
        assertThat(saved.getFirst().getFirstSeenAt()).isEqualTo(EARLIER);
        assertThat(saved.getFirst().getLastSeenAt()).isEqualTo(NOW);

    }

    @Test
    @DisplayName("이번 목록에 없는 활성 공고는 사라짐 상태로 바꾼다")
    void removesVanishedPostings() {

        seed("acme", "1", "Software Engineer, Backend", JobPostingStatus.ACTIVE, null);
        seed("acme", "2", "Software Engineer, Frontend", JobPostingStatus.ACTIVE, null);
        boards.put("acme", jobs(job(1, "Software Engineer, Backend", SEOUL, null, null)));

        service(ACME).sync();

        assertThat(statusesOf("acme")).containsEntry("1", JobPostingStatus.ACTIVE)
                .containsEntry("2", JobPostingStatus.REMOVED);

    }

    @Test
    @DisplayName("사라졌던 공고가 다시 나타나면 활성으로 되돌린다")
    void restoresReappearedPosting() {

        seed("acme", "1", "Software Engineer, Backend", JobPostingStatus.REMOVED, null);
        boards.put("acme", jobs(job(1, "Software Engineer, Backend", SEOUL, null, null)));

        service(ACME).sync();

        assertThat(statusesOf("acme")).containsEntry("1", JobPostingStatus.ACTIVE);

    }

    @Test
    @DisplayName("마감이 이미 지난 공고가 다시 보이면 활성으로 되살리지 않고 마감 상태로 둔다")
    void keepsOverduePostingExpiredWhenSeenAgain() {

        seed("acme", "1", "Software Engineer, Backend", JobPostingStatus.EXPIRED, Instant.parse("2026-10-10T00:00:00Z"));
        boards.put("acme", jobs(job(1, "Software Engineer, Backend", SEOUL, null, "2026-10-10T00:00:00Z")));

        service(ACME).sync();

        assertThat(statusesOf("acme")).containsEntry("1", JobPostingStatus.EXPIRED);

    }

    @ParameterizedTest(name = "활성 공고 {0}건")
    @DisplayName("활성 공고가 5건 이상인데 목록이 비면 아무것도 사라짐으로 바꾸지 않고 경고와 실패 코드만 남긴다")
    @CsvSource({"5, ACTIVE, EMPTY_LIST", "4, REMOVED,"})
    void doesNotRemoveWhenListSuddenlyEmpty(int activeCount, JobPostingStatus expected, String errorCode,
                                            CapturedOutput output) {

        IntStream.rangeClosed(1, activeCount).forEach(id ->
                seed("acme", String.valueOf(id), "Software Engineer", JobPostingStatus.ACTIVE, null));
        boards.put("acme", jobs());

        service(ACME).sync();

        assertThat(statusesOf("acme").values()).hasSize(activeCount).containsOnly(expected);
        assertThat(output.getOut().contains("목록이 비어")).isEqualTo(expected == JobPostingStatus.ACTIVE);
        assertThat(reloadSource("acme").getLastErrorCode()).isEqualTo(errorCode);

    }

    @Test
    @DisplayName("목록에 개발 아닌 공고만 남은 날에는 보호하지 않고 사라진 공고를 사라짐으로 바꾼다")
    void removesVanishedPostingsWhenOnlyNonDevelopmentPostingsRemain(CapturedOutput output) {

        IntStream.rangeClosed(1, 5).forEach(id ->
                seed("acme", String.valueOf(id), "Software Engineer", JobPostingStatus.ACTIVE, null));
        boards.put("acme", jobs(job(9, "Senior Manager, Treasury", SEOUL, null, null)));

        service(ACME).sync();

        assertThat(statusesOf("acme").values()).hasSize(5).containsOnly(JobPostingStatus.REMOVED);
        assertThat(output.getOut()).doesNotContain("목록이 비어");

    }

    @Test
    @DisplayName("가져오기가 실패하면 공고 상태는 그대로 두고 실패 코드를 남기며 성공하면 코드를 지운다")
    void keepsPostingsAndRecordsErrorCodeOnFailure() {

        seed("acme", "1", "Software Engineer, Backend", JobPostingStatus.ACTIVE, null);
        boards.put("acme", "{}");
        statuses.put("acme", 500);

        service(ACME).sync();

        assertThat(statusesOf("acme")).containsEntry("1", JobPostingStatus.ACTIVE);
        JobPostingSource failed = reloadSource("acme");
        assertThat(failed.getLastErrorCode()).isEqualTo("HTTP_STATUS");
        assertThat(failed.getLastSuccessAt()).isNull();

        statuses.remove("acme");
        boards.put("acme", jobs(job(1, "Software Engineer, Backend", SEOUL, null, null)));
        service(ACME).sync();

        JobPostingSource recovered = reloadSource("acme");
        assertThat(recovered.getLastErrorCode()).isNull();
        assertThat(recovered.getLastSuccessAt()).isEqualTo(NOW);

    }

    @Test
    @DisplayName("한 회사가 실패해도 다음 회사는 계속 가져온다")
    void continuesWithNextCompanyAfterFailure() {

        statuses.put("acme", 429);
        boards.put("acme", "{}");
        boards.put("beta", jobs(job(7, "Software Engineer, Backend", SEOUL, null, null)));

        service(ACME, BETA).sync();

        assertThat(reloadSource("acme").getLastErrorCode()).isEqualTo("HTTP_429");
        assertThat(statusesOf("beta")).containsEntry("7", JobPostingStatus.ACTIVE);

    }

    @Test
    @DisplayName("실행할 때마다 회사의 마지막 시도 시각을 새로 쓴다")
    void refreshesLastAttemptAtOnEveryRun() {

        sources.save(JobPostingSource.attempted("acme", EARLIER));
        boards.put("acme", jobs());

        service(ACME).sync();

        assertThat(reloadSource("acme").getLastAttemptAt()).isEqualTo(NOW);

    }

    @ParameterizedTest
    @DisplayName("응답 모양이 다르면 일부만 건너뛰지 않고 그 회사 전체를 PARSE 로 실패시킨다")
    @MethodSource("malformedBodies")
    void failsWithParseWhenBodyIsMalformed(String body) {

        seed("acme", "1", "Software Engineer, Backend", JobPostingStatus.ACTIVE, null);
        boards.put("acme", body);

        service(ACME).sync();

        assertThat(reloadSource("acme").getLastErrorCode()).isEqualTo("PARSE");
        assertThat(statusesOf("acme")).containsEntry("1", JobPostingStatus.ACTIVE);

    }

    @Test
    @DisplayName("날짜만 온 마감은 서울 기준 그날 끝으로 읽고 255자를 넘는 제목은 잘라 저장한다")
    void readsDateOnlyDeadlineAndTrimsLongTitle() {

        String longTitle = "Software Engineer " + "x".repeat(300);
        boards.put("acme", jobs(job(1, longTitle, SEOUL, null, "2026-10-20")));

        service(ACME).sync();

        JobPosting posting = reload("acme").getFirst();
        assertThat(posting.getTitle()).hasSize(255).startsWith("Software Engineer x");
        assertThat(posting.getExpiresAt()).isEqualTo(Instant.parse("2026-10-20T15:00:00Z"));

    }

    @Test
    @DisplayName("robots.txt 가 막은 회사는 실패 코드만 남기고 경고를 한 번 더 남기지 않는다")
    void recordsRobotsDisallowedWithoutWarning(CapturedOutput output) {

        boards.put("/robots.txt", "User-agent: *\nDisallow: /v1/\n");
        boards.put("acme", jobs(job(1, "Software Engineer, Backend", SEOUL, null, null)));

        service(ACME).sync();

        assertThat(reloadSource("acme").getLastErrorCode()).isEqualTo("ROBOTS_DISALLOWED");
        assertThat(output.getOut()).doesNotContain("공고 가져오기 실패");

    }

    @Test
    @DisplayName("저장 단계에서 예상 못 한 예외가 나도 그 회사에만 INTERNAL_ERROR 를 남기고 다음 회사는 저장한다")
    void continuesWithNextCompanyAfterUnexpectedError() {

        JobPostingRepository failing = mock(JobPostingRepository.class, AdditionalAnswers.delegatesTo(postings));
        doThrow(new IllegalStateException("저장 실패")).when(failing).findByCompanyKey("acme");
        boards.put("acme", jobs(job(1, "Software Engineer, Backend", SEOUL, null, null)));
        boards.put("beta", jobs(job(7, "Software Engineer, Backend", SEOUL, null, null)));

        service(failing, ACME, BETA).sync();

        assertThat(reloadSource("acme").getLastErrorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(reload("acme")).isEmpty();
        assertThat(statusesOf("beta")).containsEntry("7", JobPostingStatus.ACTIVE);

    }

    @Test
    @DisplayName("설정에서 빠진 회사의 활성 공고는 실행 시작 때 사라짐으로 바꾼다")
    void removesPostingsOfCompaniesNoLongerConfigured() {

        seed("gone", "1", "Software Engineer, Backend", JobPostingStatus.ACTIVE, null);
        boards.put("acme", jobs());

        service(ACME).sync();

        assertThat(statusesOf("gone")).containsEntry("1", JobPostingStatus.REMOVED);

    }

    @Test
    @DisplayName("마감 시각이 지난 활성 공고는 마감 상태로 바꾼다")
    void expiresOverduePostings() {

        seed("acme", "1", "Software Engineer, Backend", JobPostingStatus.ACTIVE, Instant.parse("2026-10-19T04:59:59Z"));
        seed("acme", "2", "Software Engineer, Frontend", JobPostingStatus.ACTIVE, Instant.parse("2026-10-30T00:00:00Z"));
        statuses.put("acme", 500);
        boards.put("acme", "{}");

        service(ACME).sync();

        assertThat(statusesOf("acme")).containsEntry("1", JobPostingStatus.EXPIRED)
                .containsEntry("2", JobPostingStatus.ACTIVE);

    }

    @Test
    @DisplayName("다른 곳이 잠금을 쥐고 있으면 아무것도 요청하지 않고 건너뛴다")
    void skipsWhenLockIsHeldElsewhere() throws SQLException {

        boards.put("acme", jobs(job(1, "Software Engineer, Backend", SEOUL, null, null)));

        try (Connection other = dataSource.getConnection()) {
            other.createStatement().execute("select pg_advisory_lock(" + SyncLock.KEY + ")");
            try {
                service(ACME).sync();
            } finally {
                other.createStatement().execute("select pg_advisory_unlock(" + SyncLock.KEY + ")");
            }
        }

        assertThat(hits).isEmpty();
        assertThat(reload("acme")).isEmpty();
        assertThat(sources.findAll()).isEmpty();

    }

    @Test
    @DisplayName("목록 요청에 content=true 를 붙이고, 받은 본문을 글로 바꿔 조건 뽑기에 넘긴다")
    void passesConvertedContentToExtraction() {

        boards.put("acme", jobs(jobWithContent(1, "Software Engineer, Backend",
                "&lt;h3&gt;자격 요건&lt;/h3&gt;&lt;ul&gt;&lt;li&gt;Java &amp;amp; Spring&lt;/li&gt;&lt;/ul&gt;")));

        service(ACME).sync();

        assertThat(queries).containsOnly("content=true");
        ArgumentCaptor<JobPostingExtractionRequest> request = ArgumentCaptor.forClass(JobPostingExtractionRequest.class);
        verify(extractionPort).extract(request.capture());
        assertThat(request.getValue().rawText()).isEqualTo("자격 요건\nJava & Spring");
        assertThat(request.getValue().source()).isEqualTo("acme");
        assertThat(request.getValue().externalPostingId()).isEqualTo("1");

    }

    @Test
    @DisplayName("그리팅 회사도 같은 흐름으로 개발 공고만 저장하고 상세 본문을 조건 뽑기에 넘긴다")
    void greetingCompanyFollowsSameFlow() {

        CompanySource greeting = new CompanySource("greet", "그리팅사", CompanySourceType.GREETING, base + "/ko/home");
        boards.put("/ko/home", greetingPage("[{\"queryKey\":[\"openings\"],\"state\":{\"data\":["
                + greetingOpening(7, "서버 개발자", "\"2026-11-30T14:59:59Z\"") + "," + greetingOpening(8, "마케팅 매니저", "null") + "]}}]"));
        boards.put("/ko/o/7", greetingPage("[{\"queryKey\":[\"career\",\"getOpeningById\"],\"state\":{\"data\":"
                + "{\"data\":{\"openingsInfo\":{\"detail\":\"<p>Java 경험</p>\"}}}}}]"));

        service(greeting).sync();

        assertThat(reload("greet")).singleElement().satisfies(posting -> {
            assertThat(posting.getExternalId()).isEqualTo("7");
            assertThat(posting.getStatus()).isEqualTo(JobPostingStatus.ACTIVE);
        });
        ArgumentCaptor<JobPostingExtractionRequest> request = ArgumentCaptor.forClass(JobPostingExtractionRequest.class);
        verify(extractionPort).extract(request.capture());
        assertThat(request.getValue().rawText()).isEqualTo("Java 경험");
        assertThat(request.getValue().source()).isEqualTo("greet");

    }

    @Test
    @DisplayName("그리팅 상세를 못 읽은 공고가 이미 저장돼 있으면 사라짐으로 바꾸지 않고 본문 해시와 조건도 그대로 둔다")
    void keepsStoredGreetingPostingWhenDetailUnreadable() {

        CompanySource greeting = new CompanySource("greet", "그리팅사", CompanySourceType.GREETING, base + "/ko/home");
        boards.put("/ko/home", greetingPage("[{\"queryKey\":[\"openings\"],\"state\":{\"data\":["
                + greetingOpening(7, "서버 개발자", "null") + "]}}]"));
        boards.put("/ko/o/7", greetingPage("[{\"queryKey\":[\"career\",\"getOpeningById\"],\"state\":{\"data\":"
                + "{\"data\":{\"openingsInfo\":{\"detail\":\"<p>Java 경험</p>\"}}}}}]"));
        when(extractionPort.extract(any())).thenReturn(JobPostingExtractionResult.updated("1".repeat(64),
                PostingContentStatus.READY,
                List.of(new ExtractedCriterion(PostingCriterionType.REQUIRED, "Java 경험", "Java")),
                PostingJobCategory.BACKEND, new ExtractedExperience(PostingExperienceLevel.UNSPECIFIED, null, null)));
        service(greeting).sync();
        statuses.put("/ko/o/7", 503);
        boards.put("/ko/home", greetingPage("[{\"queryKey\":[\"openings\"],\"state\":{\"data\":["
                + greetingOpening(7, "서버 개발자", "null") + "," + greetingOpening(8, "백엔드 개발자", "null") + "]}}]"));
        boards.put("/ko/o/8", greetingPage("[{\"queryKey\":[\"career\",\"getOpeningById\"],\"state\":{\"data\":"
                + "{\"data\":{\"openingsInfo\":{\"detail\":\"<p>Kotlin 경험</p>\"}}}}}]"));

        service(greeting).sync();

        assertThat(statusesOf("greet")).containsEntry("7", JobPostingStatus.ACTIVE).containsEntry("8", JobPostingStatus.ACTIVE);
        assertThat(reloadSource("greet").getLastErrorCode()).isNull();
        JobPosting posting = postings.findByCompanyKeyAndExternalId("greet", "7").orElseThrow();
        assertThat(posting.getContentHash()).isEqualTo("1".repeat(64));
        assertThat(criteria.findAll()).filteredOn(criterion -> criterion.getJobPostingId().equals(posting.getId()))
                .extracting(JobPostingCriterion::getText).containsExactly("Java 경험");
        ArgumentCaptor<JobPostingExtractionRequest> sent = ArgumentCaptor.forClass(JobPostingExtractionRequest.class);
        verify(extractionPort, times(2)).extract(sent.capture());
        assertThat(sent.getAllValues()).extracting(JobPostingExtractionRequest::externalPostingId).containsExactly("7", "8");

    }

    @Test
    @DisplayName("이번 실행에서 마감이 된 공고는 조건을 뽑지 않는다")
    void doesNotExtractPostingsExpiredInThisRun() {

        boards.put("acme", jobs(job(1, "Software Engineer, Backend", SEOUL, null, "2026-10-10T00:00:00Z")
                .replaceFirst("}$", ",\"content\":\"&lt;p&gt;Java&lt;/p&gt;\"}")));

        service(ACME).sync();

        assertThat(statusesOf("acme")).containsEntry("1", JobPostingStatus.EXPIRED);
        verify(extractionPort, never()).extract(any());

    }

    @Test
    @DisplayName("조건 뽑기가 끝난 다음에 뽑은 조건을 색인에 보내고 활성 공고 목록으로 정리를 한 번 부른다")
    void indexesAfterExtraction() {

        boards.put("acme", jobs(jobWithContent(1, "Software Engineer, Backend", "&lt;p&gt;Java&lt;/p&gt;")));
        when(extractionPort.extract(any())).thenReturn(JobPostingExtractionResult.updated("1".repeat(64),
                PostingContentStatus.READY,
                List.of(new ExtractedCriterion(PostingCriterionType.REQUIRED, "Java 경험", "Java")),
                PostingJobCategory.BACKEND, new ExtractedExperience(PostingExperienceLevel.UNSPECIFIED, null, null)));

        service(ACME).sync();

        long id = reload("acme").getFirst().getId();
        ArgumentCaptor<List<IndexPosting>> sent = ArgumentCaptor.captor();
        verify(indexPort).index(sent.capture());
        assertThat(sent.getValue()).singleElement().satisfies(posting -> {
            assertThat(posting.postingId()).isEqualTo(id);
            assertThat(posting.criteria()).extracting(criterion -> criterion.text()).containsExactly("Java 경험");
        });
        verify(indexPort).prune(List.of(id));

    }

    private static Stream<String> malformedBodies() {

        String valid = "\"title\":\"Software Engineer\",\"absolute_url\":\"https://jobs.example.com/1\"";
        return Stream.of("<html>점검 중</html>", "{\"jobs\":{}}",
                "{\"jobs\":[{" + valid + "}]}",
                "{\"jobs\":[{\"id\":\"\"," + valid + "}]}",
                "{\"jobs\":[{\"id\":\"" + "9".repeat(256) + "\"," + valid + "}]}",
                "{\"jobs\":[{\"id\":1,\"title\":\"Software Engineer\",\"absolute_url\":\"ftp://jobs.example.com/1\"}]}");

    }

    private JobPostingSyncService service(CompanySource... companies) {

        return service(postings, companies);

    }

    private JobPostingSyncService service(JobPostingRepository postingRepository, CompanySource... companies) {

        MatchingProperties properties = new MatchingProperties(List.of(companies), base);
        PoliteFetchProperties fetchProperties = new PoliteFetchProperties(null, null, null, Duration.ZERO, null, null);
        return new JobPostingSyncService(properties, new PoliteFetcher(fetchProperties, Clock.systemUTC()),
                new GreenhouseClient(properties), new GreetingClient(), new TossCareersClient(), new LineCareersClient(),
                new KakaoCareersClient(), new KtCareersClient(), new PearlAbyssCareersClient(), new SmilegateCareersClient(),
                postingRepository, sources,
                extraction(postingRepository),
                new JobPostingIndexService(indexPort, postingRepository, criteria, new TransactionTemplate(transactionManager)),
                new TransactionTemplate(transactionManager), new SyncLock(dataSource), clock);

    }

    private JobPostingExtractionService extraction(JobPostingRepository postingRepository) {

        return new JobPostingExtractionService(extractionPort, postingRepository, criteria,
                new TransactionTemplate(transactionManager), clock);

    }

    private void seed(String companyKey, String externalId, String title, JobPostingStatus status, Instant expiresAt) {

        JobPosting posting = JobPosting.discovered(companyKey, externalId, "옛 이름", title,
                "https://jobs.example.com/" + externalId, null, expiresAt, EARLIER);
        if (status == JobPostingStatus.REMOVED) {
            posting.markRemoved();
        } else if (status == JobPostingStatus.EXPIRED) {
            posting.expire();
        }
        postings.save(posting);

    }

    private List<JobPosting> reload(String companyKey) {

        return postings.findByCompanyKey(companyKey);

    }

    private Map<String, JobPostingStatus> statusesOf(String companyKey) {

        return reload(companyKey).stream()
                .collect(Collectors.toMap(JobPosting::getExternalId, JobPosting::getStatus));

    }

    private JobPostingSource reloadSource(String companyKey) {

        return sources.findById(companyKey).orElseThrow();

    }

    private static String jobs(String... jobs) {

        return "{\"jobs\":[" + String.join(",", jobs) + "],\"meta\":{\"total\":" + jobs.length + "}}";

    }

    private static String job(long id, String title, String location, String updatedAt, String deadline) {

        return "{\"id\":" + id + ",\"title\":\"" + title + "\",\"absolute_url\":\"https://jobs.example.com/" + id
                + "\",\"location\":{\"name\":\"" + location + "\"},\"updated_at\":" + quoted(updatedAt)
                + ",\"application_deadline\":" + quoted(deadline) + "}";

    }

    private static String jobWithContent(long id, String title, String content) {

        return job(id, title, SEOUL, null, null).replaceFirst("}$", ",\"content\":\"" + content + "\"}");

    }

    private static String greetingPage(String queries) {

        return "<html><script id=\"__NEXT_DATA__\" type=\"application/json\">"
                + "{\"props\":{\"pageProps\":{\"dehydratedState\":{\"queries\":" + queries + "}}}}</script></html>";

    }

    private static String greetingOpening(long id, String title, String dueDate) {

        return "{\"deploy\":true,\"openingId\":" + id + ",\"title\":\"" + title + "\",\"dueDate\":" + dueDate
                + ",\"openingJobPosition\":{\"openingJobPositions\":[]}}";

    }

    private static String quoted(String value) {

        return value == null ? "null" : "\"" + value + "\"";

    }
}
