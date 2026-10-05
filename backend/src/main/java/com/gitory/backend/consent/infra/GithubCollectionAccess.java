package com.gitory.backend.consent.infra;

import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import com.gitory.backend.consent.domain.GithubConnection;
import com.gitory.backend.consent.domain.GithubNotConnectedException;
import com.gitory.backend.consent.domain.User;
import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.consent.port.GithubCollectionTarget;
import com.gitory.backend.consent.port.GithubRepositoryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** consent 안에서 연결 상태를 확인하고, 복호화한 토큰은 HTTP 헤더에만 쓴다. */
@Component
@RequiredArgsConstructor
public class GithubCollectionAccess implements GithubCollectionAccessPort {

    private final UserRepository users;
    private final GithubConnectionRepository connections;
    private final TokenCipher cipher;
    private final AiHttpClient ai;
    private final GithubRepositoryClient github;

    @Override
    public JsonNode collect(Long userId, GithubCollectionTarget target) {

        User user = activeUser(userId).orElseThrow(GithubCollectionAccess::unavailable);
        String token = activeToken(userId).orElseThrow(GithubCollectionAccess::unavailable);
        AiCollectRequest request = new AiCollectRequest(target.userRepositoryId(), target.repository(),
                new AiCollectActor(user.getGithubLogin(), List.of()), target.branches());

        return ai.collect(request, token);

    }

    @Override
    public List<GithubRepositoryResponse> repositories(Long userId) {

        activeUser(userId).orElseThrow(GithubNotConnectedException::new);
        String token = activeToken(userId).orElseThrow(GithubNotConnectedException::new);

        try {
            return github.fetchRepositories(token);
        } catch (HttpClientErrorException.Unauthorized rejected) {
            throw new GithubNotConnectedException();
        }

    }

    private Optional<User> activeUser(Long userId) {

        return users.findById(userId).filter(u -> !u.isDeletionRequested());

    }

    private Optional<String> activeToken(Long userId) {

        return connections.findByUserIdAndRevokedAtIsNull(userId)
                .filter(GithubConnection::isActive)
                .filter(c -> c.getTokenExpiresAt() == null || c.getTokenExpiresAt().isAfter(Instant.now()))
                .map(this::decryptOrNull);

    }

    /** 암호문·키·외부 암호화 예외를 로그로 넘기지 않도록 복호화 실패는 연결이 없는 것과 같이 다룬다 */
    private String decryptOrNull(GithubConnection connection) {

        try {
            return cipher.decrypt(connection.getTokenEnc());
        } catch (RuntimeException failed) {
            return null;
        }

    }

    private static AiClientException unavailable() {
        return new AiClientException("GITHUB_CONNECTION_UNAVAILABLE", false, null);
    }
}
