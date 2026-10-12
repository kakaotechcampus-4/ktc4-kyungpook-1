package com.gitory.backend.recommend.domain;

import com.gitory.backend.ingest.domain.StoredActivityService;
import com.gitory.backend.ingest.domain.StoredCommit;
import com.gitory.backend.recommend.infra.CandidateCommitRepository;
import com.gitory.backend.recommend.infra.CandidateGroupingRepository;
import com.gitory.backend.recommend.infra.CandidateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AI 가 묶은 후보를 저장소 연결 하나의 후보로 저장하며, 사용자가 손댄 후보와 그 커밋은 다시 묶어도 지우거나 바꾸지 않는다
 * 손대지 않은 NEW 후보는 새 결과로 통째로 바꿔 후보 id 가 새로 생기므로, 보낼 요청이 지난번과 같으면 AI 를 다시 부르지 않게 지문을 남긴다
 */
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(CandidateGroupingProperties.class)
public class CandidateStoreService {

    private final CandidateRepository candidates;
    private final CandidateCommitRepository candidateCommits;
    private final CandidateGroupingRepository groupings;
    private final StoredActivityService storedActivity;
    private final CandidateGroupingProperties properties;

    /** AI 에 보내지 않을 커밋과 PR 로, 손댄 후보에 포함된 커밋과 손댄 후보에 든 모든 PR 이다 */
    @Transactional(readOnly = true)
    public LockedSources lockedSources(Long userRepositoryId) {

        List<Candidate> touched = touchedOf(candidates.findByUserRepositoryId(userRepositoryId));
        Set<Integer> pullRequestNumbers = touched.stream()
                .flatMap(candidate -> candidate.getPullRequestNumbers().stream())
                .collect(Collectors.toSet());

        return new LockedSources(new HashSet<>(storedActivity.findShas(includedCommitIdsOf(touched))),
                pullRequestNumbers);

    }

    /** 보낼 요청을 JSON 글자 그대로 받아, 설정한 묶기 버전과 함께 지문을 만든다 */
    public String fingerprint(String request) {

        return CandidateGrouping.fingerprintOf(request, properties.version());

    }

    /** 지난번 묶었을 때와 같은 요청이면 후보를 그대로 두고 묶은 시각만 지금으로 바꿔 true 를 돌려주며, 이때는 AI 를 부르지 않는다 */
    @Transactional
    public boolean keepIfSameInput(Long userRepositoryId, String fingerprint) {

        Optional<CandidateGrouping> same = groupings.findById(userRepositoryId)
                .filter(grouping -> grouping.hasSameInput(fingerprint, properties.version()));
        same.ifPresent(grouping -> grouping.record(fingerprint, properties.version()));

        return same.isPresent();

    }

    /**
     * 저장소 후보를 잠근 뒤 손대지 않은 NEW 후보를 지우고, AI 가 묶은 후보를 받은 순서로 저장한 다음 지문을 남긴다
     * 잠근 뒤 다시 읽은 손댄 후보의 포함된 커밋과 겹치는 묶음은 AI 를 기다리는 사이 사용자가 손댄 것이라 버리고, 그 묶음의 나머지 커밋은 다음 분석 때 다시 묶인다
     */
    @Transactional
    public void store(Long userRepositoryId, String fingerprint, List<GroupedCandidate> grouped) {

        List<Candidate> locked = candidates.findWithLockByUserRepositoryIdOrderById(userRepositoryId);
        Set<Long> touchedCommitIds = new HashSet<>(includedCommitIdsOf(touchedOf(locked)));
        Map<String, StoredCommit> commitsBySha = commitsOf(userRepositoryId, grouped);
        Map<Integer, Long> reviewComments = storedActivity.countReviewComments(userRepositoryId,
                pullRequestNumbersOf(grouped));

        candidates.deleteAllByIdInBatch(locked.stream()
                .filter(candidate -> !candidate.isTouched())
                .map(Candidate::getId)
                .toList());
        for (GroupedCandidate group : grouped) {
            List<StoredCommit> commits = group.commitShas().stream().map(commitsBySha::get).toList();
            if (commits.stream().noneMatch(commit -> touchedCommitIds.contains(commit.id()))) {
                save(userRepositoryId, group, commits, reviewComments);
            }
        }
        recordGrouping(userRepositoryId, fingerprint);

    }

    /** 상태와 상관없이 저장소 연결의 후보를 모두 센다 */
    public int countCandidates(Long userRepositoryId) {

        return (int) candidates.countByUserRepositoryId(userRepositoryId);

    }

    private static List<Candidate> touchedOf(List<Candidate> candidates) {

        return candidates.stream().filter(Candidate::isTouched).toList();

    }

    /** 사용자가 손댄 후보에서 뺀 커밋은 그 경험이 아니라는 표시일 뿐이라, 다른 후보로 다시 묶일 수 있게 넣지 않는다 */
    private List<Long> includedCommitIdsOf(List<Candidate> touched) {

        return candidateCommits.findByCandidateIdInAndIncludedTrue(touched.stream().map(Candidate::getId).toList())
                .stream()
                .map(CandidateCommit::getCommitId)
                .toList();

    }

    /** AI 는 보낸 커밋으로만 묶으므로 저장되지 않은 커밋이 오면 후보 커밋으로 이을 수 없어, 아무것도 저장하지 않고 실패한다 */
    private Map<String, StoredCommit> commitsOf(Long userRepositoryId, List<GroupedCandidate> grouped) {

        Set<String> shas = grouped.stream()
                .flatMap(group -> group.commitShas().stream())
                .collect(Collectors.toSet());
        Map<String, StoredCommit> commitsBySha = storedActivity.findCommits(userRepositoryId, shas).stream()
                .collect(Collectors.toMap(StoredCommit::sha, Function.identity()));
        Set<String> missing = new HashSet<>(shas);
        missing.removeAll(commitsBySha.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("저장되지 않은 커밋이 든 묶음이다: " + missing);
        }

        return commitsBySha;

    }

    private static Set<Integer> pullRequestNumbersOf(List<GroupedCandidate> grouped) {

        return grouped.stream()
                .flatMap(group -> group.pullRequestNumbers().stream())
                .collect(Collectors.toSet());

    }

    private void save(Long userRepositoryId, GroupedCandidate group, List<StoredCommit> commits,
                      Map<Integer, Long> reviewComments) {

        CandidateMeta meta = CandidateMeta.of(commits);
        int reviewCommentCount = (int) group.pullRequestNumbers().stream()
                .mapToLong(number -> reviewComments.getOrDefault(number, 0L))
                .sum();
        Candidate candidate = candidates.save(Candidate.grouped(userRepositoryId, group.sourceType(),
                group.pullRequestNumber(), group.sourceRef(), group.pullRequestNumbers(), group.issueNumbers(),
                group.title(), group.reason(), BigDecimal.valueOf(group.score()), group.lowCardWorth(),
                meta.fileCount(), reviewCommentCount, meta.spanDays(), meta.codeRatio()));
        candidateCommits.saveAll(commits.stream()
                .map(commit -> CandidateCommit.grouped(candidate.getId(), commit.id(),
                        CandidateMeta.displayPathOf(commit)))
                .toList());

    }

    private void recordGrouping(Long userRepositoryId, String fingerprint) {

        groupings.findById(userRepositoryId).ifPresentOrElse(
                grouping -> grouping.record(fingerprint, properties.version()),
                () -> groupings.save(CandidateGrouping.of(userRepositoryId, fingerprint, properties.version())));

    }
}
