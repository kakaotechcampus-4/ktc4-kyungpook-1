package com.gitory.backend.ingest.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

import static lombok.AccessLevel.PROTECTED;

/**
 * 한 번의 수집 기록. 무엇을 얼마나 읽었는지 남겨 카드가 나중에도 근거를 설명할 수 있게 한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "collection_run")
public class CollectionRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userRepositoryId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> branches;

    private Instant since;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 40)
    private String headSha;

    private int commitsCollected;
    private int prsCollected;
    private int issuesCollected;

    private boolean partial;

    @Enumerated(EnumType.STRING)
    private PartialReason partialReason;

    @CreationTimestamp
    private Instant collectedAt;

    private CollectionRun(Long userRepositoryId, List<String> branches, Instant since, String headSha,
                          int commitsCollected, int prsCollected, int issuesCollected, PartialReason partialReason) {
        this.userRepositoryId = userRepositoryId;
        this.branches = branches;
        this.since = since;
        this.headSha = headSha;
        this.commitsCollected = commitsCollected;
        this.prsCollected = prsCollected;
        this.issuesCollected = issuesCollected;
        this.partial = partialReason != null;
        this.partialReason = partialReason;
    }

    /**
     * 수집 결과를 기록한다. partialReason 이 null 이면 끝까지 다 읽은 수집이다.
     */
    public static CollectionRun recorded(Long userRepositoryId, List<String> branches, Instant since, String headSha,
                                         int commitsCollected, int prsCollected, int issuesCollected,
                                         PartialReason partialReason) {
        return new CollectionRun(userRepositoryId, branches, since, headSha,
                commitsCollected, prsCollected, issuesCollected, partialReason);
    }
}
