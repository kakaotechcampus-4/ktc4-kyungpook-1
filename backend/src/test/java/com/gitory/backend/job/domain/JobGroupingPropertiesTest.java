package com.gitory.backend.job.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class JobGroupingPropertiesTest {

    private final ApplicationContextRunner configFile = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(BindGroupingProperties.class);

    @Test
    @DisplayName("설정 파일은 경험 그룹화를 꺼 둔다")
    void groupingIsOffByDefault() {

        configFile.run(context -> assertThat(context.getBean(JobGroupingProperties.class).enabled()).isFalse());

    }

    @Test
    @DisplayName("경험 그룹화는 ANALYSIS_GROUPING_ENABLED 환경변수로 켠다")
    void groupingFollowsEnvironmentVariable() {

        configFile.withPropertyValues("ANALYSIS_GROUPING_ENABLED=true")
                .run(context -> assertThat(context.getBean(JobGroupingProperties.class).enabled()).isTrue());

    }

    @EnableConfigurationProperties(JobGroupingProperties.class)
    static class BindGroupingProperties {
    }
}
