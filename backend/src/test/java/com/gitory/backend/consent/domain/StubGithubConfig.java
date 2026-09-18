package com.gitory.backend.consent.domain;

import com.gitory.backend.consent.infra.GithubConnectionRepository;
import com.gitory.backend.consent.infra.TokenCipher;
import com.gitory.backend.consent.infra.UserRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * GitHub 서버 두 곳(토큰 교환 · 프로필 조회)만 가짜로 바꾼다.
 *
 * <p>나머지는 전부 진짜다 — 필터 체인, 세션(DB), 사용자·연결 저장, 토큰 암호화.
 * 덕분에 "로그인 → /api/me → 로그아웃" 왕복을 GitHub 없이 끝까지 태울 수 있다.
 *
 * <p>이 클래스가 {@code consent.domain} 패키지에 있는 이유는 {@link GithubLoginService} 의
 * 이음매 생성자가 패키지 전용이기 때문이다. 이음매를 public 으로 넓히는 대신 테스트를
 * 같은 패키지에 둔다.
 */
@TestConfiguration
public class StubGithubConfig {

    public static final long GITHUB_USER_ID = 4242L;
    public static final String LOGIN = "taehun0208";
    public static final String AVATAR_URL = "https://avatars.githubusercontent.com/u/4242";
    public static final String ACCESS_TOKEN = "gho_StubbedAccessTokenExample1234567";

    /**
     * 진짜 {@link GithubLoginService} 다. 프로필 조회만 갈아 끼웠으므로 사용자·연결 저장과
     * 토큰 암호화는 운영과 같은 코드가 돈다.
     */
    @Bean
    @Primary
    GithubLoginService stubbedGithubLoginService(UserRepository users,
                                                 GithubConnectionRepository connections,
                                                 TokenCipher tokenCipher) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", GITHUB_USER_ID);
        profile.put("login", LOGIN);
        profile.put("avatar_url", AVATAR_URL);

        OAuth2User githubUser = new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"), profile, "id");

        return new GithubLoginService(users, connections, tokenCipher, request -> githubUser);
    }

    @Bean
    OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> stubbedTokenResponseClient() {
        return request -> OAuth2AccessTokenResponse.withToken(ACCESS_TOKEN)
                .tokenType(OAuth2AccessToken.TokenType.BEARER)
                .scopes(Set.of("read:user"))
                .build();
    }
}
