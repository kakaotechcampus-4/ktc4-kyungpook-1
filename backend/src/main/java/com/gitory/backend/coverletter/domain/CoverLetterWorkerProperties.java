package com.gitory.backend.coverletter.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gitory.cover-letter.worker")
public record CoverLetterWorkerProperties(int concurrency) {

    public CoverLetterWorkerProperties {

        if (concurrency < 1) {
            throw new IllegalArgumentException("gitory.cover-letter.worker.concurrency 는 1 이상이어야 한다");
        }

    }
}
