package com.gitory.backend.usage.domain;

import lombok.Getter;

@Getter
public class UsageEventRejectedException extends RuntimeException {

    private final UsageEventRejection reason;
    private final String loggedName;

    public UsageEventRejectedException(UsageEventRejection reason) {
        this(reason, null);
    }

    public UsageEventRejectedException(UsageEventRejection reason, String loggedName) {
        super(reason.name());
        this.reason = reason;
        this.loggedName = loggedName;
    }
}
