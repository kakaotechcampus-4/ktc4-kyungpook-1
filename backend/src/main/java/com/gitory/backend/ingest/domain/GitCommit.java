package com.gitory.backend.ingest.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

import static lombok.AccessLevel.PROTECTED;

/**
 * 수집한 커밋 한 개. GitHub 에 이미 일어난 사실이라 저장 뒤에는 바뀌지 않는다.
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "git_commit")
public class GitCommit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long repositoryId;
    private Long collectionRunId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 40)
    private String sha;

    // GitHub 계정과 연결되지 않은 커밋은 login 이 없고 name 만 남는다
    private String authorLogin;
    private String authorName;

    private String message;
    private Instant authoredAt;

    private Integer additions;
    private Integer deletions;
    private Integer changedFiles;
    private short parentCount;

    @Column(name = "is_excluded")
    private boolean excluded;

    @Enumerated(EnumType.STRING)
    private ExclusionReason exclusionReason;

    private GitCommit(Long repositoryId, Long collectionRunId, String sha, String authorLogin, String authorName,
                      String message, Instant authoredAt, Integer additions, Integer deletions, Integer changedFiles,
                      short parentCount, ExclusionReason exclusionReason) {
        this.repositoryId = repositoryId;
        this.collectionRunId = collectionRunId;
        this.sha = sha;
        this.authorLogin = authorLogin;
        this.authorName = authorName;
        this.message = message;
        this.authoredAt = authoredAt;
        this.additions = additions;
        this.deletions = deletions;
        this.changedFiles = changedFiles;
        this.parentCount = parentCount;
        this.excluded = exclusionReason != null;
        this.exclusionReason = exclusionReason;
    }

    /**
     * 수집한 커밋을 만든다. exclusionReason 이 null 이면 제외하지 않은 커밋이다.
     */
    public static GitCommit collected(Long repositoryId, Long collectionRunId, String sha, String authorLogin,
                                      String authorName, String message, Instant authoredAt, Integer additions,
                                      Integer deletions, Integer changedFiles, short parentCount,
                                      ExclusionReason exclusionReason) {
        return new GitCommit(repositoryId, collectionRunId, sha, authorLogin, authorName, message, authoredAt,
                additions, deletions, changedFiles, parentCount, exclusionReason);
    }
}
