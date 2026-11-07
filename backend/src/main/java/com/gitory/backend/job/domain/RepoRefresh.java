package com.gitory.backend.job.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

import static lombok.AccessLevel.PROTECTED;

/**
 * 서버가 정리한 레포를 스스로 다시 읽은 일 한 건이며, 사용자가 직접 누른 분석과 초안 Job 에는 만들어지지 않는다
 * 보지 않은 READY 만 화면의 알림이고, 보았다는 표시는 상태를 바꾸지 않고 seenAt 으로 남긴다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "repo_refresh")
public class RepoRefresh {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private Long userRepositoryId;
    private Long analysisJobId;
    private Long draftJobId;
    private Instant previousAnalyzedAt;
    private int cardCount;

    @Enumerated(EnumType.STRING)
    private RepoRefreshState state;

    private Instant createdAt;
    private Instant finishedAt;
    private Instant seenAt;

    private RepoRefresh(Long userId, Long userRepositoryId, Long analysisJobId, Instant previousAnalyzedAt,
                        Instant now) {
        this.userId = userId;
        this.userRepositoryId = userRepositoryId;
        this.analysisJobId = analysisJobId;
        this.previousAnalyzedAt = previousAnalyzedAt;
        this.state = RepoRefreshState.READING;
        this.createdAt = now;
    }

    public static RepoRefresh reading(Long userId, Long userRepositoryId, Long analysisJobId,
                                      Instant previousAnalyzedAt, Instant now) {

        return new RepoRefresh(userId, userRepositoryId, analysisJobId, previousAnalyzedAt, now);

    }

    public void startDrafting(Long draftJobId, int cardCount) {

        requireState(RepoRefreshState.READING);
        this.state = RepoRefreshState.DRAFTING;
        this.draftJobId = draftJobId;
        this.cardCount = cardCount;

    }

    public void ready(Instant now) {

        requireState(RepoRefreshState.DRAFTING);
        this.state = RepoRefreshState.READY;
        this.finishedAt = now;

    }

    /** 알릴 것 없이 끝내며, 이미 끝난 기록은 그대로 둔다 */
    public void close(Instant now) {

        if (state != RepoRefreshState.READING && state != RepoRefreshState.DRAFTING) {
            return;
        }

        this.state = RepoRefreshState.CLOSED;
        this.finishedAt = now;

    }

    private void requireState(RepoRefreshState expected) {

        if (state != expected) {
            throw new IllegalStateException("스스로 다시 읽기 상태가 " + expected + " 가 아니다: " + state);
        }

    }
}
