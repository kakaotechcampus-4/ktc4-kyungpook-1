package com.gitory.backend.ingest.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

import static lombok.AccessLevel.PROTECTED;

/**
 * GitHub 에서 수집한 이슈 한 개를 담는다
 * 열림에서 닫힘으로 바뀌므로 다시 수집하면 저장된 행을 이번 수집 값으로 덮어쓴다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "issue")
public class Issue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long repositoryId;
    private Long collectionRunId;

    private int githubIssueNumber;

    private String title;
    private String bodyExcerpt;

    @Enumerated(EnumType.STRING)
    private GithubState state;

    private String authorLogin;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> labels;

    private Instant openedAt;
    private Instant closedAt;

    private Issue(Long repositoryId, Long collectionRunId, int githubIssueNumber, String title, String bodyExcerpt,
                  GithubState state, String authorLogin, List<String> labels, Instant openedAt, Instant closedAt) {
        this.repositoryId = repositoryId;
        this.collectionRunId = collectionRunId;
        this.githubIssueNumber = githubIssueNumber;
        this.title = title;
        this.bodyExcerpt = bodyExcerpt;
        this.state = state;
        this.authorLogin = authorLogin;
        this.labels = labels;
        this.openedAt = openedAt;
        this.closedAt = closedAt;
    }

    public static Issue collected(Long repositoryId, Long collectionRunId, int githubIssueNumber, String title,
                                  String bodyExcerpt, GithubState state, String authorLogin, List<String> labels,
                                  Instant openedAt, Instant closedAt) {
        return new Issue(repositoryId, collectionRunId, githubIssueNumber, title, bodyExcerpt, state, authorLogin,
                labels, openedAt, closedAt);
    }
}
