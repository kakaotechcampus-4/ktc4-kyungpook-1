package com.gitory.backend.consent.domain;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 세션에 담기는 로그인 주체. 인증된 요청이 "누구인지" 아는 유일한 경로다.
 *
 * <p><b>여기에 액세스 토큰을 담지 않는다.</b> 세션은 DB(SPRING_SESSION_ATTRIBUTES)에
 * 직렬화돼 들어가므로, 토큰을 넣으면 암호화된 token_enc 옆에 평문 사본이 하나 더 생긴다.
 * 토큰이 필요한 쪽은 user id 로 {@code github_connection} 을 찾아 복호화한다.
 *
 * <p>{@code avatarUrl} 만 DB 가 아니라 GitHub 응답에서 와서 세션에 실린다 — 우리 스키마에
 * 아바타 컬럼이 없고, GitHub 이 주는 표시용 값을 굳이 복제해 보관할 이유도 없다.
 *
 * <p>⚠️ {@link Serializable} 이어야 한다. 세션이 JDBC 에 바이트로 저장되므로
 * 직렬화할 수 없는 필드를 넣으면 로그인 직후 세션 저장에서 터진다.
 */
public record LoginUser(Long id, String login, String avatarUrl) implements OAuth2User, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final List<GrantedAuthority> AUTHORITIES = List.of(new SimpleGrantedAuthority("ROLE_USER"));

    @Override
    public Map<String, Object> getAttributes() {
        // avatarUrl 이 null 일 수 있어 Map.of 를 쓰지 않는다.
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("id", id);
        attributes.put("login", login);
        attributes.put("avatarUrl", avatarUrl);
        return Collections.unmodifiableMap(attributes);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // 역할 구분이 없는 서비스다. 권한 판정은 "이 자원의 주인인가"로 하고,
        // 그 판정은 consent 모듈이 한다(package-info).
        return AUTHORITIES;
    }

    /**
     * Spring Security 가 주체 이름으로 쓰는 값. {@code SPRING_SESSION.PRINCIPAL_NAME} 에도
     * 이 값이 들어가므로, GitHub 사용자명이 아니라 바뀌지 않는 내부 id 를 쓴다.
     */
    @Override
    public String getName() {
        return String.valueOf(id);
    }
}
