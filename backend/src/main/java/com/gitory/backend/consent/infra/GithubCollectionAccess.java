package com.gitory.backend.consent.infra;

import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import com.gitory.backend.consent.domain.GithubConnection;
import com.gitory.backend.consent.domain.User;
import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.consent.port.GithubCollectionTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/** consent 안에서 연결 상태를 확인하고, 복호화한 토큰은 HTTP 헤더에만 쓴다. */
@Component
@RequiredArgsConstructor
public class GithubCollectionAccess implements GithubCollectionAccessPort {

    private final UserRepository users;
    private final GithubConnectionRepository connections;
    private final TokenCipher cipher;
    private final AiHttpClient ai;

    @Override
    public JsonNode collect(Long userId, GithubCollectionTarget target) {
        User user = users.findById(userId).filter(u -> !u.isDeletionRequested())
                .orElseThrow(GithubCollectionAccess::unavailable);
        GithubConnection connection = connections.findByUserIdAndRevokedAtIsNull(userId)
                .filter(GithubConnection::isActive)
                .filter(c -> c.getTokenExpiresAt() == null || c.getTokenExpiresAt().isAfter(Instant.now()))
                .orElseThrow(GithubCollectionAccess::unavailable);
        String token;
        try {
            token = cipher.decrypt(connection.getTokenEnc());
        } catch (RuntimeException failed) {
            // 암호문·키·외부 암호화 예외를 로그로 넘기지 않는다.
            throw unavailable();
        }
        AiCollectRequest request = new AiCollectRequest(target.userRepositoryId(), target.repository(),
                new AiCollectActor(user.getGithubLogin(), List.of()), target.branches());
        return ai.collect(request, token);
    }

    private static AiClientException unavailable() {
        return new AiClientException("GITHUB_CONNECTION_UNAVAILABLE", false, null);
    }
}
