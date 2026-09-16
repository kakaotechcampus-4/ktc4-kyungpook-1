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

/**
 * GitHub 로그인 한 번을 처리한다 — 프로필을 읽고, 사용자와 연결 동의를 저장하고,
 * 세션에 담길 {@link LoginUser} 를 돌려준다.
 *
 * <p>Spring Security 의 UserInfo 단계에 끼워 넣는 이유는 <b>이 지점에만 토큰과 프로필이
 * 함께 있기 때문</b>이다. 성공 핸들러로 미루면 토큰을 다시 꺼내오는 경로가 하나 더 생긴다.
 *
 * <p>여기서 {@code OAuth2AuthenticationException} 을 던지면 실패 핸들러가 받아
 * {@code /login?error=...} 로 보낸다. 즉 로그인 실패는 500 이 아니라 리다이렉트다.
 */
@Service
public class GithubLoginService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    /** GitHub {@code /user} 응답을 가져온다. 네트워크 호출은 전부 이쪽에 있다. */
    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> githubProfile;

    private final UserRepository users;
    private final GithubConnectionRepository connections;
    private final TokenCipher tokenCipher;

    // 생성자가 둘이라 어느 쪽으로 주입할지 명시해야 한다.
    @Autowired
    public GithubLoginService(UserRepository users,
                              GithubConnectionRepository connections,
                              TokenCipher tokenCipher) {
        this(users, connections, tokenCipher, new DefaultOAuth2UserService());
    }

    /** 프로필 조회를 갈아 끼우는 생성자. 테스트가 GitHub 을 부르지 않게 하려고 열어 둔 이음매다. */
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

    /**
     * 활성 연결이 있으면 갱신하고, 없으면 새로 만든다.
     *
     * <p>매 로그인마다 새 행을 쌓지 않는 이유는 {@code uq_connection_active} 가
     * 사용자당 활성 연결 하나만 허용하기 때문이다. 동의 범위는 사용자가 GitHub 쪽에서
     * 바꿀 수 있으므로 매번 응답에 실려 온 값으로 덮어쓴다 — 우리가 요청한 범위가 아니라
     * 실제로 받은 범위가 기록돼야 "연결 범위 확인"이 사실이 된다.
     */
    private void saveConnection(Long userId, OAuth2AccessToken accessToken) {
        // 동의 범위를 먼저 검사한다 — 어차피 거절할 로그인이면 토큰을 암호화할 이유가 없다.
        String[] scopes = grantedScopes(accessToken);
        String tokenEnc = tokenCipher.encrypt(accessToken.getTokenValue());
        Instant expiresAt = accessToken.getExpiresAt();

        connections.findByUserIdAndRevokedAtIsNull(userId)
                .ifPresentOrElse(
                        connection -> connection.renew(scopes, tokenEnc, expiresAt),
                        () -> connections.save(GithubConnection.grant(userId, scopes, tokenEnc, expiresAt)));
    }

    /**
     * GitHub 은 토큰 응답의 {@code scope} 를 공백이 아니라 <b>쉼표</b>로 구분해 내려준다.
     * RFC 6749 는 공백이고 Spring 의 변환기도 공백으로만 끊으므로
     * ({@code DefaultMapOAuth2AccessTokenResponseConverter#getScopes}),
     * 그대로 저장하면 {@code "public_repo,read:user"} 한 덩어리가 스코프 하나로 들어간다.
     * 여기서 한 번 더 쪼갠다 — 구분자가 어느 쪽이든 결과가 같다.
     */
    private static String[] grantedScopes(OAuth2AccessToken accessToken) {
        String[] scopes = accessToken.getScopes().stream()
                .flatMap(scope -> Arrays.stream(scope.split("[,\\s]+")))
                .map(String::trim)
                .filter(scope -> !scope.isEmpty())
                .distinct()
                .toArray(String[]::new);

        if (scopes.length == 0) {
            // 무엇에 동의했는지 모르는 연결은 만들지 않는다 — 나중에 "연결 범위 확인"에 답할 수 없다.
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

    /**
     * 오류 코드는 {@code invalid_user_info_response} 로 고정한다. 실패 핸들러가 이 값을
     * {@code /login?error=} 에 실어 보내므로, 메시지에 토큰·내부 식별자를 넣지 않는다
     * (security-privacy.md: 진단 로그·응답에 민감값을 남기지 않음).
     */
    private static OAuth2AuthenticationException loginFailed(String message) {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_user_info_response", message, null), message);
    }
}
