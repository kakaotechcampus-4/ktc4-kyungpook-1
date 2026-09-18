package com.gitory.backend.consent.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import static lombok.AccessLevel.PROTECTED;

@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "github_connection")
public class GithubConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    /** PostgreSQL {@code TEXT[]}. 최소 권한만 담긴다 — read:user, public_repo. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] scopes;

    /** tokenEnc 에는 TokenCipher 로 암호화한 값만 넣는다 */
    private String tokenEnc;

    private Instant tokenExpiresAt;

    @CreationTimestamp
    private Instant grantedAt;

    private Instant revokedAt;

    private GithubConnection(Long userId, String[] scopes, String tokenEnc, Instant tokenExpiresAt) {
        this.userId = userId;
        this.scopes = scopes;
        this.tokenEnc = tokenEnc;
        this.tokenExpiresAt = tokenExpiresAt;
    }

    public static GithubConnection grant(Long userId, String[] scopes, String tokenEnc, Instant tokenExpiresAt) {
        return new GithubConnection(userId, requireScopes(scopes), requireToken(tokenEnc), tokenExpiresAt);
    }

    public void renew(String[] scopes, String tokenEnc, Instant tokenExpiresAt) {
        if (revokedAt != null) {
            throw new IllegalStateException("철회된 연결은 갱신할 수 없다 — 새로 동의받아야 한다");
        }
        this.scopes = requireScopes(scopes);
        this.tokenEnc = requireToken(tokenEnc);
        this.tokenExpiresAt = tokenExpiresAt;
    }

    public void revoke() {
        this.tokenEnc = null;
        this.tokenExpiresAt = null;
        this.revokedAt = Instant.now();
    }

    public boolean isActive() {
        return revokedAt == null && tokenEnc != null;
    }

    public List<String> scopeList() {
        return List.of(scopes);
    }

    private static String[] requireScopes(String[] scopes) {
        if (scopes == null || scopes.length == 0) {
            throw new IllegalArgumentException("동의 범위가 비어 있다 — 무엇에 동의했는지 남지 않는다");
        }
        return scopes.clone();
    }

    private static String requireToken(String tokenEnc) {
        return Objects.requireNonNull(tokenEnc, "암호화된 토큰이 필요하다");
    }
}
