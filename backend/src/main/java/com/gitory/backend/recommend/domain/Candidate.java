package com.gitory.backend.recommend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static lombok.AccessLevel.PROTECTED;

/**
 * 카드로 만들 만한 경험 후보 한 개로, 사용자가 손댄 후보는 다시 분석해도 지우거나 바꾸지 않는다
 * tech_tags·created_at 은 쓰는 곳이 없어 매핑하지 않는다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "candidate")
public class Candidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // URL 에는 내부 id 대신 이 값(UUID)만 내보낸다
    private UUID publicId;

    private Long userRepositoryId;

    @Enumerated(EnumType.STRING)
    private CandidateType sourceType;

    // AI 가 준 후보는 대표 PR 번호이고 후보에 든 PR 은 이 번호를 포함해 pullRequestNumbers 에 모두 있다
    private Integer githubPrNumber;

    private String sourceRef;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<Integer> pullRequestNumbers;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<Integer> issueNumbers;

    private String title;
    private String reasonText;

    private BigDecimal score;
    private boolean lowCardWorth;

    private int fileCount;
    private int reviewCommentCount;
    private Integer spanDays;
    private BigDecimal codeRatio;

    @Enumerated(EnumType.STRING)
    private CandidateStatus status;

    @Column(name = "is_user_modified")
    private boolean userModified;

    private Candidate(Long userRepositoryId, CandidateType sourceType, Integer githubPrNumber, String sourceRef,
                      List<Integer> pullRequestNumbers, List<Integer> issueNumbers, String title, String reasonText,
                      BigDecimal score, boolean lowCardWorth, int fileCount, int reviewCommentCount,
                      Integer spanDays, BigDecimal codeRatio) {
        this.publicId = UUID.randomUUID();
        this.userRepositoryId = userRepositoryId;
        this.sourceType = sourceType;
        this.githubPrNumber = githubPrNumber;
        this.sourceRef = sourceRef;
        this.pullRequestNumbers = pullRequestNumbers;
        this.issueNumbers = issueNumbers;
        this.title = title;
        this.reasonText = reasonText;
        this.score = score;
        this.lowCardWorth = lowCardWorth;
        this.fileCount = fileCount;
        this.reviewCommentCount = reviewCommentCount;
        this.spanDays = spanDays;
        this.codeRatio = codeRatio;
        this.status = CandidateStatus.NEW;
        this.userModified = false;
    }

    /** AI 가 묶은 후보를 사용자가 아직 손대지 않은 NEW 후보로 만든다 */
    public static Candidate grouped(Long userRepositoryId, CandidateType sourceType, Integer githubPrNumber,
                                    String sourceRef, List<Integer> pullRequestNumbers, List<Integer> issueNumbers,
                                    String title, String reasonText, BigDecimal score, boolean lowCardWorth,
                                    int fileCount, int reviewCommentCount, Integer spanDays, BigDecimal codeRatio) {

        return new Candidate(userRepositoryId, sourceType, githubPrNumber, sourceRef, pullRequestNumbers,
                issueNumbers, title, reasonText, score, lowCardWorth, fileCount, reviewCommentCount, spanDays,
                codeRatio);

    }

    /** 제외·복원·커밋 빼기처럼 사용자가 후보에 보낸 요청마다 부르며, 한 번 켜면 끄는 길이 없다 */
    public void markUserModified() {

        this.userModified = true;

    }

    /** NEW 가 아니거나 사용자가 한 번이라도 손댔거나 직접 추가한 후보면 손댄 후보다 */
    public boolean isTouched() {

        return status != CandidateStatus.NEW || userModified || sourceType == CandidateType.MANUAL;

    }
}
