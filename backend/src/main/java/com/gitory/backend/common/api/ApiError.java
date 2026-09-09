package com.gitory.backend.common.api;

/**
 * 사용자에게 보여 줄 메시지와 운영용 분류를 분리한다(api-data-assumptions.md 계약 원칙 4).
 *
 * <p>{@code code} 는 프론트가 분기하는 영문 대문자 enum 이름이고, {@code message} 는
 * 사용자에게 그대로 보여도 되는 문장이다. 스택 트레이스·내부 식별자·저장소 이름을 넣지 않는다
 * (security-privacy.md: 진단 로그에 저장소 이름을 남기지 않음).
 */
public record ApiError(String code, String message) {
}
