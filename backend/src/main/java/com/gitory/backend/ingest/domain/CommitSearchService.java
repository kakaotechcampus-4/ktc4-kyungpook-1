package com.gitory.backend.ingest.domain;

import com.gitory.backend.ingest.infra.ConnectedRepositoryRepository;
import com.gitory.backend.ingest.infra.GitCommitRepository;
import com.gitory.backend.ingest.infra.GithubRepoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 저장소에 수집해 둔 커밋 중 내 커밋을 메시지나 커밋 번호 앞부분으로 찾는다
 * 커밋은 사용자끼리 함께 쓰고 NOT_OWN 표시는 먼저 분석한 사람 기준이라, 내 커밋은 작성자 로그인으로 가린다
 */
@Service
@RequiredArgsConstructor
public class CommitSearchService {

    static final int MAX_RESULTS = 20;

    private final ConnectedRepositoryRepository connections;
    private final GithubRepoRepository repositories;
    private final GitCommitRepository commits;

    public List<FoundCommit> search(UUID publicId, Long userId, String login, String query) {

        ConnectedRepository connected = connections.findByPublicIdAndUserId(publicId, userId)
                .orElseThrow(ConnectedRepositoryNotFoundException::new);
        if (query.isBlank()) {
            return List.of();
        }

        GithubRepo repository = repositories.findById(connected.getRepositoryId()).orElseThrow();
        String keyword = escapeLike(query.strip().toLowerCase(Locale.ROOT));

        return commits.searchOwn(repository.getId(), login, "%" + keyword + "%", keyword + "%", Limit.of(MAX_RESULTS))
                .stream()
                .map(commit -> FoundCommit.of(repository, commit))
                .toList();

    }

    /** 검색어의 % · _ 를 와일드카드가 아니라 글자 그대로 찾게 한다 */
    private static String escapeLike(String keyword) {

        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");

    }
}
