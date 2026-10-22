package com.gitory.backend.matching.domain;

import com.gitory.backend.matching.infra.CompanySource;
import com.gitory.backend.matching.infra.FetchFailureCode;
import com.gitory.backend.matching.infra.GreenhouseClient;
import com.gitory.backend.matching.infra.GreetingClient;
import com.gitory.backend.matching.infra.JobPostingRepository;
import com.gitory.backend.matching.infra.JobPostingSourceRepository;
import com.gitory.backend.matching.infra.KakaoCareersClient;
import com.gitory.backend.matching.infra.LineCareersClient;
import com.gitory.backend.matching.infra.MatchingProperties;
import com.gitory.backend.matching.infra.PoliteFetchException;
import com.gitory.backend.matching.infra.PoliteFetchSession;
import com.gitory.backend.matching.infra.PoliteFetcher;
import com.gitory.backend.matching.infra.SyncLock;
import com.gitory.backend.matching.infra.TossCareersClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 설정에 둔 회사들의 채용 공고를 가져와 저장하고, 사라졌거나 마감이 지난 공고의 상태를 바꾼 뒤 본문을 받은 공고의 조건을 뽑게 넘기고 활성 공고를 색인에 다시 보낸다
 * 한 회사가 실패해도 공고 상태는 그대로 두고 다음 회사로 넘어가며, 가져오는 동안은 트랜잭션을 쥐지 않는다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobPostingSyncService {

    // 목록이 갑자기 비면 그 회사가 공고를 내린 것이 아니라 가져오기가 잘못된 것으로 보고 상태를 바꾸지 않는다
    private static final int EMPTY_LIST_GUARD_MIN_ACTIVE = 5;
    private static final String EMPTY_LIST = "EMPTY_LIST";

    private final MatchingProperties properties;
    private final PoliteFetcher fetcher;
    private final GreenhouseClient greenhouse;
    private final GreetingClient greeting;
    private final TossCareersClient toss;
    private final LineCareersClient line;
    private final KakaoCareersClient kakao;
    private final JobPostingRepository postings;
    private final JobPostingSourceRepository sources;
    private final JobPostingExtractionService extraction;
    private final JobPostingIndexService indexing;
    private final TransactionTemplate transaction;
    private final SyncLock lock;
    private final Clock clock;

    public void sync() {

        boolean ran = lock.runExclusively(this::syncAll);
        if (!ran) {
            log.info("공고 가져오기를 건너뜀: 공고 가져오기나 색인 다시 보내기가 이미 실행 중이다");
        }

    }

    private void syncAll() {

        Instant started = clock.instant();
        log.info("공고 가져오기 시작: 회사 {}곳", properties.sources().size());
        removeUnconfiguredCompanies();
        PoliteFetchSession session = fetcher.open();
        List<ExtractionTarget> targets = new ArrayList<>();
        for (CompanySource source : properties.sources()) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            syncCompany(session, source, targets);
        }
        expireOverdue();
        extraction.extractAll(targets);
        indexing.indexAll();
        log.info("공고 가져오기 끝: {}초", secondsSince(started));

    }

    private void removeUnconfiguredCompanies() {

        Set<String> configured = properties.sources().stream().map(CompanySource::key).collect(Collectors.toSet());
        transaction.executeWithoutResult(status ->
                postings.findByStatusAndCompanyKeyNotIn(JobPostingStatus.ACTIVE, configured)
                        .forEach(JobPosting::markRemoved));

    }

    private void syncCompany(PoliteFetchSession session, CompanySource source, List<ExtractionTarget> targets) {

        Instant started = clock.instant();
        transaction.executeWithoutResult(status -> sourceOf(source).attempt(started));
        try {
            List<FetchedPosting> fetched = switch (source.type()) {
                case GREENHOUSE -> greenhouse.list(session, source);
                case GREETING -> greeting.list(session, source);
                case TOSS -> toss.list(session, source);
                case LINE -> line.list(session, source);
                case KAKAO -> kakao.list(session, source);
            };
            List<FetchedPosting> development = fetched.stream().filter(DevelopmentPostingFilter::accepts).toList();
            Counts counts = transaction.execute(status -> store(source, fetched.size(), development));
            development.forEach(posting -> targets.add(new ExtractionTarget(source.key(), posting.externalId(), posting.text())));
            log.info("공고 가져오기 {}: 목록 {}건 중 개발 {}건 · 새 {} · 사라짐 {} · {}초", source.name(), fetched.size(),
                    development.size(), counts.added(), counts.removed(), secondsSince(started));
        } catch (PoliteFetchException e) {
            recordFailure(source, e.code());
        } catch (RuntimeException e) {
            log.error("공고 가져오기 실패 {}: INTERNAL_ERROR", source.name(), e);
            transaction.executeWithoutResult(status -> sourceOf(source).fail("INTERNAL_ERROR"));
        }

    }

    private Counts store(CompanySource source, int fetchedCount, List<FetchedPosting> development) {

        Instant now = clock.instant();
        Map<String, JobPosting> known = postings.findByCompanyKey(source.key()).stream()
                .collect(Collectors.toMap(JobPosting::getExternalId, Function.identity()));
        Set<String> seen = development.stream().map(FetchedPosting::externalId).collect(Collectors.toSet());
        int added = 0;
        for (FetchedPosting posting : development) {
            JobPosting existing = known.get(posting.externalId());
            if (existing == null) {
                postings.save(JobPosting.discovered(source.key(), posting.externalId(), source.name(), posting.title(),
                        posting.sourceUrl(), posting.sourceUpdatedAt(), posting.expiresAt(), now));
                added++;
            } else {
                existing.seenAgain(source.name(), posting.title(), posting.sourceUrl(), posting.sourceUpdatedAt(),
                        posting.expiresAt(), now);
            }
        }
        List<JobPosting> vanished = known.values().stream()
                .filter(posting -> posting.getStatus() == JobPostingStatus.ACTIVE && !seen.contains(posting.getExternalId()))
                .toList();
        int removed = 0;
        if (fetchedCount == 0 && vanished.size() >= EMPTY_LIST_GUARD_MIN_ACTIVE) {
            log.warn("공고 가져오기 {}: 목록이 비어 공고 {}건을 사라짐으로 바꾸지 않았다", source.name(), vanished.size());
            sourceOf(source).fail(EMPTY_LIST);
        } else {
            vanished.forEach(JobPosting::markRemoved);
            removed = vanished.size();
            sourceOf(source).succeed(now);
        }
        return new Counts(added, removed);

    }

    // robots.txt 가 막은 경우는 가져오기 세션이 이미 INFO 로 남겼으므로 경고를 한 번 더 남기지 않는다
    private void recordFailure(CompanySource source, FetchFailureCode code) {

        if (code != FetchFailureCode.ROBOTS_DISALLOWED) {
            log.warn("공고 가져오기 실패 {}: {}", source.name(), code);
        }
        transaction.executeWithoutResult(status -> sourceOf(source).fail(code.name()));

    }

    private void expireOverdue() {

        Instant now = clock.instant();
        transaction.executeWithoutResult(status ->
                postings.findByStatusAndExpiresAtBefore(JobPostingStatus.ACTIVE, now).forEach(JobPosting::expire));

    }

    private JobPostingSource sourceOf(CompanySource source) {

        return sources.findById(source.key()).orElseGet(() ->
                sources.save(JobPostingSource.attempted(source.key(), clock.instant())));

    }

    private String secondsSince(Instant started) {

        return String.format(Locale.ROOT, "%.1f", Duration.between(started, clock.instant()).toMillis() / 1000.0);

    }

    private record Counts(int added, int removed) {
    }
}
