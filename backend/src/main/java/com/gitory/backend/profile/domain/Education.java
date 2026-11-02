package com.gitory.backend.profile.domain;

import java.math.BigDecimal;

/** 달은 YYYY-MM 글이고, 학점은 만점과 함께 있거나 둘 다 없다. 학교 이름은 처음 입력 창이 받지 않아 null 일 수 있고, 매칭은 학력 구분만 본다 */
public record Education(EducationLevel level, String school, String major, EducationStatus status,
                        String startMonth, String endMonth, BigDecimal gpa, BigDecimal gpaMax) {
}
