package com.gitory.backend.recommend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

import static lombok.AccessLevel.PROTECTED;

/**
 * 후보에 든 커밋 한 개로, 사용자가 빼도 행을 지우지 않고 포함 여부만 바꾼다
 * 화면이 커밋마다 보여 줄 경로는 묶을 때 정해 displayPath 에 둔다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "candidate_commit")
public class CandidateCommit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long candidateId;
    private Long commitId;

    @Enumerated(EnumType.STRING)
    private CandidateCommitOrigin origin;

    @Column(name = "is_included")
    private boolean included;

    private String displayPath;

    @UpdateTimestamp
    private Instant updatedAt;

    private CandidateCommit(Long candidateId, Long commitId, CandidateCommitOrigin origin, String displayPath) {
        this.candidateId = candidateId;
        this.commitId = commitId;
        this.origin = origin;
        this.included = true;
        this.displayPath = displayPath;
    }

    /** AI 가 후보에 넣은 커밋을 포함된 상태로 만든다 */
    public static CandidateCommit grouped(Long candidateId, Long commitId, String displayPath) {

        return new CandidateCommit(candidateId, commitId, CandidateCommitOrigin.AI, displayPath);

    }

    /** 사용자가 직접 붙인 커밋을 포함된 상태로 만든다 */
    public static CandidateCommit attached(Long candidateId, Long commitId, String displayPath) {

        return new CandidateCommit(candidateId, commitId, CandidateCommitOrigin.USER, displayPath);

    }

    public void include() {

        this.included = true;

    }

    public void exclude() {

        this.included = false;

    }
}
