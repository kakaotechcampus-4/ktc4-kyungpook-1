package com.gitory.backend.recommend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.Comparator;
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

    /** 보드가 보여 줄 순서로, AI 가 준 순서대로 저장하므로 점수가 같으면 먼저 저장한 후보가 앞이고 점수가 없는 후보는 맨 뒤다 */
    public static final Comparator<Candidate> BOARD_ORDER = Comparator
            .comparing(Candidate::getScore, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Candidate::getId);

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

    /** 사용자가 직접 만든 후보를 PR·이슈·점수 없이 처음부터 손댄 후보로 만들며, 파일 수·기간·코드 비율은 붙인 커밋으로 계산한 값을 받는다 */
    static Candidate manual(Long userRepositoryId, String sourceRef, String title, String reasonText,
                            CandidateMeta meta) {

        Candidate candidate = new Candidate(userRepositoryId, CandidateType.MANUAL, null, sourceRef, List.of(),
                List.of(), title, reasonText, null, false, meta.fileCount(), 0, meta.spanDays(), meta.codeRatio());
        candidate.markUserModified();

        return candidate;

    }

    /** 제외·복원·커밋 빼기처럼 사용자가 후보에 보낸 요청마다 부르며, 한 번 켜면 끄는 길이 없다 */
    public void markUserModified() {

        this.userModified = true;

    }

    /** 카드로 쓴 후보로 바꾸며, 새 후보가 아니면 이미 쓰였거나 제외된 후보라 바꾸지 않는다 */
    public void use() {

        if (status != CandidateStatus.NEW) {
            throw new IllegalStateException("새 후보만 카드로 쓸 수 있다: " + status);
        }

        this.status = CandidateStatus.USED;

    }

    /** NEW 가 아니거나 사용자가 한 번이라도 손댔거나 직접 추가한 후보면 손댄 후보다 */
    public boolean isTouched() {

        return status != CandidateStatus.NEW || userModified || sourceType == CandidateType.MANUAL;

    }

    /** 카드로 쓴 후보는 카드가 가리키고 있어 어떤 수정도 받지 않는다 */
    public void ensureEditable() {

        if (status == CandidateStatus.USED) {
            throw new CandidateNotEditableException();
        }

    }

    public void exclude() {

        ensureEditable();
        this.status = CandidateStatus.EXCLUDED;

    }

    public void restore() {

        ensureEditable();
        this.status = CandidateStatus.NEW;

    }
}
