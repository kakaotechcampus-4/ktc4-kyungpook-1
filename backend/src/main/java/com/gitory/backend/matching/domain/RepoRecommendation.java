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
 * 계산하는 사이에 확정 카드가 또 바뀌면 staleSince 가 남아 있어 끝나도 COMPUTING 으로 두고, 다음 계산이 다시 한다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "repo_recommendation")
public class RepoRecommendation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    private Long userRepositoryId;

    @Enumerated(EnumType.STRING)
    private RepoRecommendationStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<RecommendedCompany> companies;

    private Instant computedAt;

    private Instant staleSince;

    public static RepoRecommendation ready(Long userId, Long userRepositoryId, List<RecommendedCompany> companies,
                                           Instant now) {

        RepoRecommendation recommendation = new RepoRecommendation(userId, userRepositoryId);
        recommendation.refresh(companies, now);
        return recommendation;

    }

    public static RepoRecommendation failed(Long userId, Long userRepositoryId, Instant now) {

        RepoRecommendation recommendation = new RepoRecommendation(userId, userRepositoryId);
        recommendation.fail(now);
        return recommendation;

    }

    private RepoRecommendation(Long userId, Long userRepositoryId) {
        this.userId = userId;
        this.userRepositoryId = userRepositoryId;
        this.companies = new ArrayList<>();
    }

    public void refresh(List<RecommendedCompany> companies, Instant now) {

        this.companies = new ArrayList<>(companies);
        this.computedAt = now;
        this.status = staleSince == null ? RepoRecommendationStatus.READY : RepoRecommendationStatus.COMPUTING;

    }

    /** 앞서 계산한 기업은 남겨, 화면이 실패를 알리면서도 지난 추천을 보여 줄 수 있게 한다 */
    public void fail(Instant now) {

        this.computedAt = now;
        this.status = staleSince == null ? RepoRecommendationStatus.FAILED : RepoRecommendationStatus.COMPUTING;

    }
}
