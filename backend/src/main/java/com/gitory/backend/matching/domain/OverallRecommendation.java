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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static lombok.AccessLevel.PROTECTED;

/**
 * 공고 페이지의 '내 조건으로 매칭하기' 결과이며, 사용자가 다시 누르기 전에는 카드가 바뀌어도 기업이 바뀌지 않는다
 * 계산이 실패해도 앞서 계산한 기업과 그때의 입력 지문은 남겨, 화면이 지난 결과와 '조건이 바뀌었어요' 를 계속 보여 줄 수 있게 한다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "overall_recommendation")
public class OverallRecommendation {

    // 공고 15곳을 5곳씩 나눠 물어도 1분 안팎이라, 이보다 오래 COMPUTING 이면 계산하던 서버가 다시 시작된 것이다
    public static final Duration STUCK_AFTER = Duration.ofMinutes(10);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    @Enumerated(EnumType.STRING)
    private OverallRecommendationStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<OverallCompany> companies;

    private String fingerprint;

    private int progressDone;

    private int progressTotal;

    private Instant requestedAt;

    private Instant computedAt;

    public void refresh(List<OverallCompany> companies, String fingerprint, Instant now) {

        this.companies = new ArrayList<>(companies);
        this.fingerprint = fingerprint;
        this.computedAt = now;
        this.status = OverallRecommendationStatus.READY;

    }

    public void fail(Instant now) {

        this.computedAt = now;
        this.status = OverallRecommendationStatus.FAILED;

    }

    /** 계산을 시작한 지 오래된 COMPUTING 은 아무도 계산하지 않는 줄이라, 화면에는 실패로 보이고 다음 요청이 다시 시작한다 */
    public boolean isStuck(Instant now) {

        return status == OverallRecommendationStatus.COMPUTING && !requestedAt.isAfter(now.minus(STUCK_AFTER));

    }
}
