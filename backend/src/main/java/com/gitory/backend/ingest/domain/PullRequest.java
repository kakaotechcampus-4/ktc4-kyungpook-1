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
 * GitHub 에서 수집한 PR 한 개를 담는다
 * 열림에서 머지·닫힘으로 바뀌므로 다시 수집하면 저장된 행을 이번 수집 값으로 덮어쓴다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "pull_request")
public class PullRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long repositoryId;
    private Long collectionRunId;

    private int githubPrNumber;

    private String title;
    private String bodyExcerpt;

    @Enumerated(EnumType.STRING)
    private GithubState state;

    private String authorLogin;

    private String baseBranch;
    private String headBranch;

    private Instant openedAt;
    // state 가 CLOSED 인 PR 이 머지된 것인지 그냥 닫힌 것인지는 이 칸으로만 알 수 있다
    private Instant mergedAt;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> commitShas;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<Integer> linkedIssueNumbers;

    private PullRequest(Long repositoryId, Long collectionRunId, int githubPrNumber, String title,
                        String bodyExcerpt, GithubState state, String authorLogin, String baseBranch,
                        String headBranch, Instant openedAt, Instant mergedAt, List<String> commitShas,
                        List<Integer> linkedIssueNumbers) {
        this.repositoryId = repositoryId;
        this.collectionRunId = collectionRunId;
        this.githubPrNumber = githubPrNumber;
        this.title = title;
        this.bodyExcerpt = bodyExcerpt;
        this.state = state;
        this.authorLogin = authorLogin;
        this.baseBranch = baseBranch;
        this.headBranch = headBranch;
        this.openedAt = openedAt;
        this.mergedAt = mergedAt;
        this.commitShas = commitShas;
        this.linkedIssueNumbers = linkedIssueNumbers;
    }

    public static PullRequest collected(Long repositoryId, Long collectionRunId, int githubPrNumber, String title,
                                        String bodyExcerpt, GithubState state, String authorLogin, String baseBranch,
                                        String headBranch, Instant openedAt, Instant mergedAt,
                                        List<String> commitShas, List<Integer> linkedIssueNumbers) {
        return new PullRequest(repositoryId, collectionRunId, githubPrNumber, title, bodyExcerpt, state, authorLogin,
                baseBranch, headBranch, openedAt, mergedAt, commitShas, linkedIssueNumbers);
    }
}
