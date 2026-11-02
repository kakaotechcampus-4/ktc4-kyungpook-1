package com.gitory.backend.profile.api;

import com.gitory.backend.profile.domain.ProfileInput;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "인적사항 네 목록을 통째로 보냅니다. 네 목록 모두 꼭 보내고, 없으면 빈 목록([])입니다.")
public record ProfileRequest(
        @Schema(description = "학력, 5개까지") List<EducationRequest> educations,
        @Schema(description = "경력, 10개까지") List<CareerRequest> careers,
        @Schema(description = "어학, 10개까지") List<LanguageRequest> languages,
        @Schema(description = "자격증, 20개까지") List<CertificationRequest> certifications,
        @Schema(description = "관심 분야 — 공고 직무 분류 이름(BACKEND · FRONTEND · MOBILE · DEVOPS · DATA · AI_ML · SECURITY · QA · ETC) 3개까지, 겹치지 않게. 안 보내면 저장된 값을 그대로 둡니다", nullable = true)
        List<String> interests) {

    @Schema(name = "ProfileEducationRequest")
    public record EducationRequest(
            @Schema(description = "HIGH_SCHOOL · ASSOCIATE · BACHELOR · MASTER · DOCTOR") String level,
            @Schema(description = "학교 이름, 50자까지. 비우면 null", nullable = true) String school,
            @Schema(description = "전공, 50자까지. 없으면 null") String major,
            @Schema(description = "ENROLLED · ON_LEAVE · GRADUATION_EXPECTED · GRADUATED · DROPPED_OUT") String status,
            @Schema(description = "입학 달 YYYY-MM, 없으면 null", example = "2021-03") String startMonth,
            @Schema(description = "졸업(예정) 달 YYYY-MM, 없으면 null", example = "2027-02") String endMonth,
            @Schema(description = "학점. 만점과 함께 보내거나 둘 다 null", example = "3.8") BigDecimal gpa,
            @Schema(description = "학점 만점 4.0 · 4.3 · 4.5 · 100", example = "4.5") BigDecimal gpaMax) {
    }

    @Schema(name = "ProfileCareerRequest")
    public record CareerRequest(
            @Schema(description = "회사 이름, 50자까지") String company,
            @Schema(description = "직무, 50자까지. 없으면 null") String role,
            @Schema(description = "FULL_TIME · CONTRACT · INTERN · PART_TIME · FREELANCE") String type,
            @Schema(description = "입사 달 YYYY-MM", example = "2024-07") String startMonth,
            @Schema(description = "퇴사 달 YYYY-MM, 재직 중이면 null", example = "2024-12") String endMonth) {
    }

    @Schema(name = "ProfileLanguageRequest")
    public record LanguageRequest(
            @Schema(description = "TOEIC · TOEIC_SPEAKING · OPIC · TOEFL · IELTS · TEPS · JLPT · JPT · HSK · OTHER") String test,
            @Schema(description = "OTHER 일 때 시험 이름 50자까지. 그 밖에는 보내도 저장하지 않음") String testName,
            @Schema(description = "점수·등급 20자까지", example = "IH") String score,
            @Schema(description = "취득 달 YYYY-MM, 없으면 null") String acquiredMonth) {
    }

    @Schema(name = "ProfileCertificationRequest")
    public record CertificationRequest(
            @Schema(description = "자격증 이름, 50자까지", example = "정보처리기사") String name,
            @Schema(description = "발급 기관, 50자까지. 없으면 null") String issuer,
            @Schema(description = "취득 달 YYYY-MM, 없으면 null") String acquiredMonth) {
    }

    ProfileInput toInput() {

        return new ProfileInput(
                educations == null ? null : educations.stream().map(e -> e == null ? null
                        : new ProfileInput.EducationInput(e.level(), e.school(), e.major(), e.status(), e.startMonth(),
                                e.endMonth(), e.gpa(), e.gpaMax())).toList(),
                careers == null ? null : careers.stream().map(c -> c == null ? null
                        : new ProfileInput.CareerInput(c.company(), c.role(), c.type(), c.startMonth(), c.endMonth()))
                        .toList(),
                languages == null ? null : languages.stream().map(l -> l == null ? null
                        : new ProfileInput.LanguageInput(l.test(), l.testName(), l.score(), l.acquiredMonth())).toList(),
                certifications == null ? null : certifications.stream().map(c -> c == null ? null
                        : new ProfileInput.CertificationInput(c.name(), c.issuer(), c.acquiredMonth())).toList(),
                interests);

    }
}
