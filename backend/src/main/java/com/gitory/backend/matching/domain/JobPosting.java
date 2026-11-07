package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.CardTech;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static lombok.AccessLevel.PROTECTED;

/**
 * 회사가 올린 채용 공고 한 개이며, 공고 본문 글은 담지 않고 해시만 둔다
 * 칸을 추가하려면 마이그레이션에 컬럼부터 추가할 것 (ddl-auto: validate)
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "job_posting")
public class JobPosting {

    // 채용 사이트가 상시 채용을 2999-12-31 같은 먼 날짜로 보내므로, 이보다 먼 마감일은 마감일 없음으로 둔다
    private static final Duration MAX_DEADLINE_AHEAD = Duration.ofDays(365);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID publicId;

    private String companyKey;
    private String externalId;
    private String companyName;
    private String title;
    private String sourceUrl;

    @Enumerated(EnumType.STRING)
    private JobCategory category;

    @Enumerated(EnumType.STRING)
    private ExperienceLevel experienceLevel;

    private Integer experienceMinYears;
    private String experienceQuote;

    // 카드 techs 와 같은 enum 이름이고, Hibernate 가 INSERT 에 이 칸을 넣어 DB 기본값이 쓰이지 않으므로 빈 목록으로 시작한다
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> teamTechs;

    // 비어 있으면 AI 가 버전을 주기 전에 읽은 공고라, AI 가 다음 실행에서 한 번 다시 읽는다
    private Integer extractionVersion;

    @Enumerated(EnumType.STRING)
    private JobPostingStatus status;

    @Enumerated(EnumType.STRING)
    private JobPostingContentStatus contentStatus;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 64)
    private String contentHash;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 64)
    private String indexedContentHash;

    private Instant sourceUpdatedAt;
    private Instant expiresAt;
    private Instant firstSeenAt;
    private Instant lastSeenAt;
    private Instant extractedAt;

    @UpdateTimestamp
    private Instant updatedAt;

    private JobPosting(String companyKey, String externalId, String companyName, String title, String sourceUrl,
                       Instant sourceUpdatedAt, Instant expiresAt, Instant now) {
        this.publicId = UUID.randomUUID();
        this.companyKey = companyKey;
        this.externalId = externalId;
        this.companyName = companyName;
        this.title = title;
        this.sourceUrl = sourceUrl;
        this.sourceUpdatedAt = sourceUpdatedAt;
        this.expiresAt = deadlineOf(expiresAt, now);
        this.experienceLevel = ExperienceLevel.UNSPECIFIED;
        this.teamTechs = List.of();
        this.status = JobPostingStatus.ACTIVE;
        this.contentStatus = JobPostingContentStatus.PENDING;
        this.firstSeenAt = now;
        this.lastSeenAt = now;
    }

    public static JobPosting discovered(String companyKey, String externalId, String companyName, String title,
                                        String sourceUrl, Instant sourceUpdatedAt, Instant expiresAt, Instant now) {

        return new JobPosting(companyKey, externalId, companyName, title, sourceUrl, sourceUpdatedAt, expiresAt, now);

    }

    /** 활성으로 되돌려도 마감이 지난 공고는 실행 끝의 마감 처리가 다시 마감 상태로 바꾼다 */
    public void seenAgain(String companyName, String title, String sourceUrl, Instant sourceUpdatedAt,
                          Instant expiresAt, Instant now) {

        this.companyName = companyName;
        this.title = title;
        this.sourceUrl = sourceUrl;
        this.sourceUpdatedAt = sourceUpdatedAt;
        this.expiresAt = deadlineOf(expiresAt, now);
        this.lastSeenAt = now;
        this.status = JobPostingStatus.ACTIVE;

    }

    private static Instant deadlineOf(Instant expiresAt, Instant now) {

        return expiresAt != null && expiresAt.isAfter(now.plus(MAX_DEADLINE_AHEAD)) ? null : expiresAt;

    }

    /** 조건이 바뀐 결과를 반영하며, 본문 글은 받지 않고 AI 가 준 해시만 남긴다 */
    public void extracted(JobCategory category, ExperienceLevel experienceLevel, Integer experienceMinYears,
                          String experienceQuote, List<CardTech> teamTechs, Integer extractionVersion,
                          JobPostingContentStatus contentStatus, String contentHash, Instant now) {

        this.category = category;
        this.experienceLevel = experienceLevel;
        this.experienceMinYears = experienceMinYears;
        this.experienceQuote = experienceQuote;
        this.teamTechs = teamTechs.stream().map(Enum::name).toList();
        this.extractionVersion = extractionVersion;
        this.contentStatus = contentStatus;
        this.contentHash = contentHash;
        this.extractedAt = now;

    }

    /** 색인에 보낸 내용의 해시이며, content_hash 와 같아야 색인이 최신이다 */
    public void indexed(String contentHash) {

        this.indexedContentHash = contentHash;

    }

    // 활성을 벗어나면 색인에서 빠지므로 해시를 비워야 다시 나타났을 때 벡터 없이 목록에 들어가지 않는다
    public void markRemoved() {

        this.status = JobPostingStatus.REMOVED;
        this.indexedContentHash = null;

    }

    public void expire() {

        this.status = JobPostingStatus.EXPIRED;
        this.indexedContentHash = null;

    }

    /** 저장해 둔 추천이 가리키는 공고가 그 뒤 마감되거나 내려갔는지로, 다시 계산하지 않고 마감으로 보여 줄 때 쓴다 */
    public boolean isClosedAt(Instant now) {

        return status != JobPostingStatus.ACTIVE || (expiresAt != null && !expiresAt.isAfter(now));

    }
}
