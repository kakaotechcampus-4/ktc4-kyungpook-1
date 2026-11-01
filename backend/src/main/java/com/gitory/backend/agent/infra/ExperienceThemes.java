package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.ExperienceTheme;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** AI 가 고른 문항 유형은 카드를 고르는 표시라, 모르는 값·겹친 값은 버리고 앞에서부터 두 개까지 받는다 */
final class ExperienceThemes {

    private static final int MAX_THEMES = 2;
    private static final Map<String, ExperienceTheme> BY_NAME = Arrays.stream(ExperienceTheme.values())
            .collect(Collectors.toMap(Enum::name, Function.identity()));

    private ExperienceThemes() {
    }

    // 없거나 배열이 아니면 빈 목록이다
    static List<ExperienceTheme> read(JsonNode values) {

        if (!values.isArray()) {
            return List.of();
        }
        List<ExperienceTheme> themes = new ArrayList<>();
        for (JsonNode value : values) {
            ExperienceTheme theme = value.isString() ? BY_NAME.get(value.asString()) : null;
            if (theme != null && !themes.contains(theme) && themes.size() < MAX_THEMES) {
                themes.add(theme);
            }
        }

        return List.copyOf(themes);

    }
}
