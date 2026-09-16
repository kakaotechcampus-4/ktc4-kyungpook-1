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

/**
 * 한 사용자의 GitHub 연결 동의. "어떤 범위에 동의했는지 · 언제 철회했는지"가 여기 남는다.
 *
 * <p>사용자당 활성 연결은 하나다 —
 * {@code uq_connection_active ON github_connection (user_id) WHERE revoked_at IS NULL}.
 * 그래서 재로그인은 새 행을 만드는 게 아니라 {@link #renew} 로 기존 행을 갱신한다.
 *
 * <p>⚠️ {@code tokenEnc} 는 <b>반드시 암호화된 값</b>이다. 평문 토큰이 이 필드에 들어가면
 * 스펙("장기 토큰을 평문 저장하지 않는다")을 정면으로 어긴다. 암호화는
 * {@code consent.infra.TokenCipher} 한 곳에서만 한다.
 *
 * <p>이 객체는 토큰을 노출하는 getter 를 만들지 않는다. 토큰이 필요한 쪽은 consent 모듈
 * 안에 있고, 밖으로는 "이 범위로 호출할 수 있는 클라이언트"만 나간다(package-info).
 */
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

    /** 암호화된 액세스 토큰. 철회하면 NULL 이 된다(CHECK connection_revoked_has_no_token). */
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

    /** 첫 연결. */
    public static GithubConnection grant(Long userId, String[] scopes, String tokenEnc, Instant tokenExpiresAt) {
        return new GithubConnection(userId, requireScopes(scopes), requireToken(tokenEnc), tokenExpiresAt);
    }

    /**
     * 재로그인. 동의 범위와 토큰이 매번 달라질 수 있으므로 통째로 갈아 끼운다.
     *
     * <p>철회된 연결을 되살리지는 않는다 — 철회 후 재연결은 새 동의이고, 활성 유니크 인덱스가
     * 비어 있으므로 새 행({@link #grant})을 만드는 것이 맞다.
     */
    public void renew(String[] scopes, String tokenEnc, Instant tokenExpiresAt) {
        if (revokedAt != null) {
            throw new IllegalStateException("철회된 연결은 갱신할 수 없다 — 새로 동의받아야 한다");
        }
        this.scopes = requireScopes(scopes);
        this.tokenEnc = requireToken(tokenEnc);
        this.tokenExpiresAt = tokenExpiresAt;
    }

    /** 연결 해제. 토큰을 먼저 지운다 — 철회된 연결은 토큰을 들고 있을 수 없다. */
    public void revoke() {
        this.tokenEnc = null;
        this.tokenExpiresAt = null;
        this.revokedAt = Instant.now();
    }

    public boolean isActive() {
        return revokedAt == null && tokenEnc != null;
    }

    /** 화면·API 로 나가는 값. 배열을 그대로 내보내면 호출자가 내부 상태를 바꿀 수 있다. */
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
