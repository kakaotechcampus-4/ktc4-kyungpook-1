package com.gitory.backend.matching.infra;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class MatchingPropertiesTest {

    private final ApplicationContextRunner configFile = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(BindMatchingProperties.class);

    @Test
    @DisplayName("설정 파일의 공고 가져올 회사들은 key 가 서로 겹치지 않는다")
    void companyKeysAreUnique() {

        configFile.run(context -> {
            MatchingProperties properties = context.getBean(MatchingProperties.class);
            assertThat(properties.sources()).isNotEmpty();
            assertThat(properties.sources()).extracting(CompanySource::key).doesNotHaveDuplicates();
        });

    }

    @EnableConfigurationProperties(MatchingProperties.class)
    static class BindMatchingProperties {
    }
}
