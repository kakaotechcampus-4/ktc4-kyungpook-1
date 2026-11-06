package com.gitory.backend.mail.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gitory.mail")
public record MailProperties(boolean enabled, String appBaseUrl) {
}
