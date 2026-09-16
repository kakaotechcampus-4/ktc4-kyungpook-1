package com.gitory.backend.consent.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

import static lombok.AccessLevel.PROTECTED;

/**
 * 서비스 사용자 한 명. GitHub 계정 하나에 정확히 하나 대응한다.
 *
 * <p>여기에 토큰은 없다. 액세스 토큰은 {@link GithubConnection} 이 들고 있다 —
 * 프로필을 읽는 모든 쿼리가 토큰을 함께 읽지 않게 하려는 분리다(V1__init.sql 주석).
 *
 * <p>필드를 추가하려면 마이그레이션에 컬럼부터 추가한다 (ddl-auto: validate).
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** GitHub 이 계정을 식별하는 번호. login 과 달리 바뀌지 않으므로 이쪽이 식별자다. */
    private Long githubUserId;

    /** GitHub 사용자명. 사용자가 바꿀 수 있으므로 로그인할 때마다 최신 값으로 갱신한다. */
    private String githubLogin;

    // DB 기본값 now() 에 맡기면 JPA 가 null 을 넣어 NOT NULL 제약에 걸린다 (AnalysisJob 과 같은 이유).
    @CreationTimestamp
    private Instant createdAt;

    private Instant lastLoginAt;

    /**
     * 삭제 '요청' 시각이다. 실제 삭제는 별도 배치가 7일 안에 수행한다(V1__init.sql 주석).
     * 그래서 이 값이 있다고 행이 사라진 것은 아니다.
     */
    private Instant deletedAt;

    private User(Long githubUserId, String githubLogin) {
        this.githubUserId = githubUserId;
        this.githubLogin = githubLogin;
        this.lastLoginAt = Instant.now();
    }

    /** 첫 로그인. 가입 시점이 곧 첫 로그인이므로 lastLoginAt 도 함께 찍는다. */
    public static User register(Long githubUserId, String githubLogin) {
        return new User(githubUserId, githubLogin);
    }

    /** 재로그인. GitHub 에서 사용자명을 바꿨을 수 있으므로 매번 받아 적는다. */
    public void recordLogin(String githubLogin) {
        this.githubLogin = githubLogin;
        this.lastLoginAt = Instant.now();
    }

    public boolean isDeletionRequested() {
        return deletedAt != null;
    }
}
