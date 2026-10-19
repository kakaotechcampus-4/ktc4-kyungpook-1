package com.gitory.backend.usage.domain;

public enum UsageEventRejection {
    UNKNOWN_NAME,
    AT_INVALID,
    PATH_INVALID,
    PROPS_VALUE_TYPE,
    PROPS_NUL,
    PROPS_TOO_LARGE,
    BODY_UNREADABLE,
    UNSUPPORTED_MEDIA_TYPE
}
