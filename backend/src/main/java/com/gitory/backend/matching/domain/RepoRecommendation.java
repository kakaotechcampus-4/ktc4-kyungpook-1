package com.gitory.backend.matching.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static lombok.AccessLevel.PROTECTED;

/**
 * 레포 화면이 그대로 읽는 그 레포의 추천 기업이며, 새 공고가 들어와도 다시 계산하기 전까지 바뀌지 않는다
 * 계산이 모두 실패하면 앞서 계산한 기업은 남기고 상태만 실패로 둔다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "repo_recommendation")
public class RepoRecommendation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userRepositoryId;

    @Enumerated(EnumType.STRING)
    private RepoRecommendationStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<RecommendedCompany> companies;

    private Instant computedAt;

    public static RepoRecommendation ready(Long userRepositoryId, List<RecommendedCompany> companies, Instant now) {

        RepoRecommendation recommendation = new RepoRecommendation();
        recommendation.userRepositoryId = userRepositoryId;
        recommendation.companies = new ArrayList<>();
        recommendation.refresh(companies, now);
        return recommendation;

    }

    public static RepoRecommendation failed(Long userRepositoryId, Instant now) {

        RepoRecommendation recommendation = new RepoRecommendation();
        recommendation.userRepositoryId = userRepositoryId;
        recommendation.companies = new ArrayList<>();
        recommendation.fail(now);
        return recommendation;

    }

    public void refresh(List<RecommendedCompany> companies, Instant now) {

        this.companies = new ArrayList<>(companies);
        this.status = RepoRecommendationStatus.READY;
        this.computedAt = now;

    }

    public void fail(Instant now) {

        this.status = RepoRecommendationStatus.FAILED;
        this.computedAt = now;

    }
}
