package com.gitory.backend.matching.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

import static lombok.AccessLevel.PROTECTED;

/**
 * 회사마다 공고를 마지막으로 가져오려 한 시각과 성공한 시각을 남긴다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "job_posting_source")
public class JobPostingSource {

    @Id
    private String companyKey;

    private Instant lastAttemptAt;
    private Instant lastSuccessAt;
    private String lastErrorCode;

    private JobPostingSource(String companyKey, Instant lastAttemptAt) {
        this.companyKey = companyKey;
        this.lastAttemptAt = lastAttemptAt;
    }

    public static JobPostingSource attempted(String companyKey, Instant attemptedAt) {

        return new JobPostingSource(companyKey, attemptedAt);

    }
}
