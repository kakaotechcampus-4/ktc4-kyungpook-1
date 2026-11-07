package com.gitory.backend.ingest.domain;

import com.gitory.backend.ingest.infra.CommitSummaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 다른 모듈이 커밋 id 로 커밋의 sha·메시지·작성 시각과 초안에 보낼 커밋 순서를 ingest 표를 직접 읽지 않고 얻게 한다 */
@Service
@RequiredArgsConstructor
public class CommitSummaryService {

    private final CommitSummaryRepository commits;

    public Map<Long, CommitSummary> findByIds(Collection<Long> commitIds) {

        return commits.findByIds(commitIds).stream()
                .collect(Collectors.toMap(CommitSummary::id, Function.identity()));

    }

    /** AI 가 앞 커밋부터 읽으므로, 경험의 핵심인 큰 변경이 먼저 읽히게 추가·삭제 줄 수가 큰 순서로 limit 개까지 돌려준다 */
    public List<DraftCommit> largestFirst(Collection<Long> commitIds, int limit) {

        return commits.findLargestFirst(commitIds, Limit.of(limit));

    }

    /**
     * 주어진 커밋 가운데 after 뒤에 쓴 커밋이거나 그 수집 이력에서 처음 저장된 커밋의 id 를 돌려준다
     * 커밋은 저장소마다 한 벌이고 처음 저장한 수집 이력의 id 만 가지므로, 이전 수집 때 저장한 커밋은 쓴 시각이 after 뒤가 아니면 빠진다
     */
    public Set<Long> newIds(Collection<Long> commitIds, Instant after, Long collectionRunId) {

        if (commitIds.isEmpty()) {
            return Set.of();
        }

        return new HashSet<>(commits.findNewIds(commitIds, after, collectionRunId));

    }

    /** 같은 커밋을 다시 요약하면 새 요약으로 바꾼다 */
    @Transactional
    public void recordDiffSummaries(Map<Long, String> summaryByCommitId) {

        summaryByCommitId.forEach(commits::updateDiffSummary);

    }
}
