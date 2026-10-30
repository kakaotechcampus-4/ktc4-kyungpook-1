package com.gitory.backend.profile.domain;

import java.math.BigDecimal;

/** 달은 YYYY-MM 글이고, 학점은 만점과 함께 있거나 둘 다 없다 */
public record Education(EducationLevel level, String school, String major, EducationStatus status,
                        String startMonth, String endMonth, BigDecimal gpa, BigDecimal gpaMax) {
}
