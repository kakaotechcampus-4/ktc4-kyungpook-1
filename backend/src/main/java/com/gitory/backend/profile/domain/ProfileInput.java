package com.gitory.backend.profile.domain;

import java.util.List;

/** 화면이 보낸 그대로의 값으로, 관심 분야는 글이라 {@link ProfileRules} 가 검사한 뒤에 저장할 값이 된다 */
public record ProfileInput(List<String> interests, String email, Boolean mailOptIn) {
}
