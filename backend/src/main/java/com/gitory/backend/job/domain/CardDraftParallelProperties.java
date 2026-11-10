package com.gitory.backend.job.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("gitory.job")
public record CardDraftParallelProperties(@DefaultValue("2") int draftParallel) {

    public CardDraftParallelProperties {

        if (draftParallel < 1) {
            throw new IllegalArgumentException("gitory.job.draft-parallel 은 1 이상이어야 한다");
        }

    }
}
