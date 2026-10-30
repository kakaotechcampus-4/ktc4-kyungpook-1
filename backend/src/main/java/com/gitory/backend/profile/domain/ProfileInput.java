package com.gitory.backend.profile.domain;

import java.math.BigDecimal;
import java.util.List;

/** 화면이 보낸 그대로의 값으로, 종류·달은 글이라 {@link ProfileRules} 가 검사한 뒤에 저장할 값이 된다 */
public record ProfileInput(List<EducationInput> educations, List<CareerInput> careers,
                           List<LanguageInput> languages, List<CertificationInput> certifications) {

    public record EducationInput(String level, String school, String major, String status, String startMonth,
                                 String endMonth, BigDecimal gpa, BigDecimal gpaMax) {
    }

    public record CareerInput(String company, String role, String type, String startMonth, String endMonth) {
    }

    public record LanguageInput(String test, String testName, String score, String acquiredMonth) {
    }

    public record CertificationInput(String name, String issuer, String acquiredMonth) {
    }
}
