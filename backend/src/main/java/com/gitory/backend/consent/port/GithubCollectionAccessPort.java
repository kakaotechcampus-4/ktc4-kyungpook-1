package com.gitory.backend.consent.port;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 토큰 대신 범위가 고정된 수집 호출 권한을 제공한다. */
public interface GithubCollectionAccessPort {

    JsonNode collect(Long userId, GithubCollectionTarget target, List<String> knownCommitShas);

    /** 후보 커밋의 diff 를 AI 가 읽어 커밋별 근거로 요약하며, 토큰은 이 호출 안의 헤더에만 쓴다 */
    DiffEvidenceResult diffEvidence(Long userId, DiffEvidenceTarget target);

    /** 쓸 수 있는 GitHub 연결이 없으면 GithubNotConnectedException 을 던지고, GitHub 은 부르지 않는다 */
    void requireUsableConnection(Long userId);

    /**
     * 쓸 수 있는 GitHub 연결이 없거나 GitHub 이 토큰을 거절하면 GithubNotConnectedException 을 던지며 거절이면 연결도 해제로 남긴다
     * GitHub 이 사용 한도 때문에 거절하면 연결은 두고 GithubRateLimitedException 을 던진다
     */
    List<GithubRepositoryResponse> repositories(Long userId);

    /** GitHub 이 세지 못한 저장소는 결과에서 빠진다 */
    List<GithubRepositoryCount> countActivity(Long userId, List<GithubCountTarget> targets);

    /** 연결이 없거나 GitHub 에 묻지 못하면 예외 없이 빈 값을 돌려준다 */
    Optional<Instant> rateLimitResetAt(Long userId);
}
