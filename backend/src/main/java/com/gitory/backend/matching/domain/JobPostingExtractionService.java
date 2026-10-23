package com.gitory.backend.matching.domain;

import com.gitory.backend.agent.port.ExtractedCriterion;
import com.gitory.backend.agent.port.ExtractedExperience;
import com.gitory.backend.agent.port.JobPostingExtractionPort;
import com.gitory.backend.agent.port.JobPostingExtractionRequest;
import com.gitory.backend.agent.port.JobPostingExtractionResult;
import com.gitory.backend.agent.port.PostingContentStatus;
import com.gitory.backend.agent.port.PostingExtractionUnavailableException;
import com.gitory.backend.matching.infra.JobPostingCriterionRepository;
import com.gitory.backend.matching.infra.JobPostingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 이번 실행에서 본문을 받은 공고마다 AI 로 조건을 뽑아 저장한다
 * 본문 글은 실행 안에서만 들고 있고 저장하지 않으며, 공고 하나가 실패해도 다음 공고로 넘어간다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobPostingExtractionService {

    // AI 가 받는 본문 상한과 같은 값이다
    private static final int MAX_TEXT_LENGTH = 100_000;
    private static final int STOP_AFTER_SAME_FAILURES = 3;

    private final JobPostingExtractionPort port;
    private final JobPostingRepository postings;
    private final JobPostingCriterionRepository criteria;
    private final TransactionTemplate transaction;
    private final Clock clock;

    /** 공고마다 차례로 부르며, 다시 해도 같은 AI 실패가 연달아 이어지면 남은 공고는 다음 실행으로 미룬다 */
    public void extractAll(List<ExtractionTarget> targets) {

        int updated = 0;
        int unchanged = 0;
        int rejected = 0;
        int failed = 0;
        int sameFailures = 0;
        String failureCode = null;
        for (ExtractionTarget target : targets) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            Attempt attempt = attempt(target);
            switch (attempt.result()) {
                case UPDATED -> updated++;
                case UNCHANGED -> unchanged++;
                case REJECTED -> rejected++;
                case FAILED, SAME_FAILURE -> failed++;
                case TEXT_SKIPPED -> {
                    failed++;
                    continue;
                }
                case SKIPPED -> {
                    continue;
                }
            }
            sameFailures = attempt.result() == Result.SAME_FAILURE ? sameFailures + 1 : 0;
            failureCode = attempt.code();
            if (sameFailures >= STOP_AFTER_SAME_FAILURES) {
                log.warn("공고 조건 뽑기를 멈춘다: 다시 해도 같은 AI 실패 {} 연속 {}번", failureCode, sameFailures);
                break;
            }
        }
        log.info("공고 조건 뽑기: 새로 {} · 그대로 {} · 거절 {} · 실패 {}", updated, unchanged, rejected, failed);

    }

    private Attempt attempt(ExtractionTarget target) {

        String label = target.companyKey() + "/" + target.externalId();
        try {
            JobPosting posting = postings.findByCompanyKeyAndExternalId(target.companyKey(), target.externalId())
                    .orElse(null);
            if (posting == null || posting.getStatus() != JobPostingStatus.ACTIVE) {
                return new Attempt(Result.SKIPPED, null);
            }
            if (target.text().isBlank() || target.text().codePointCount(0, target.text().length()) > MAX_TEXT_LENGTH) {
                log.warn("공고 조건 뽑기 건너뜀 {}: 본문이 비었거나 {}자를 넘는다", label, MAX_TEXT_LENGTH);
                return new Attempt(Result.TEXT_SKIPPED, null);
            }
            JobPostingExtractionResult result = port.extract(requestOf(posting, target.text()));
            return switch (result.outcome()) {
                case UPDATED -> {
                    save(posting.getId(), result, target.text());
                    yield new Attempt(Result.UPDATED, null);
                }
                case UNCHANGED -> new Attempt(Result.UNCHANGED, null);
                case REJECTED -> {
                    log.info("공고 조건 뽑기 거절 {}: 필수 조건의 근거를 원문에서 확인할 수 없다", label);
                    yield new Attempt(Result.REJECTED, null);
                }
            };
        } catch (PostingExtractionUnavailableException e) {
            // 실패 원인은 AI 오류 코드로 충분해 스택은 남기지 않는다
            log.warn("공고 조건 뽑기 실패 {}: {}", label, e.getErrorCode());
            return new Attempt(e.isPermanentModelFailure() ? Result.SAME_FAILURE : Result.FAILED, e.getErrorCode());
        } catch (RuntimeException e) {
            log.error("공고 조건 뽑기 실패 {}: INTERNAL_ERROR", label, e);
            return new Attempt(Result.FAILED, "INTERNAL_ERROR");
        }

    }

    // 아직 한 번도 못 뽑은 공고는 비교할 해시가 없어 이전 해시와 상태를 보내지 않는다
    private static JobPostingExtractionRequest requestOf(JobPosting posting, String text) {

        boolean extractedBefore = posting.getContentStatus() != JobPostingContentStatus.PENDING
                && posting.getContentHash() != null;
        return new JobPostingExtractionRequest(posting.getCompanyKey(), posting.getExternalId(), posting.getTitle(),
                posting.getSourceUrl(), text, extractedBefore ? posting.getContentHash() : null,
                extractedBefore ? PostingContentStatus.valueOf(posting.getContentStatus().name()) : null);

    }

    private void save(Long postingId, JobPostingExtractionResult result, String text) {

        ExtractedExperience experience = result.experience();
        transaction.executeWithoutResult(status -> {
            JobPosting posting = postings.findById(postingId).orElseThrow();
            criteria.deleteAllByPostingId(postingId);
            posting.extracted(JobCategory.valueOf(result.category().name()),
                    ExperienceLevel.valueOf(experience.level().name()), experience.minYears(), experience.quote(),
                    JobPostingContentStatus.valueOf(result.contentStatus().name()), result.contentHash(),
                    clock.instant());
            List<JobPostingCriterion> saved = new ArrayList<>();
            List<ExtractedCriterion> ordered = result.criteria().stream()
                    .sorted(Comparator.comparingInt(criterion -> positionOf(text, criterion.quote())))
                    .toList();
            for (ExtractedCriterion criterion : ordered) {
                saved.add(JobPostingCriterion.extracted(postingId, CriterionType.valueOf(criterion.type().name()),
                        saved.size() + 1, criterion.text(), criterion.quote()));
            }
            criteria.saveAll(saved);
        });

    }

    // 보낸 글에서 인용이 처음 나오는 자리이고, 같은 자리면 정렬이 안정적이라 AI 가 준 순서가 남는다
    private static int positionOf(String text, String quote) {

        int position = text.indexOf(quote);
        return position < 0 ? Integer.MAX_VALUE : position;

    }

    private enum Result {
        UPDATED, UNCHANGED, REJECTED, FAILED, SAME_FAILURE, TEXT_SKIPPED, SKIPPED
    }

    private record Attempt(Result result, String code) {
    }
}
