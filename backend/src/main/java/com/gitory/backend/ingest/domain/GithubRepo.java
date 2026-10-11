package com.gitory.backend.ingest.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

import static lombok.AccessLevel.PROTECTED;

/**
 * GitHub 저장소 한 개를 담는다
 * 여러 사용자가 같은 저장소를 동시에 동기화할 수 있어 저장은 엔티티가 아니라 GithubRepoRepository 의 upsert 로만 한다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "repository")
public class GithubRepo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long githubRepoId;

    private String ownerLogin;
    private String name;

    @Enumerated(EnumType.STRING)
    private RepositoryVisibility visibility;

    private String primaryLanguage;
    private String defaultBranch;

    private Instant githubCreatedAt;
    private Instant githubPushedAt;
}
