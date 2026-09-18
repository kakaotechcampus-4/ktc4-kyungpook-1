package com.gitory.backend.consent.domain;

import com.gitory.backend.consent.infra.GithubConnectionRepository;
import com.gitory.backend.consent.infra.TokenCipher;
import com.gitory.backend.consent.infra.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

/** 토큰과 프로필이 함께 있는 UserInfo 단계에서 사용자와 GitHub 연결을 저장한다 */
@Service
public class GithubLoginService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> githubProfile;

    private final UserRepository users;
    private final GithubConnectionRepository connections;
    private final TokenCipher tokenCipher;

    @Autowired
    public GithubLoginService(UserRepository users,
                              GithubConnectionRepository connections,
                              TokenCipher tokenCipher) {
        this(users, connections, tokenCipher, new DefaultOAuth2UserService());
    }

    GithubLoginService(UserRepository users,
                       GithubConnectionRepository connections,
                       TokenCipher tokenCipher,
                       OAuth2UserService<OAuth2UserRequest, OAuth2User> githubProfile) {
        this.users = users;
        this.connections = connections;
        this.tokenCipher = tokenCipher;
        this.githubProfile = githubProfile;
    }

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        Map<String, Object> profile = githubProfile.loadUser(request).getAttributes();

        long githubUserId = githubUserId(profile);
        String login = requiredString(profile, "login");
        String avatarUrl = optionalString(profile, "avatar_url");

        User user = users.findByGithubUserId(githubUserId)
                .map(existing -> {
                    existing.recordLogin(login);
                    return existing;
                })
                .orElseGet(() -> users.save(User.register(githubUserId, login)));

        saveConnection(user.getId(), request.getAccessToken());

        return new LoginUser(user.getId(), login, avatarUrl);
    }

    private void saveConnection(Long userId, OAuth2AccessToken accessToken) {
        String[] scopes = grantedScopes(accessToken);
        String tokenEnc = tokenCipher.encrypt(accessToken.getTokenValue());
        Instant expiresAt = realExpiry(accessToken);

        connections.findByUserIdAndRevokedAtIsNull(userId)
                .ifPresentOrElse(
                        connection -> connection.renew(scopes, tokenEnc, expiresAt),
                        () -> connections.save(GithubConnection.grant(userId, scopes, tokenEnc, expiresAt)));
    }

    /** Spring 은 만료 없는 토큰도 발급 1초 뒤를 만료로 채우므로 그 값은 null 로 되돌린다 */
    private static Instant realExpiry(OAuth2AccessToken accessToken) {
        Instant issuedAt = accessToken.getIssuedAt();
        Instant expiresAt = accessToken.getExpiresAt();

        if (issuedAt == null || expiresAt == null) {
            return expiresAt;
        }
        return expiresAt.isAfter(issuedAt.plusSeconds(1)) ? expiresAt : null;
    }

    private static String[] grantedScopes(OAuth2AccessToken accessToken) {
        String[] scopes = accessToken.getScopes().stream()
                .flatMap(scope -> Arrays.stream(scope.split("[,\\s]+")))
                .map(String::trim)
                .filter(scope -> !scope.isEmpty())
                .distinct()
                .toArray(String[]::new);

        if (scopes.length == 0) {
            throw loginFailed("동의 범위를 확인할 수 없습니다. 다시 시도해 주세요.");
        }
        return scopes;
    }

    private static long githubUserId(Map<String, Object> profile) {
        Object id = profile.get("id");
        if (!(id instanceof Number number)) {
            throw loginFailed("GitHub 계정 정보를 읽지 못했습니다. 다시 시도해 주세요.");
        }
        return number.longValue();
    }

    private static String requiredString(Map<String, Object> profile, String key) {
        String value = optionalString(profile, key);
        if (value == null || value.isBlank()) {
            throw loginFailed("GitHub 계정 정보를 읽지 못했습니다. 다시 시도해 주세요.");
        }
        return value;
    }

    private static String optionalString(Map<String, Object> profile, String key) {
        return profile.get(key) instanceof String value ? value : null;
    }

    private static OAuth2AuthenticationException loginFailed(String message) {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_user_info_response", message, null), message);
    }
}
