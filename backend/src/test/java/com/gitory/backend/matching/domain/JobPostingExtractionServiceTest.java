package com.gitory.backend.matching.domain;

import com.gitory.backend.agent.port.ExtractedCriterion;
import com.gitory.backend.agent.port.ExtractedExperience;
import com.gitory.backend.agent.port.JobPostingExtractionPort;
import com.gitory.backend.agent.port.JobPostingExtractionRequest;
import com.gitory.backend.agent.port.JobPostingExtractionResult;
import com.gitory.backend.agent.port.PostingContentStatus;
import com.gitory.backend.agent.port.PostingCriterionType;
import com.gitory.backend.agent.port.PostingExperienceLevel;
import com.gitory.backend.agent.port.PostingExtractionUnavailableException;
import com.gitory.backend.agent.port.PostingJobCategory;
import com.gitory.backend.matching.infra.JobPostingCriterionRepository;
import com.gitory.backend.matching.infra.JobPostingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JobPostingExtractionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T05:00:00Z");
    private static final Instant EARLIER = Instant.parse("2026-10-18T05:00:00Z");
    private static final String OLD_HASH = "a".repeat(64);
    private static final String NEW_HASH = "b".repeat(64);
    private static final String TEXT = "담당 업무: 서버 개발\n자격 요건: Java 경험\n우대: AWS 경험";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JobPostingRepository postings;

    @Autowired
    JobPostingCriterionRepository criteria;

    private final List<JobPostingExtractionRequest> calls = new ArrayList<>();
    private final Map<String, Supplier<JobPostingExtractionResult>> answers = new HashMap<>();

    @BeforeEach
    void clean() {

        jdbc.execute("TRUNCATE job_posting CASCADE");

    }

    @Test
    @DisplayName("아직 한 번도 못 뽑은 공고는 이전 해시와 상태 없이 공고 정보와 본문만 보낸다")
    void sendsNoPreviousValuesForPendingPosting() {

        seedPending("1");

        service().extractAll(List.of(target("1", TEXT)));

        assertThat(calls).containsExactly(new JobPostingExtractionRequest("acme", "1", "Software Engineer, Backend",
                "https://jobs.example.com/1", TEXT, null, null));

    }

    @Test
    @DisplayName("이미 뽑은 공고는 저장된 해시와 내용 상태를 붙여 보낸다")
    void sendsStoredHashAndStatusForExtractedPosting() {

        seedExtracted("1", JobPostingContentStatus.METADATA_ONLY, 0);

        service().extractAll(List.of(target("1", TEXT)));

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.previousContentHash()).isEqualTo(OLD_HASH);
            assertThat(call.previousContentStatus()).isEqualTo(PostingContentStatus.METADATA_ONLY);
        });

    }

    @Test
    @DisplayName("UPDATED 결과는 분류와 경력과 해시와 내용 상태를 공고에 반영하고 조건을 종류별로 저장한다")
    void storesUpdatedResult() {

        seedPending("1");
        answers.put("1", () -> updated(NEW_HASH,
                criterion(PostingCriterionType.RESPONSIBILITY, "서버 개발", "서버 개발"),
                criterion(PostingCriterionType.REQUIRED, "Java 경험", "Java 경험"),
                criterion(PostingCriterionType.PREFERRED, "AWS 경험", "AWS 경험")));

        service().extractAll(List.of(target("1", TEXT)));

        JobPosting posting = reload("1");
        assertThat(posting.getCategory()).isEqualTo(JobCategory.BACKEND);
        assertThat(posting.getExperienceLevel()).isEqualTo(ExperienceLevel.EXPERIENCED);
        assertThat(posting.getExperienceMinYears()).isEqualTo(3);
        assertThat(posting.getExperienceQuote()).isEqualTo("경력 3년 이상");
        assertThat(posting.getContentHash()).isEqualTo(NEW_HASH);
        assertThat(posting.getContentStatus()).isEqualTo(JobPostingContentStatus.READY);
        assertThat(posting.getExtractedAt()).isEqualTo(NOW);
        assertThat(criteriaOf(posting)).extracting(row -> row.get("type"))
                .containsExactly("RESPONSIBILITY", "REQUIRED", "PREFERRED");

    }

    @Test
    @DisplayName("조건 순서는 보낸 글에서 인용이 처음 나오는 위치 순이고 위치가 같으면 AI 가 준 순서를 따른다")
    void ordersCriteriaByQuotePosition() {

        seedPending("1");
        answers.put("1", () -> updated(NEW_HASH,
                criterion(PostingCriterionType.PREFERRED, "AWS 경험", "AWS 경험"),
                criterion(PostingCriterionType.REQUIRED, "Java 능숙", "Java 경험"),
                criterion(PostingCriterionType.PREFERRED, "Java 경험 우대", "Java 경험"),
                criterion(PostingCriterionType.RESPONSIBILITY, "서버 개발", "서버 개발")));

        service().extractAll(List.of(target("1", TEXT)));

        assertThat(criteriaOf(reload("1"))).extracting(row -> row.get("text"), row -> row.get("seq"))
                .containsExactly(tuple("서버 개발", 1), tuple("Java 능숙", 2), tuple("Java 경험 우대", 3),
                        tuple("AWS 경험", 4));

    }

    @Test
    @DisplayName("UPDATED 가 다시 오면 옛 조건을 모두 지우고 새 조건으로 통째로 바꾸며 조건 id 도 새로 생긴다")
    void replacesAllCriteriaOnUpdate() {

        seedExtracted("1", JobPostingContentStatus.READY, 2);
        List<Object> oldIds = criteriaOf(reload("1")).stream().map(row -> row.get("public_id")).toList();
        answers.put("1", () -> updated(NEW_HASH, criterion(PostingCriterionType.REQUIRED, "Java 경험", "Java 경험")));

        service().extractAll(List.of(target("1", TEXT)));

        assertThat(criteriaOf(reload("1"))).hasSize(1).allSatisfy(row -> {
            assertThat(row.get("text")).isEqualTo("Java 경험");
            assertThat(oldIds).doesNotContain(row.get("public_id"));
        });

    }

    @Test
    @DisplayName("UPDATED 인데 조건이 하나도 없으면 옛 조건을 지우고 내용 상태를 METADATA_ONLY 로 바꾼다")
    void clearsCriteriaWhenNoCriteriaRemain() {

        seedExtracted("1", JobPostingContentStatus.READY, 2);
        answers.put("1", () -> JobPostingExtractionResult.updated(NEW_HASH, PostingContentStatus.METADATA_ONLY,
                List.of(), PostingJobCategory.ETC, new ExtractedExperience(PostingExperienceLevel.UNSPECIFIED, null, null)));

        service().extractAll(List.of(target("1", TEXT)));

        JobPosting posting = reload("1");
        assertThat(posting.getContentStatus()).isEqualTo(JobPostingContentStatus.METADATA_ONLY);
        assertThat(posting.getExperienceLevel()).isEqualTo(ExperienceLevel.UNSPECIFIED);
        assertThat(criteriaOf(posting)).isEmpty();

    }

    @Test
    @DisplayName("본문 글은 공고 표 어느 칸에도 저장하지 않는다")
    void doesNotStoreBodyText() {

        seedPending("1");
        String marker = "본문에만있는문장-7c1f";
        answers.put("1", () -> updated(NEW_HASH, criterion(PostingCriterionType.REQUIRED, "Java 경험", "Java 경험")));

        service().extractAll(List.of(target("1", "Java 경험\n" + marker)));

        Integer rows = jdbc.queryForObject("select count(*) from job_posting p where cast(row_to_json(p) as text) like ?",
                Integer.class, "%" + marker + "%");
        assertThat(rows).isZero();

    }

    @Test
    @DisplayName("UNCHANGED 이면 공고와 조건을 그대로 둔다")
    void keepsPostingOnUnchanged() {

        seedExtracted("1", JobPostingContentStatus.READY, 2);
        List<Object> oldIds = criteriaOf(reload("1")).stream().map(row -> row.get("public_id")).toList();
        answers.put("1", () -> JobPostingExtractionResult.unchanged(OLD_HASH, PostingContentStatus.READY));

        service().extractAll(List.of(target("1", TEXT)));

        JobPosting posting = reload("1");
        assertThat(posting.getContentHash()).isEqualTo(OLD_HASH);
        assertThat(posting.getExtractedAt()).isEqualTo(EARLIER);
        assertThat(criteriaOf(posting)).extracting(row -> row.get("public_id")).containsExactlyElementsOf(oldIds);

    }

    @Test
    @DisplayName("거절(REJECTED)이면 해시를 남기지 않아 처음 보는 공고는 PENDING 으로 남고 옛 조건은 그대로다")
    void leavesNoHashOnRejection() {

        seedPending("1");
        seedExtracted("2", JobPostingContentStatus.READY, 2);
        answers.put("1", JobPostingExtractionResult::rejected);
        answers.put("2", JobPostingExtractionResult::rejected);

        service().extractAll(List.of(target("1", TEXT), target("2", TEXT)));

        assertThat(reload("1").getContentStatus()).isEqualTo(JobPostingContentStatus.PENDING);
        assertThat(reload("1").getContentHash()).isNull();
        assertThat(reload("2").getContentHash()).isEqualTo(OLD_HASH);
        assertThat(criteriaOf(reload("2"))).hasSize(2);

    }

    @ParameterizedTest
    @MethodSource("recoverableFailures")
    @DisplayName("뽑기가 실패하면 옛 조건을 그대로 두고 같은 실행에서 다시 시도하지 않고 다음 공고로 넘어간다")
    void keepsOldCriteriaAndMovesOnAfterFailure(Supplier<JobPostingExtractionResult> failure) {

        seedExtracted("1", JobPostingContentStatus.READY, 2);
        seedPending("2");
        answers.put("1", failure);
        answers.put("2", () -> updated(NEW_HASH, criterion(PostingCriterionType.REQUIRED, "Java 경험", "Java 경험")));

        service().extractAll(List.of(target("1", TEXT), target("2", TEXT)));

        assertThat(calls).extracting(JobPostingExtractionRequest::externalPostingId).containsExactly("1", "2");
        assertThat(reload("1").getContentHash()).isEqualTo(OLD_HASH);
        assertThat(criteriaOf(reload("1"))).hasSize(2);
        assertThat(reload("2").getContentHash()).isEqualTo(NEW_HASH);

    }

    @Test
    @DisplayName("저장 중에 DB 오류가 나면 그 공고의 옛 조건이 그대로 남고 다음 공고는 계속 저장한다")
    void rollsBackStoreFailureAndContinues() {

        seedExtracted("1", JobPostingContentStatus.READY, 2);
        seedPending("2");
        answers.put("1", () -> updated(NEW_HASH, criterion(PostingCriterionType.REQUIRED, "가".repeat(1001), "Java 경험")));
        answers.put("2", () -> updated(NEW_HASH, criterion(PostingCriterionType.REQUIRED, "Java 경험", "Java 경험")));

        service().extractAll(List.of(target("1", TEXT), target("2", TEXT)));

        assertThat(reload("1").getContentHash()).isEqualTo(OLD_HASH);
        assertThat(criteriaOf(reload("1"))).hasSize(2);
        assertThat(reload("2").getContentHash()).isEqualTo(NEW_HASH);

    }

    @Test
    @DisplayName("다시 해도 같은 LLM 실패가 연달아 3번이면 남은 공고는 부르지 않고 경고를 한 번만 남긴다")
    void stopsAfterThreeSameFailuresInARow(CapturedOutput output) {

        IntStream.rangeClosed(1, 5).forEach(id -> {
            seedPending(String.valueOf(id));
            answers.put(String.valueOf(id), JobPostingExtractionServiceTest::permanentFailure);
        });

        service().extractAll(targets(5));

        assertThat(calls).hasSize(3);
        assertThat(count(output, "공고 조건 뽑기를 멈춘다: 다시 해도 같은 AI 실패 LLM_UNAVAILABLE 연속 3번")).isEqualTo(1);

    }

    @ParameterizedTest
    @MethodSource("streakBreakers")
    @DisplayName("같은 실패의 연속이 성공이나 다른 실패로 끊기면 횟수를 0 부터 다시 센다")
    void restartsCountWhenStreakIsBroken(Supplier<JobPostingExtractionResult> breaker, CapturedOutput output) {

        List<Supplier<JobPostingExtractionResult>> sequence = List.of(
                JobPostingExtractionServiceTest::permanentFailure, JobPostingExtractionServiceTest::permanentFailure,
                breaker, JobPostingExtractionServiceTest::permanentFailure,
                JobPostingExtractionServiceTest::permanentFailure);
        for (int index = 0; index < sequence.size(); index++) {
            seedPending(String.valueOf(index + 1));
            answers.put(String.valueOf(index + 1), sequence.get(index));
        }

        service().extractAll(targets(5));

        assertThat(calls).hasSize(5);
        assertThat(output.getOut()).doesNotContain("공고 조건 뽑기를 멈춘다");

    }

    @Test
    @DisplayName("본문이 100000자를 넘거나 비어 있으면 AI 를 부르지 않고 경고를 남긴 뒤 건너뛴다")
    void skipsTooLongOrBlankText(CapturedOutput output) {

        seedPending("1");
        seedPending("2");

        service().extractAll(List.of(target("1", "가".repeat(100_001)), target("2", "  ")));

        assertThat(calls).isEmpty();
        assertThat(count(output, "공고 조건 뽑기 건너뜀")).isEqualTo(2);
        assertThat(output.getOut()).contains("실패 2");

    }

    @Test
    @DisplayName("AI 를 부르지 않고 건너뛴 공고는 같은 실패의 연속 횟수를 끊지 않는다")
    void textSkipDoesNotBreakSameFailureStreak(CapturedOutput output) {

        seedPending("1");
        seedPending("2");
        seedPending("3");
        seedPending("4");
        answers.put("1", JobPostingExtractionServiceTest::permanentFailure);
        answers.put("2", JobPostingExtractionServiceTest::permanentFailure);
        answers.put("4", JobPostingExtractionServiceTest::permanentFailure);

        service().extractAll(List.of(target("1", TEXT), target("2", TEXT), target("3", "  "), target("4", TEXT)));

        assertThat(calls).hasSize(3);
        assertThat(count(output, "공고 조건 뽑기를 멈춘다")).isEqualTo(1);

    }

    @Test
    @DisplayName("본문이 정확히 100000자이면 AI 에 보낸다")
    void sendsTextAtTheLimit() {

        seedPending("1");

        service().extractAll(List.of(target("1", "가".repeat(100_000))));

        assertThat(calls).hasSize(1);

    }

    @Test
    @DisplayName("활성이 아니거나 저장되지 않은 공고는 AI 를 부르지 않는다")
    void skipsInactiveOrUnknownPostings(CapturedOutput output) {

        seedPending("1");
        JobPosting expired = reload("1");
        expired.expire();
        postings.save(expired);

        service().extractAll(List.of(target("1", TEXT), target("3", TEXT)));

        assertThat(calls).isEmpty();
        assertThat(output.getOut()).contains("실패 0").doesNotContain("INTERNAL_ERROR");

    }

    @Test
    @DisplayName("목록에 있는 순서대로 한 번에 하나씩 차례로 부른다")
    void callsOneByOneInOrder() {

        IntStream.rangeClosed(1, 3).forEach(id -> seedPending(String.valueOf(id)));

        service().extractAll(targets(3));

        assertThat(calls).extracting(JobPostingExtractionRequest::externalPostingId).containsExactly("1", "2", "3");

    }

    @Test
    @DisplayName("끝에 새로·그대로·거절·실패 개수를 한 줄로 남긴다")
    void logsSummaryLine(CapturedOutput output) {

        IntStream.rangeClosed(1, 4).forEach(id -> seedPending(String.valueOf(id)));
        answers.put("1", () -> updated(NEW_HASH, criterion(PostingCriterionType.REQUIRED, "Java 경험", "Java 경험")));
        answers.put("2", () -> JobPostingExtractionResult.unchanged(OLD_HASH, PostingContentStatus.READY));
        answers.put("3", JobPostingExtractionResult::rejected);
        answers.put("4", () -> failure(true));

        service().extractAll(targets(4));

        assertThat(output.getOut()).contains("공고 조건 뽑기: 새로 1 · 그대로 1 · 거절 1 · 실패 1");

    }

    static Stream<Arguments> recoverableFailures() {

        return Stream.of(
                argumentSet("다시 시도할 수 있는 AI 실패", (Supplier<JobPostingExtractionResult>) () -> failure(true)),
                argumentSet("이상한 응답", (Supplier<JobPostingExtractionResult>) () -> failure(false)),
                argumentSet("예상 못 한 예외", (Supplier<JobPostingExtractionResult>) () -> {
                    throw new IllegalStateException("예상 못 한 오류");
                }));

    }

    static Stream<Arguments> streakBreakers() {

        return Stream.of(
                argumentSet("성공", (Supplier<JobPostingExtractionResult>) () -> updated(NEW_HASH,
                        criterion(PostingCriterionType.REQUIRED, "Java 경험", "Java 경험"))),
                argumentSet("다시 시도할 수 있는 실패", (Supplier<JobPostingExtractionResult>) () -> failure(true)));

    }

    private JobPostingExtractionService service() {

        JobPostingExtractionPort port = request -> {
            calls.add(request);
            return answers.getOrDefault(request.externalPostingId(),
                    () -> JobPostingExtractionResult.unchanged(OLD_HASH, PostingContentStatus.READY)).get();
        };
        return new JobPostingExtractionService(port, postings, criteria, new TransactionTemplate(transactionManager),
                Clock.fixed(NOW, ZoneOffset.UTC));

    }

    private static JobPostingExtractionResult permanentFailure() {

        throw new PostingExtractionUnavailableException("AI 공고 조건 뽑기 실패 (LLM_UNAVAILABLE)", false, true,
                "LLM_UNAVAILABLE", null);

    }

    private static JobPostingExtractionResult failure(boolean retryable) {

        throw new PostingExtractionUnavailableException("AI 공고 조건 뽑기 실패", retryable, !retryable,
                retryable ? "AI_UNAVAILABLE" : "AI_INVALID_RESPONSE", null);

    }

    private static JobPostingExtractionResult updated(String hash, ExtractedCriterion... criteria) {

        return JobPostingExtractionResult.updated(hash, PostingContentStatus.READY, List.of(criteria),
                PostingJobCategory.BACKEND,
                new ExtractedExperience(PostingExperienceLevel.EXPERIENCED, 3, "경력 3년 이상"));

    }

    private static ExtractedCriterion criterion(PostingCriterionType type, String text, String quote) {

        return new ExtractedCriterion(type, text, quote);

    }

    private static ExtractionTarget target(String externalId, String text) {

        return new ExtractionTarget("acme", externalId, text);

    }

    private static List<ExtractionTarget> targets(int count) {

        return IntStream.rangeClosed(1, count).mapToObj(id -> target(String.valueOf(id), TEXT)).toList();

    }

    private void seedPending(String externalId) {

        postings.save(JobPosting.discovered("acme", externalId, "에이크미", "Software Engineer, Backend",
                "https://jobs.example.com/" + externalId, null, null, EARLIER));

    }

    private void seedExtracted(String externalId, JobPostingContentStatus status, int criteriaCount) {

        JobPosting posting = JobPosting.discovered("acme", externalId, "에이크미", "Software Engineer, Backend",
                "https://jobs.example.com/" + externalId, null, null, EARLIER);
        posting.extracted(JobCategory.DATA, ExperienceLevel.UNSPECIFIED, null, null, status, OLD_HASH, EARLIER);
        JobPosting saved = postings.save(posting);
        for (int seq = 1; seq <= criteriaCount; seq++) {
            criteria.save(JobPostingCriterion.extracted(saved.getId(), CriterionType.REQUIRED, seq, "옛 조건 " + seq,
                    "옛 인용 " + seq));
        }

    }

    private JobPosting reload(String externalId) {

        return postings.findByCompanyKeyAndExternalId("acme", externalId).orElseThrow();

    }

    private List<Map<String, Object>> criteriaOf(JobPosting posting) {

        return jdbc.queryForList("select seq, type, text, quote, public_id from job_posting_criterion "
                + "where job_posting_id = ? order by seq", posting.getId());

    }

    private static int count(CapturedOutput output, String line) {

        return output.getOut().split(Pattern.quote(line), -1).length - 1;

    }
}
