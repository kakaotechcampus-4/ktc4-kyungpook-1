package com.gitory.backend.recommend.domain;

import com.gitory.backend.ingest.domain.CommitSummary;
import com.gitory.backend.ingest.domain.CommitSummaryService;
import com.gitory.backend.recommend.infra.CandidateCommitRepository;
import com.gitory.backend.recommend.infra.CandidateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 다른 모듈이 카드로 쓰지도 빼지도 않은 후보와 그 후보에 든 커밋 메시지를 후보 표를 직접 읽지 않고 얻게 한다 */
@Service
@RequiredArgsConstructor
public class UnusedCandidateReader {

    private final CandidateRepository candidates;
    private final CandidateCommitRepository candidateCommits;
    private final CommitSummaryService commits;

    /** 주어진 저장소 연결의 새 후보를 점수가 높은 순으로 limit 개까지 읽고, 사용자가 빼지 않은 커밋의 메시지 첫 줄을 후보에 넣은 순서로 commitLimit 개까지 담는다 */
    @Transactional(readOnly = true)
    public List<UnusedCandidate> of(Collection<Long> userRepositoryIds, int limit, int commitLimit) {

        if (userRepositoryIds.isEmpty()) {
            return List.of();
        }
        List<Candidate> found = candidates.findBestFirst(userRepositoryIds, CandidateStatus.NEW, Limit.of(limit));
        if (found.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Long>> commitIds = candidateCommits.findByCandidateIdInAndIncludedTrue(
                        found.stream().map(Candidate::getId).toList()).stream()
                .sorted(Comparator.comparing(CandidateCommit::getId))
                .collect(Collectors.groupingBy(CandidateCommit::getCandidateId,
                        Collectors.mapping(CandidateCommit::getCommitId, Collectors.toList())));
        List<Long> allCommitIds = commitIds.values().stream().flatMap(List::stream).toList();
        Map<Long, CommitSummary> summaries = allCommitIds.isEmpty() ? Map.of() : commits.findByIds(allCommitIds);

        return found.stream()
                .map(candidate -> new UnusedCandidate(candidate.getUserRepositoryId(), candidate.getPublicId(),
                        candidate.getTitle(), candidate.getReasonText(),
                        firstLines(commitIds.getOrDefault(candidate.getId(), List.of()), summaries, commitLimit),
                        candidate.getReviewCommentCount()))
                .toList();

    }

    private static List<String> firstLines(List<Long> commitIds, Map<Long, CommitSummary> summaries, int limit) {

        return commitIds.stream()
                .map(summaries::get)
                .filter(Objects::nonNull)
                .map(CommitSummary::message)
                .filter(Objects::nonNull)
                .map(message -> message.strip().lines().findFirst().orElse("").strip())
                .filter(line -> !line.isEmpty())
                .limit(limit)
                .toList();

    }
}
