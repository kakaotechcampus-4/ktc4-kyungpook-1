package com.gitory.backend.matching.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

import static lombok.AccessLevel.PROTECTED;

/**
 * AI 가 공고에서 뽑은 조건 한 줄이며, 원문 인용을 함께 남긴다
 * 공고가 지워지면 같이 지워진다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "job_posting_criterion")
public class JobPostingCriterion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID publicId;

    private Long jobPostingId;

    @Enumerated(EnumType.STRING)
    private CriterionType type;

    private int seq;

    private String text;
    private String quote;

    private JobPostingCriterion(Long jobPostingId, CriterionType type, int seq, String text, String quote) {
        this.publicId = UUID.randomUUID();
        this.jobPostingId = jobPostingId;
        this.type = type;
        this.seq = seq;
        this.text = text;
        this.quote = quote;
    }

    public static JobPostingCriterion extracted(Long jobPostingId, CriterionType type, int seq, String text,
                                                String quote) {

        return new JobPostingCriterion(jobPostingId, type, seq, text, quote);

    }
}
