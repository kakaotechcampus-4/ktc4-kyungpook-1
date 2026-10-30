package com.gitory.backend.profile.domain;

/** 끝난 달이 없으면 재직 중이다 */
public record Career(String company, String role, EmploymentType type, String startMonth, String endMonth) {
}
