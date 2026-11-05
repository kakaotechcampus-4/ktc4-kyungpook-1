package com.gitory.backend.job.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gitory.alert")
public record JobAlertProperties(String webhookUrl) {

    public JobAlertProperties {
        webhookUrl = webhookUrl == null ? "" : webhookUrl.strip();
    }
}
