package com.gitory.backend.ingest.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

import static lombok.AccessLevel.PROTECTED;

/**
 * GitHub 에서 수집한 PR 줄 댓글 한 개를 담는다
 * 댓글은 고칠 수 있으므로 다시 수집하면 저장된 행을 이번 수집 값으로 덮어쓴다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "pr_review_comment")
public class PrReviewComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long repositoryId;
    private int githubPrNumber;
    private Long collectionRunId;

    private long githubCommentId;

    // 상태를 몰라 건너뛴 리뷰의 댓글도 저장하므로 이 id 의 리뷰가 pr_review 에 없을 수 있다
    private Long githubReviewId;

    private String authorLogin;
    private String bodyExcerpt;

    private String path;
    private Integer line;

    private Instant commentedAt;

    private PrReviewComment(Long repositoryId, int githubPrNumber, Long collectionRunId, long githubCommentId,
                            Long githubReviewId, String authorLogin, String bodyExcerpt, String path, Integer line,
                            Instant commentedAt) {
        this.repositoryId = repositoryId;
        this.githubPrNumber = githubPrNumber;
        this.collectionRunId = collectionRunId;
        this.githubCommentId = githubCommentId;
        this.githubReviewId = githubReviewId;
        this.authorLogin = authorLogin;
        this.bodyExcerpt = bodyExcerpt;
        this.path = path;
        this.line = line;
        this.commentedAt = commentedAt;
    }

    public static PrReviewComment collected(Long repositoryId, int githubPrNumber, Long collectionRunId,
                                            long githubCommentId, Long githubReviewId, String authorLogin,
                                            String bodyExcerpt, String path, Integer line, Instant commentedAt) {

        return new PrReviewComment(repositoryId, githubPrNumber, collectionRunId, githubCommentId, githubReviewId,
                authorLogin, bodyExcerpt, path, line, commentedAt);

    }
}
