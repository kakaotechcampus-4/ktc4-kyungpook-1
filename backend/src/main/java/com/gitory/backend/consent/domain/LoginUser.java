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
 * 세션에 직렬화돼 저장되는 로그인 사용자
 * 토큰이나 직렬화할 수 없는 필드는 담지 않는다
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
        return AUTHORITIES;
    }

    @Override
    public String getName() {
        return String.valueOf(id);
    }
}
