package com.gitory.backend.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gitory.backend.matching.infra.JobPostingCriterionRepository;
import com.gitory.backend.matching.infra.JobPostingRepository;
import com.gitory.backend.matching.infra.JobPostingSourceRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Stream;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class JobPostingPersistenceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T05:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    EntityManager em;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JobPostingRepository postings;

    @Autowired
    JobPostingCriterionRepository criteria;

    @Autowired
    JobPostingSourceRepository sources;

    @Test
    @DisplayName("새로 발견한 공고는 활성 상태와 뽑기 전 값으로 저장되고 다시 읽힌다")
    void savesDiscoveredPosting() {

        Instant sourceUpdatedAt = Instant.parse("2026-10-18T00:00:00Z");
        Instant expiresAt = Instant.parse("2026-11-30T14:59:59Z");
        JobPosting saved = postings.saveAndFlush(JobPosting.discovered("acme", "1001", "에이크미", "백엔드 개발자",
                "https://jobs.example.com/1001", sourceUpdatedAt, expiresAt, NOW));

        em.clear();
        JobPosting found = postings.findByCompanyKeyAndStatus("acme", JobPostingStatus.ACTIVE).getFirst();

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getPublicId()).isNotNull();
        assertThat(found.getExternalId()).isEqualTo("1001");
        assertThat(found.getCompanyName()).isEqualTo("에이크미");
        assertThat(found.getTitle()).isEqualTo("백엔드 개발자");
        assertThat(found.getSourceUrl()).isEqualTo("https://jobs.example.com/1001");
        assertThat(found.getSourceUpdatedAt()).isEqualTo(sourceUpdatedAt);
        assertThat(found.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(found.getFirstSeenAt()).isEqualTo(NOW);
        assertThat(found.getLastSeenAt()).isEqualTo(NOW);
        assertThat(found.getContentStatus()).isEqualTo(JobPostingContentStatus.PENDING);
        assertThat(found.getExperienceLevel()).isEqualTo(ExperienceLevel.UNSPECIFIED);
        assertThat(found.getCategory()).isNull();
        assertThat(found.getContentHash()).isNull();
        assertThat(found.getIndexedContentHash()).isNull();
        assertThat(found.getExtractedAt()).isNull();
        assertThat(found.getUpdatedAt()).isNotNull();

    }

    @ParameterizedTest
    @EnumSource(value = JobPostingStatus.class, names = {"REMOVED", "EXPIRED"})
    @DisplayName("활성을 벗어난 공고는 색인에 반영된 해시를 비운다")
    void clearsIndexedHashWhenLeavingActive(JobPostingStatus status) {

        JobPosting posting = persistPosting("acme", "1001");
        jdbc.update("UPDATE job_posting SET indexed_content_hash = ? WHERE id = ?", "a".repeat(64), posting.getId());
        em.clear();

        JobPosting found = postings.findById(posting.getId()).orElseThrow();
        if (status == JobPostingStatus.REMOVED) {
            found.markRemoved();
        } else {
            found.expire();
        }
        em.flush();

        assertThat(jdbc.queryForObject("SELECT indexed_content_hash FROM job_posting WHERE id = ?", String.class,
                posting.getId())).isNull();

    }

    @Test
    @DisplayName("같은 회사에 같은 원천 id 의 공고를 두 번 저장할 수 없고 다른 회사나 다른 id 는 저장된다")
    void rejectsDuplicateExternalIdInSameCompany() {

        persistPosting("acme", "1001");
        persistPosting("other", "1001");
        persistPosting("acme", "1002");

        assertThatThrownBy(() -> persistPosting("acme", "1001"))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("job_posting_company_key_external_id_key");

    }

    @Test
    @DisplayName("조건의 칸마다 값이 저장되고 다시 읽힌다")
    void savesCriterion() {

        JobPosting posting = persistPosting("acme", "1001");
        JobPostingCriterion saved = criteria.saveAndFlush(JobPostingCriterion.extracted(posting.getId(),
                CriterionType.REQUIRED, 1, "Spring Boot 개발 경험", "Spring Boot 로 서비스를 개발해 본 분"));

        em.clear();
        JobPostingCriterion found = criteria.findById(saved.getId()).orElseThrow();

        assertThat(found.getPublicId()).isNotNull();
        assertThat(found.getJobPostingId()).isEqualTo(posting.getId());
        assertThat(found.getType()).isEqualTo(CriterionType.REQUIRED);
        assertThat(found.getSeq()).isEqualTo(1);
        assertThat(found.getText()).isEqualTo("Spring Boot 개발 경험");
        assertThat(found.getQuote()).isEqualTo("Spring Boot 로 서비스를 개발해 본 분");

    }

    @Test
    @DisplayName("공고를 지우면 그 공고의 조건도 함께 지워진다")
    void deletesCriteriaWithPosting() {

        JobPosting posting = persistPosting("acme", "1001");
        criteria.saveAndFlush(JobPostingCriterion.extracted(posting.getId(), CriterionType.REQUIRED, 1, "가", "가"));
        criteria.saveAndFlush(JobPostingCriterion.extracted(posting.getId(), CriterionType.PREFERRED, 2, "나", "나"));

        jdbc.update("DELETE FROM job_posting WHERE id = ?", posting.getId());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_posting_criterion", Integer.class)).isZero();

    }

    @Test
    @DisplayName("한 공고에 같은 순번의 조건을 두 번 저장할 수 없고 다른 공고의 같은 순번은 저장된다")
    void rejectsDuplicateSeqInSamePosting() {

        JobPosting posting = persistPosting("acme", "1001");
        JobPosting another = persistPosting("acme", "1002");
        criteria.saveAndFlush(JobPostingCriterion.extracted(posting.getId(), CriterionType.REQUIRED, 1, "가", "가"));
        criteria.saveAndFlush(JobPostingCriterion.extracted(another.getId(), CriterionType.REQUIRED, 1, "가", "가"));
        criteria.saveAndFlush(JobPostingCriterion.extracted(posting.getId(), CriterionType.REQUIRED, 2, "나", "나"));

        assertThatThrownBy(() -> criteria.saveAndFlush(
                JobPostingCriterion.extracted(posting.getId(), CriterionType.PREFERRED, 1, "다", "다")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("job_posting_criterion_job_posting_id_seq_key");

    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("postingEnumValues")
    @DisplayName("공고의 enum 칸은 자바 enum 값을 모두 저장하고 다시 읽는다")
    void savesEveryPostingEnumValue(String column, Enum<?> value, Function<JobPosting, Enum<?>> getter) {

        JobPosting posting = persistPosting("acme", "1001");

        jdbc.update("UPDATE job_posting SET " + column + " = ? WHERE id = ?", value.name(), posting.getId());
        em.clear();

        assertThat(getter.apply(postings.findById(posting.getId()).orElseThrow())).isEqualTo(value);

    }

    @ParameterizedTest
    @EnumSource(CriterionType.class)
    @DisplayName("조건의 type 은 자바 enum 값을 모두 저장하고 다시 읽는다")
    void savesEveryCriterionType(CriterionType type) {

        JobPosting posting = persistPosting("acme", "1001");
        JobPostingCriterion saved = criteria.saveAndFlush(
                JobPostingCriterion.extracted(posting.getId(), type, 1, "가", "가"));

        em.clear();

        assertThat(criteria.findById(saved.getId()).orElseThrow().getType()).isEqualTo(type);

    }

    @Test
    @DisplayName("허용되지 않은 직무 분류 값은 저장할 수 없다")
    void rejectsUnknownCategory() {

        JobPosting posting = persistPosting("acme", "1001");

        assertThatThrownBy(() -> jdbc.update("UPDATE job_posting SET category = 'PLANNER' WHERE id = ?",
                posting.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);

    }

    @Test
    @DisplayName("경력 표시 없음, 신입, 경력 N년 이상은 규칙에 맞게 저장된다")
    void acceptsValidExperienceCombinations() {

        JobPosting posting = persistPosting("acme", "1001");

        assertThat(updateExperience(posting, "ENTRY", null, "신입 지원 가능")).isEqualTo(1);
        assertThat(updateExperience(posting, "EXPERIENCED", 1, "경력 1년 이상")).isEqualTo(1);
        assertThat(updateExperience(posting, "EXPERIENCED", 60, "경력 60년 이상")).isEqualTo(1);
        assertThat(updateExperience(posting, "UNSPECIFIED", null, null)).isEqualTo(1);

    }

    @ParameterizedTest(name = "{0} / 연차 {1} / 인용 {2}")
    @MethodSource("invalidExperience")
    @DisplayName("경력 표시와 어긋난 연차나 인용 조합은 저장할 수 없다")
    void rejectsInvalidExperienceCombinations(String level, Integer minYears, String quote) {

        JobPosting posting = persistPosting("acme", "1001");

        assertThatThrownBy(() -> updateExperience(posting, level, minYears, quote))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("job_posting_experience_check");

    }

    @Test
    @DisplayName("회사별 가져오기 시도 시각이 저장되고 다시 읽힌다")
    void savesSourceAttempt() {

        sources.saveAndFlush(JobPostingSource.attempted("acme", NOW));

        em.clear();
        JobPostingSource found = sources.findById("acme").orElseThrow();

        assertThat(found.getLastAttemptAt()).isEqualTo(NOW);
        assertThat(found.getLastSuccessAt()).isNull();
        assertThat(found.getLastErrorCode()).isNull();

    }

    static Stream<Arguments> postingEnumValues() {

        Stream<Arguments> categories = Arrays.stream(JobCategory.values())
                .map(v -> Arguments.of("category", v, (Function<JobPosting, Enum<?>>) JobPosting::getCategory));
        Stream<Arguments> statuses = Arrays.stream(JobPostingStatus.values())
                .map(v -> Arguments.of("status", v, (Function<JobPosting, Enum<?>>) JobPosting::getStatus));
        Stream<Arguments> contentStatuses = Arrays.stream(JobPostingContentStatus.values())
                .map(v -> Arguments.of("content_status", v,
                        (Function<JobPosting, Enum<?>>) JobPosting::getContentStatus));

        return Stream.of(categories, statuses, contentStatuses).flatMap(s -> s);

    }

    static Stream<Arguments> invalidExperience() {

        return Stream.of(
                Arguments.of("UNSPECIFIED", null, "경력 무관"),
                Arguments.of("UNSPECIFIED", 3, null),
                Arguments.of("ENTRY", null, null),
                Arguments.of("ENTRY", 1, "신입 지원 가능"),
                Arguments.of("EXPERIENCED", null, "경력자"),
                Arguments.of("EXPERIENCED", 0, "경력 0년"),
                Arguments.of("EXPERIENCED", 3, null),
                Arguments.of("EXPERIENCED", 61, "경력 61년 이상"));

    }

    private int updateExperience(JobPosting posting, String level, Integer minYears, String quote) {

        return jdbc.update("""
                UPDATE job_posting
                SET experience_level = ?, experience_min_years = ?, experience_quote = ?
                WHERE id = ?
                """, level, minYears, quote, posting.getId());

    }

    private JobPosting persistPosting(String companyKey, String externalId) {

        JobPosting posting = JobPosting.discovered(companyKey, externalId, "에이크미", "백엔드 개발자",
                "https://jobs.example.com/" + externalId, null, null, NOW);

        em.persist(posting);
        em.flush();

        return posting;

    }
}
