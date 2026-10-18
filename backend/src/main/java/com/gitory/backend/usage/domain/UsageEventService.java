package com.gitory.backend.usage.domain;

import com.gitory.backend.usage.infra.UsageEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class UsageEventService {

    // 시작값이라 써 보며 조정한다
    private static final int MAX_PROPS_BYTES = 2048;
    private static final int MAX_PATH_LENGTH = 255;

    // 화면의 toISOString() 이 내는 네 자리 연도 범위라서, DB 의 시각 범위를 넘는 값이 500 이 되지 않는다
    private static final Instant MIN_AT = Instant.EPOCH;
    private static final Instant MAX_AT = Instant.parse("9999-12-31T23:59:59.999Z");

    private static final Pattern LOGGABLE_NAME = Pattern.compile("[a-z0-9_]{1,40}");

    private final UsageEventRepository events;
    private final ObjectMapper objectMapper;

    public void record(Long userId, String name, Map<String, Object> props, Instant clientAt, String path) {

        // 이름은 소문자·숫자·밑줄 모양일 때만 로그에 남기려고 거절 예외에 실어 보낸다
        if (!UsageEventNames.isAllowed(name)) {
            throw new UsageEventRejectedException(UsageEventRejection.UNKNOWN_NAME, loggable(name));
        }
        if (clientAt == null || clientAt.isBefore(MIN_AT) || clientAt.isAfter(MAX_AT)) {
            throw new UsageEventRejectedException(UsageEventRejection.AT_INVALID);
        }
        if (path == null || path.length() > MAX_PATH_LENGTH || containsNul(path)) {
            throw new UsageEventRejectedException(UsageEventRejection.PATH_INVALID);
        }

        Map<String, Object> safeProps = props == null ? Map.of() : props;
        requireStorableValues(safeProps);
        if (objectMapper.writeValueAsString(safeProps).getBytes(StandardCharsets.UTF_8).length > MAX_PROPS_BYTES) {
            throw new UsageEventRejectedException(UsageEventRejection.PROPS_TOO_LARGE);
        }

        events.save(UsageEvent.of(userId, name, safeProps, path, clientAt));

    }

    // PostgreSQL 이 NUL 문자를 저장하지 못해 500 이 되므로 저장 전에 거절한다
    private static void requireStorableValues(Map<String, Object> props) {

        for (Map.Entry<String, Object> entry : props.entrySet()) {
            Object value = entry.getValue();
            boolean scalar = value == null || value instanceof String || value instanceof Number || value instanceof Boolean;
            if (!scalar) {
                throw new UsageEventRejectedException(UsageEventRejection.PROPS_VALUE_TYPE);
            }
            if (containsNul(entry.getKey()) || (value instanceof String text && containsNul(text))) {
                throw new UsageEventRejectedException(UsageEventRejection.PROPS_NUL);
            }
        }

    }

    private static boolean containsNul(String text) {

        return text.indexOf('\0') >= 0;

    }

    private static String loggable(String name) {

        return name != null && LOGGABLE_NAME.matcher(name).matches() ? name : "(이름 모양이 아님)";

    }
}
