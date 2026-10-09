package com.gitory.backend.ingest.domain;

import com.gitory.backend.ingest.infra.CollectionRunRepository;
import com.gitory.backend.ingest.infra.ConnectedRepositoryRepository;
import com.gitory.backend.ingest.infra.GitCommitRepository;
import com.gitory.backend.ingest.port.CollectedActivity;
import com.gitory.backend.ingest.port.CollectedCommit;
import com.gitory.backend.ingest.port.IngestRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AI 가 돌려준 수집 결과를 수집 이력 한 건과 커밋 여러 건으로 저장한다
 * 커밋은 GitHub 에 이미 일어난 사실이라 이미 저장한 sha 는 다시 저장하지 않는다
 */
@Service
@RequiredArgsConstructor
public class ActivityStoreService {

    private final ConnectedRepositoryRepository connectedRepositories;
    private final CollectionRunRepository runs;
    private final GitCommitRepository commits;

    /** AI가 재수집에서 상세 조회를 생략할 수 있도록 이미 저장한 SHA를 제공한다. */
    @Transactional(readOnly = true)
    public List<String> knownCommitShas(Long userRepositoryId) {

        connectedRepositoryOf(userRepositoryId);
        return commits.findAllShasByUserRepositoryId(userRepositoryId);

    }

    @Transactional
    public Long store(IngestRequest request, CollectedActivity activity) {

        ConnectedRepository connected = connectedRepositoryOf(request.userRepositoryId());
        CollectionRun run = runs.save(runOf(request, activity));

        storeNewCommits(connected.getRepositoryId(), run.getId(), activity.commits());
        connected.markAnalyzed();

        return run.getId();
    }

    private ConnectedRepository connectedRepositoryOf(Long userRepositoryId) {

        return connectedRepositories.findById(userRepositoryId)
                .orElseThrow(() -> new IllegalStateException("연결된 저장소가 없다: " + userRepositoryId));
    }

    private CollectionRun runOf(IngestRequest request, CollectedActivity activity) {

        return CollectionRun.recorded(request.userRepositoryId(), request.branches(), null, activity.headSha(),
                activity.commits().size(), activity.pullRequests().size(), activity.issues().size(),
                activity.partialReason());
    }

    private void storeNewCommits(Long repositoryId, Long collectionRunId, List<CollectedCommit> collected) {

        List<String> shas = collected.stream().map(CollectedCommit::sha).toList();
        Set<String> stored = new HashSet<>(commits.findStoredShas(repositoryId, shas));

        for (CollectedCommit commit : collected) {
            if (stored.contains(commit.sha())) {
                continue;
            }
            stored.add(commit.sha());
            commits.save(commitOf(repositoryId, collectionRunId, commit));
        }
    }

    private GitCommit commitOf(Long repositoryId, Long collectionRunId, CollectedCommit commit) {

        return GitCommit.collected(repositoryId, collectionRunId, commit.sha(), commit.authorLogin(),
                commit.authorName(), commit.message(), commit.authoredAt(), commit.additions(),
                commit.deletions(), commit.changedFiles(), commit.parentCount(), commit.exclusionReason());
    }
}
