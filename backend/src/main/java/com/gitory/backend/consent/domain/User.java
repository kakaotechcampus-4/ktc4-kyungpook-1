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

@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long githubUserId;

    private String githubLogin;

    @CreationTimestamp
    private Instant createdAt;

    private Instant lastLoginAt;

    private Instant deletedAt;

    private User(Long githubUserId, String githubLogin) {
        this.githubUserId = githubUserId;
        this.githubLogin = githubLogin;
        this.lastLoginAt = Instant.now();
    }

    public static User register(Long githubUserId, String githubLogin) {
        return new User(githubUserId, githubLogin);
    }

    public void recordLogin(String githubLogin) {
        this.githubLogin = githubLogin;
        this.lastLoginAt = Instant.now();
    }

    public boolean isDeletionRequested() {
        return deletedAt != null;
    }
}
