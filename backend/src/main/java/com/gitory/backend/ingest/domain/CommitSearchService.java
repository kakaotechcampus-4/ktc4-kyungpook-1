package com.gitory.backend.ingest.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.ingest.infra.ConnectedRepositoryRepository;
import com.gitory.backend.ingest.infra.GitCommitRepository;
import com.gitory.backend.ingest.infra.GithubRepoRepository;
import com.gitory.backend.ingest.port.RepositoryCandidateCardPort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 저장소에 수집해 둔 커밋 중 내 커밋을 메시지나 커밋 번호 앞부분으로 찾는다
 * 커밋 행은 사용자끼리 함께 쓰므로 내 커밋은 저장된 표시가 아니라 작성자 로그인으로 가린다
 */
@Service
@RequiredArgsConstructor
public class CommitSearchService {

    static final int MAX_RESULTS = 20;

    private final ConnectedRepositoryRepository connections;
    private final GithubRepoRepository repositories;
    private final GitCommitRepository commits;
    private final RepositoryCandidateCardPort work;
    private final RepositoryOwnershipService ownership;

    public List<FoundCommit> search(UUID publicId, Long userId, String login, String query) {

        Long connectionId = ownership.findOwnedRepository(publicId, userId, AuditAction.SEARCH_COMMITS)
                .orElseThrow(ConnectedRepositoryNotFoundException::new);
        if (query.isBlank()) {
            return List.of();
        }

        ConnectedRepository connected = connections.findById(connectionId).orElseThrow();
        GithubRepo repository = repositories.findById(connected.getRepositoryId()).orElseThrow();
        String keyword = escapeLike(query.strip().toLowerCase(Locale.ROOT));

        List<GitCommit> found = commits.searchOwn(repository.getId(), login, "%" + keyword + "%", keyword + "%",
                Limit.of(MAX_RESULTS));
        Map<Long, UUID> candidateIds = work.candidateIdsByCommit(connected.getId(),
                found.stream().map(GitCommit::getId).toList());

        return found.stream()
                .map(commit -> FoundCommit.of(repository, commit, candidateIds.get(commit.getId())))
                .toList();

    }

    /** 검색어의 % · _ 를 와일드카드가 아니라 글자 그대로 찾게 한다 */
    private static String escapeLike(String keyword) {

        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");

    }
}
