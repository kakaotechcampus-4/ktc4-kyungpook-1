package com.gitory.backend.ingest.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

import static lombok.AccessLevel.PROTECTED;

/**
 * GitHub 에서 수집한 PR 리뷰 한 개를 담는다
 * 리뷰는 제출·취소(DISMISSED)·본문 수정으로 바뀌므로 다시 수집하면 저장된 행을 이번 수집 값으로 덮어쓴다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "pr_review")
public class PrReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long repositoryId;
    private int githubPrNumber;
    private Long collectionRunId;

    private long githubReviewId;

    @Enumerated(EnumType.STRING)
    private ReviewState state;

    private String authorLogin;
    private String bodyExcerpt;
    private Instant submittedAt;

    private PrReview(Long repositoryId, int githubPrNumber, Long collectionRunId, long githubReviewId,
                     ReviewState state, String authorLogin, String bodyExcerpt, Instant submittedAt) {
        this.repositoryId = repositoryId;
        this.githubPrNumber = githubPrNumber;
        this.collectionRunId = collectionRunId;
        this.githubReviewId = githubReviewId;
        this.state = state;
        this.authorLogin = authorLogin;
        this.bodyExcerpt = bodyExcerpt;
        this.submittedAt = submittedAt;
    }

    public static PrReview collected(Long repositoryId, int githubPrNumber, Long collectionRunId, long githubReviewId,
                                     ReviewState state, String authorLogin, String bodyExcerpt,
                                     Instant submittedAt) {

        return new PrReview(repositoryId, githubPrNumber, collectionRunId, githubReviewId, state, authorLogin,
                bodyExcerpt, submittedAt);

    }
}
