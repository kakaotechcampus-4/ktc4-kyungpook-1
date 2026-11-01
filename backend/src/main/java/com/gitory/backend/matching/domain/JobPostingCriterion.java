package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.CardTech;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
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

    @Enumerated(EnumType.STRING)
    private GeneralConditionType generalType;

    // 카드 techs 와 같은 enum 이름이고, 기술 이름이 없는 조건은 빈 목록이다
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> techs;

    // 학력 조건에만 있고, 예외가 적힌 조건은 수준이 있어도 거르지 않는다
    @Enumerated(EnumType.STRING)
    private EducationLevel educationLevel;

    private boolean educationExempt;

    private JobPostingCriterion(Long jobPostingId, CriterionType type, int seq, String text, String quote,
                                GeneralConditionType generalType, List<CardTech> techs, EducationLevel educationLevel,
                                boolean educationExempt) {
        this.publicId = UUID.randomUUID();
        this.jobPostingId = jobPostingId;
        this.type = type;
        this.seq = seq;
        this.text = text;
        this.quote = quote;
        this.generalType = generalType;
        this.techs = techs.stream().map(Enum::name).toList();
        this.educationLevel = educationLevel;
        this.educationExempt = educationExempt;
    }

    public static JobPostingCriterion extracted(Long jobPostingId, CriterionType type, int seq, String text,
                                                String quote) {

        return extracted(jobPostingId, type, seq, text, quote, null, List.of(), null, false);

    }

    public static JobPostingCriterion extracted(Long jobPostingId, CriterionType type, int seq, String text,
                                                String quote, GeneralConditionType generalType, List<CardTech> techs,
                                                EducationLevel educationLevel, boolean educationExempt) {

        return new JobPostingCriterion(jobPostingId, type, seq, text, quote, generalType, techs, educationLevel,
                educationExempt);

    }
}
