package com.gitory.backend.ingest.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;
import static lombok.AccessLevel.PROTECTED;

/**
 * 사용자와 GitHub 저장소의 연결 한 건으로, user_repository 에서 지금 쓰는 컬럼만 매핑한다
 * 여러 세션이 같은 사용자를 동시에 동기화할 수 있어 행 추가는 엔티티가 아니라 ConnectedRepositoryRepository 의 connectIfAbsent 로만 한다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "user_repository")
public class ConnectedRepository {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID publicId;

    private Long userId;

    private Long repositoryId;

    private Instant lastAnalyzedAt;

    private int commitCount;
    private int ownCommitCount;
    private int prCount;
    private int ownPrCount;
    private int reviewedPrCount;

    private Instant countedAt;

    public void markAnalyzed() {

        this.lastAnalyzedAt = Instant.now();

    }

    /** countedAt 은 GitHub 에 묻기 시작한 시각을 넣어, 세는 사이에 생긴 push 가 다음 조회 때 다시 세지게 한다 */
    public void recordCounts(int commitCount, int ownCommitCount, int prCount, int ownPrCount, int reviewedPrCount,
                             Instant countedAt) {

        this.commitCount = commitCount;
        this.ownCommitCount = ownCommitCount;
        this.prCount = prCount;
        this.ownPrCount = ownPrCount;
        this.reviewedPrCount = reviewedPrCount;
        this.countedAt = countedAt;

    }
}
