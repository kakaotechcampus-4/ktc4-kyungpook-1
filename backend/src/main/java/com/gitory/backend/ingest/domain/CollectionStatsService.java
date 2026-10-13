package com.gitory.backend.ingest.domain;

import com.gitory.backend.ingest.infra.CollectionStatsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 다른 모듈이 저장소 연결의 마지막 수집 기록과 저장된 커밋의 숫자를 ingest 표를 직접 읽지 않고 얻게 한다 */
@Service
@RequiredArgsConstructor
public class CollectionStatsService {

    private final CollectionStatsRepository stats;

    /** 같은 GitHub 저장소를 연결한 다른 사용자의 수집은 읽지 않으며, 수집한 적 없으면 빈 값이다 */
    public Optional<CollectionRunSummary> latestRun(UUID repositoryId, Long userId) {

        return stats.findLatestRuns(repositoryId, userId, Limit.of(1)).stream().findFirst();

    }

    /** 숫자를 세지 않은 저장소는 센 시각이 없고, 내 저장소 연결이 아니면 없는 저장소와 같이 거절한다 */
    public CollectionCounts counts(UUID repositoryId, Long userId) {

        return stats.findCounts(repositoryId, userId).orElseThrow(ConnectedRepositoryNotFoundException::new);

    }

    /** 다시 분석하면 AI 가 이미 저장한 커밋은 돌려주지 않아 수집 기록의 커밋 수는 새 커밋뿐이라, 읽어 둔 커밋은 저장된 행으로 센다 */
    public int storedCommits(UUID repositoryId, Long userId) {

        return Math.toIntExact(stats.countStoredCommits(repositoryId, userId));

    }

    /** 커밋 행은 사용자끼리 함께 쓰므로 이 저장소를 연결한 누구의 수집으로 들어왔든 센다 */
    public int commitsWithoutAuthorAccount(UUID repositoryId, Long userId) {

        return Math.toIntExact(stats.countWithoutAuthorLogin(repositoryId, userId));

    }

    /** 작성자 로그인이 login 과 같고 제외 사유가 없는 커밋의 메시지이며, 메시지가 없는 커밋은 빈 글자다 */
    public List<String> ownCommitMessages(UUID repositoryId, Long userId, String login) {

        return stats.findOwnMessages(repositoryId, userId, login);

    }
}
