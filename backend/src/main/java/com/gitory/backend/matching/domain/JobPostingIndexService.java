package com.gitory.backend.matching.domain;

import com.gitory.backend.agent.port.IndexCriterion;
import com.gitory.backend.agent.port.IndexPosting;
import com.gitory.backend.agent.port.IndexPostingStatus;
import com.gitory.backend.agent.port.JobMatchingIndexPort;
import com.gitory.backend.agent.port.MatchingIndexUnavailableException;
import com.gitory.backend.agent.port.MatchingPruneResult;
import com.gitory.backend.agent.port.PostingCriterionType;
import com.gitory.backend.matching.infra.JobPostingCriterionRepository;
import com.gitory.backend.matching.infra.JobPostingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 활성이고 조건을 뽑은 공고 전체를 AI 색인에 20개씩 보내고, 다 보낸 뒤 남길 공고 목록으로 색인을 정리한다
 * 묶음이 실패해도 다음 묶음을 계속하고, 실패한 공고도 남길 목록에 넣어 옛 색인을 지키며 다음 실행에서 다시 보낸다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobPostingIndexService {

    // AI 색인은 요청 하나에서 공고마다 임베딩을 한 번씩 하므로 한 번에 보내는 수를 작게 둔다
    private static final int BATCH_SIZE = 20;

    private final JobMatchingIndexPort port;
    private final JobPostingRepository postings;
    private final JobPostingCriterionRepository criteria;
    private final TransactionTemplate transaction;

    /** 잠금은 부르는 쪽이 쥐고 있어야 한다 */
    public void indexAll() {

        clearHashOfPostingsLeavingIndex();
        List<JobPosting> targets = postings.findByStatusAndContentStatusOrderById(JobPostingStatus.ACTIVE,
                JobPostingContentStatus.READY);
        if (targets.isEmpty()) {
            log.info("공고 색인: 활성 공고가 없어 보내지 않았고 정리도 부르지 않았다");
            return;
        }
        Sent sent = new Sent();
        for (int from = 0; from < targets.size(); from += BATCH_SIZE) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            List<JobPosting> batch = targets.subList(from, Math.min(from + BATCH_SIZE, targets.size()));
            sendBatch(batch, from / BATCH_SIZE + 1, sent);
        }
        int removed = prune(targets);
        log.info("공고 색인: 보냄 {}건(조건 {}) · 뺌 {}건 · 실패 {}묶음", sent.postings, sent.criteria, removed,
                sent.failedBatches);

    }

    // 색인에서 빠지는 공고가 나중에 다시 활성이 될 때 지워진 벡터인데도 해시가 맞아 목록에 나오지 않게 미리 비운다
    private void clearHashOfPostingsLeavingIndex() {

        transaction.executeWithoutResult(status ->
                postings.clearIndexedContentHashExcept(JobPostingStatus.ACTIVE, JobPostingContentStatus.READY));

    }

    private void sendBatch(List<JobPosting> batch, int number, Sent sent) {

        try {
            List<IndexPosting> request = requestOf(batch);
            port.index(request);
            markIndexed(batch);
            sent.postings += request.size();
            sent.criteria += request.stream().mapToInt(posting -> posting.criteria().size()).sum();
        } catch (MatchingIndexUnavailableException e) {
            // 실패 원인은 AI 오류 코드로 충분해 스택은 남기지 않는다
            log.warn("공고 색인 묶음 실패 ({}번째, 공고 {}건): {}", number, batch.size(), e.getErrorCode());
            sent.failedBatches++;
        } catch (RuntimeException e) {
            log.error("공고 색인 묶음 실패 ({}번째, 공고 {}건): INTERNAL_ERROR", number, batch.size(), e);
            sent.failedBatches++;
        }

    }

    private List<IndexPosting> requestOf(List<JobPosting> batch) {

        Map<Long, List<IndexCriterion>> criteriaByPosting = new HashMap<>();
        for (JobPostingCriterion criterion : criteria.findByJobPostingIdInOrderByJobPostingIdAscSeqAsc(
                batch.stream().map(JobPosting::getId).toList())) {
            criteriaByPosting.computeIfAbsent(criterion.getJobPostingId(), id -> new ArrayList<>())
                    .add(new IndexCriterion(criterion.getId(), PostingCriterionType.valueOf(criterion.getType().name()),
                            criterion.getText()));
        }
        return batch.stream()
                .map(posting -> new IndexPosting(posting.getId(), IndexPostingStatus.ACTIVE,
                        criteriaByPosting.getOrDefault(posting.getId(), List.of())))
                .toList();

    }

    // 목록은 지금 내용이 색인에 반영된 공고만 보여 주므로, 이 묶음을 보낸 때의 해시를 남긴다
    private void markIndexed(List<JobPosting> batch) {

        Map<Long, String> hashes = batch.stream()
                .collect(Collectors.toMap(JobPosting::getId, JobPosting::getContentHash));
        transaction.executeWithoutResult(status ->
                postings.findAllById(hashes.keySet()).forEach(posting -> posting.indexed(hashes.get(posting.getId()))));

    }

    private int prune(List<JobPosting> targets) {

        try {
            MatchingPruneResult result = port.prune(targets.stream().map(JobPosting::getId).toList());
            return result.removedPostings();
        } catch (MatchingIndexUnavailableException e) {
            log.warn("공고 색인 정리 실패: {}", e.getErrorCode());
        } catch (RuntimeException e) {
            log.error("공고 색인 정리 실패: INTERNAL_ERROR", e);
        }
        return 0;

    }

    private static final class Sent {

        int postings;
        int criteria;
        int failedBatches;
    }
}
