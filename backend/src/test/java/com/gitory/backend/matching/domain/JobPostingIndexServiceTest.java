package com.gitory.backend.matching.domain;

import com.gitory.backend.agent.port.IndexCriterion;
import com.gitory.backend.agent.port.IndexPosting;
import com.gitory.backend.agent.port.IndexPostingStatus;
import com.gitory.backend.agent.port.JobMatchingIndexPort;
import com.gitory.backend.agent.port.MatchingIndexResult;
import com.gitory.backend.agent.port.MatchingIndexUnavailableException;
import com.gitory.backend.agent.port.MatchingPruneResult;
import com.gitory.backend.agent.port.PostingCriterionType;
import com.gitory.backend.matching.infra.JobPostingCriterionRepository;
import com.gitory.backend.matching.infra.JobPostingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
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
class JobPostingIndexServiceTest {

    private static final Instant EARLIER = Instant.parse("2026-10-18T05:00:00Z");
    private static final MatchingIndexResult INDEXED = new MatchingIndexResult(0, 0, 0);

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

    private final JobMatchingIndexPort port = mock(JobMatchingIndexPort.class);

    @BeforeEach
    void clean() {

        jdbc.execute("TRUNCATE job_posting CASCADE");
        when(port.index(any())).thenReturn(INDEXED);
        when(port.prune(any())).thenReturn(new MatchingPruneResult(0, 0));

    }

    @Test
    @DisplayName("공고 45개는 20개, 20개, 5개로 세 번 나눠 보낸다")
    void sendsInBatchesOfTwenty() {

        seedReady(45);

        service().indexAll();

        ArgumentCaptor<List<IndexPosting>> batches = ArgumentCaptor.captor();
        verify(port, times(3)).index(batches.capture());
        assertThat(batches.getAllValues()).extracting(List::size).containsExactly(20, 20, 5);

    }

    @Test
    @DisplayName("공고는 활성 상태로, 조건은 id와 종류와 글을 번호 순으로 담아 보낸다")
    void sendsActivePostingWithCriteriaInOrder() {

        long id = seed("ready-1", JobPostingContentStatus.READY, JobPostingStatus.ACTIVE);
        long preferred = seedCriterion(id, CriterionType.PREFERRED, 3, "AWS 경험");
        long required = seedCriterion(id, CriterionType.REQUIRED, 1, "Java 경험");
        long responsibility = seedCriterion(id, CriterionType.RESPONSIBILITY, 2, "서버 개발");

        service().indexAll();

        ArgumentCaptor<List<IndexPosting>> batch = ArgumentCaptor.captor();
        verify(port).index(batch.capture());
        assertThat(batch.getValue()).singleElement().satisfies(posting -> {
            assertThat(posting.postingId()).isEqualTo(id);
            assertThat(posting.status()).isEqualTo(IndexPostingStatus.ACTIVE);
            assertThat(posting.criteria())
                    .extracting(IndexCriterion::criterionId, IndexCriterion::criterionType, IndexCriterion::text)
                    .containsExactly(tuple(required, PostingCriterionType.REQUIRED, "Java 경험"),
                            tuple(responsibility, PostingCriterionType.RESPONSIBILITY, "서버 개발"),
                            tuple(preferred, PostingCriterionType.PREFERRED, "AWS 경험"));
        });

    }

    @Test
    @DisplayName("활성이고 조건을 뽑은 공고만 보내고, 마감·사라짐·아직 못 뽑은·본문만 있는 공고는 보내지도 남기지도 않는다")
    void sendsOnlyActiveReadyPostings() {

        List<Long> ready = seedReady(2);
        seed("expired", JobPostingContentStatus.READY, JobPostingStatus.EXPIRED);
        seed("removed", JobPostingContentStatus.READY, JobPostingStatus.REMOVED);
        seed("pending", JobPostingContentStatus.PENDING, JobPostingStatus.ACTIVE);
        seed("metadata", JobPostingContentStatus.METADATA_ONLY, JobPostingStatus.ACTIVE);

        service().indexAll();

        ArgumentCaptor<List<IndexPosting>> batch = ArgumentCaptor.captor();
        verify(port).index(batch.capture());
        assertThat(batch.getValue()).extracting(IndexPosting::postingId).containsExactlyElementsOf(ready);
        verify(port).prune(ready);

    }

    @Test
    @DisplayName("성공한 묶음의 공고만 색인 해시를 그때의 내용 해시로 채우고, 실패한 묶음이 있어도 다음 묶음을 계속 보낸다")
    void marksOnlySuccessfulBatchesAndContinuesAfterFailure(CapturedOutput output) {

        List<Long> ids = seedReady(45);
        when(port.index(any())).thenReturn(INDEXED).thenThrow(unavailable("AI_UNAVAILABLE")).thenReturn(INDEXED);

        service().indexAll();

        verify(port, times(3)).index(any());
        assertThat(indexedHashes(ids.subList(0, 20))).allSatisfy(hash -> assertThat(hash).isNotNull());
        assertThat(indexedHashes(ids.subList(20, 40))).containsOnlyNulls();
        assertThat(indexedHashes(ids.subList(40, 45))).allSatisfy(hash -> assertThat(hash).isNotNull());
        assertThat(jdbc.queryForObject("select count(*) from job_posting where indexed_content_hash <> content_hash",
                Integer.class)).isZero();
        assertThat(output.getOut()).contains("공고 색인 묶음 실패 (2번째, 공고 20건): AI_UNAVAILABLE");

    }

