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

    @Test
    @DisplayName("회사 설정의 이름이 비었거나 로고가 https 주소가 아니면 서버가 뜨지 않는다")
    void rejectsBlankNameOrNonHttpsLogo() {

        String[] company = {"gitory.matching.sources[0].key=acme", "gitory.matching.sources[0].type=GREENHOUSE",
                "gitory.matching.sources[0].board=acme"};

        configFile.withPropertyValues(company)
                .withPropertyValues("gitory.matching.sources[0].name=에이크미",
                        "gitory.matching.sources[0].logo=https://cdn.example.com/acme.png")
                .run(context -> assertThat(context).hasNotFailed());
        configFile.withPropertyValues(company)
                .withPropertyValues("gitory.matching.sources[0].name=에이크미",
                        "gitory.matching.sources[0].logo=http://cdn.example.com/acme.png")
                .run(context -> assertThat(context).hasFailed());
        configFile.withPropertyValues(company)
                .withPropertyValues("gitory.matching.sources[0].name= ")
                .run(context -> assertThat(context).hasFailed());

    }

    @EnableConfigurationProperties(MatchingProperties.class)
    static class BindMatchingProperties {
    }
}
