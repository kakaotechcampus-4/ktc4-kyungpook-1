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
 * 공고 하나에서 못 채운 조건마다 AI 가 찾은 제안이며, 찾는 동안의 진행은 메모리에만 두고 끝난 결론만 여기에 남는다
 * 지문은 다 찾았을 때만 남겨, 실패한 공고는 같은 자료로도 다시 찾는다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "match_gap_fill")
public class MatchGapFill {

    // AI 는 90초 안에 찾기를 끝내므로, 이보다 오래 RUNNING 이면 찾던 서버가 다시 시작된 것이다
    public static final Duration STUCK_AFTER = Duration.ofMinutes(5);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    private Long jobPostingId;

    @Enumerated(EnumType.STRING)
    private GapFillStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<GapFillCondition> conditions;

    private String fingerprint;

    private Instant requestedAt;

    private Instant finishedAt;

    public void finish(List<GapFillCondition> conditions, String fingerprint, Instant now) {

        this.conditions = new ArrayList<>(conditions);
        this.fingerprint = fingerprint;
        this.finishedAt = now;
        this.status = GapFillStatus.DONE;

    }

    public void fail(Instant now) {

        this.fingerprint = null;
        this.finishedAt = now;
        this.status = GapFillStatus.FAILED;

    }

    /** 찾기를 시작한 지 오래된 RUNNING 은 아무도 찾지 않는 줄이라, 화면에는 실패로 보이고 다음 요청이 다시 시작한다 */
    public boolean isStuck(Instant now) {

        return status == GapFillStatus.RUNNING && !requestedAt.isAfter(now.minus(STUCK_AFTER));

    }
}