    @Test
    @DisplayName("실패한 묶음의 활성 공고는 옛 색인 해시를 그대로 둔다")
    void keepsOldHashOfActivePostingInFailedBatch() {

        long active = seed("ready-1", JobPostingContentStatus.READY, JobPostingStatus.ACTIVE);
        seedCriterion(active, CriterionType.REQUIRED, 1, "Java 경험");
        jdbc.update("update job_posting set indexed_content_hash = content_hash");
        when(port.index(any())).thenThrow(unavailable("AI_UNAVAILABLE"));

        service().indexAll();

        assertThat(indexedHashes(List.of(active))).allSatisfy(hash -> assertThat(hash).isNotNull());

    }

    @Test
    @DisplayName("모든 묶음을 보낸 뒤 정리는 한 번만 부르고, 실패한 묶음의 공고도 남길 목록에 넣는다")
    void prunesOnceAfterAllBatchesKeepingFailedBatchPostings() {

        List<Long> ids = seedReady(25);
        when(port.index(any())).thenThrow(unavailable("AI_UNAVAILABLE")).thenReturn(INDEXED);

        service().indexAll();

        InOrder order = inOrder(port);
        order.verify(port, times(2)).index(any());
        order.verify(port).prune(ids);
        verify(port, times(1)).prune(any());

    }

    @Test
    @DisplayName("남길 공고가 하나도 없으면 아무것도 보내지 않고 정리도 부르지 않는다")
    void doesNotCallPruneWhenNothingToKeep(CapturedOutput output) {

        seed("expired", JobPostingContentStatus.READY, JobPostingStatus.EXPIRED);

        service().indexAll();

        verify(port, never()).index(any());
        verify(port, never()).prune(any());
        assertThat(output.getOut()).contains("공고 색인: 활성 공고가 없어 보내지 않았고 정리도 부르지 않았다");

    }

    @Test
    @DisplayName("정리가 실패해도 경고 한 줄만 남기고 끝까지 간다")
    void survivesPruneFailure(CapturedOutput output) {

        seedReady(1);
        when(port.prune(any())).thenThrow(unavailable("AI_UNAVAILABLE"));

        service().indexAll();

        assertThat(output.getOut()).contains("공고 색인 정리 실패: AI_UNAVAILABLE")
                .contains("공고 색인: 보냄 1건(조건 1) · 뺌 0건 · 실패 0묶음");

    }

    @Test
    @DisplayName("끝에 보낸 공고 수와 조건 수와 지운 공고 수와 실패한 묶음 수를 한 줄로 남긴다")
    void logsSummaryLine(CapturedOutput output) {

        seedReady(25);
        when(port.index(any())).thenReturn(INDEXED).thenThrow(unavailable("AI_UNAVAILABLE"));
        when(port.prune(any())).thenReturn(new MatchingPruneResult(25, 3));

        service().indexAll();

        assertThat(output.getOut()).contains("공고 색인: 보냄 20건(조건 20) · 뺌 3건 · 실패 1묶음");

    }

    private JobPostingIndexService service() {

        return new JobPostingIndexService(port, postings, criteria, new TransactionTemplate(transactionManager));

    }

    private static MatchingIndexUnavailableException unavailable(String code) {

        return new MatchingIndexUnavailableException("AI 공고 색인 실패 (" + code + ")", true, false, code, null);

    }

    // 공고마다 조건 하나씩을 달아 만들고 id 순서대로 돌려준다
    private List<Long> seedReady(int count) {

        List<Long> ids = new ArrayList<>();
        for (int number = 1; number <= count; number++) {
            long id = seed("ready-" + number, JobPostingContentStatus.READY, JobPostingStatus.ACTIVE);
            seedCriterion(id, CriterionType.REQUIRED, 1, "조건 " + id);
            ids.add(id);
        }
        return ids;

    }

    private long seed(String externalId, JobPostingContentStatus contentStatus, JobPostingStatus status) {

        JobPosting posting = JobPosting.discovered("acme", externalId, "에이크미", "Software Engineer, Backend",
                "https://jobs.example.com/" + externalId, null, null, EARLIER);
        posting.extracted(JobCategory.BACKEND, ExperienceLevel.UNSPECIFIED, null, null, List.of(), null, contentStatus,
                "%064x".formatted(externalId.hashCode() & 0xffffffffL), EARLIER);
        if (status == JobPostingStatus.EXPIRED) {
            posting.expire();
        } else if (status == JobPostingStatus.REMOVED) {
            posting.markRemoved();
        }
        return postings.save(posting).getId();

    }

    private long seedCriterion(long postingId, CriterionType type, int seq, String text) {

        return criteria.save(JobPostingCriterion.extracted(postingId, type, seq, text, "인용 " + text)).getId();

    }

    private List<String> indexedHashes(List<Long> ids) {

        return ids.stream().map(id -> jdbc.queryForObject("select indexed_content_hash from job_posting where id = ?",
                String.class, id)).toList();

    }
}
