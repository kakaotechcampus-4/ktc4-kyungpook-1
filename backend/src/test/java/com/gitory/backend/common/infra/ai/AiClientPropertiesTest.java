package com.gitory.backend.common.infra.ai;

import com.gitory.backend.support.AiTestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiClientPropertiesTest {

    private final ApplicationContextRunner configFile = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(BindAiProperties.class);

    @Test
    @DisplayName("설정이 없으면 묶기는 600초, 되묻기는 20초를 기다린다")
    void defaultsGroupsAndInterviewTimeouts() {

        AiClientProperties properties = AiTestProperties.defaults().build();

        assertThat(properties.groupsReadTimeout()).isEqualTo(Duration.ofSeconds(600));
        assertThat(properties.interviewReadTimeout()).isEqualTo(Duration.ofSeconds(20));

    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    @DisplayName("묶기 대기 시간이 0 이하이면 설정을 거절한다")
    void rejectsNonPositiveGroupsTimeout(long seconds) {

        assertThatThrownBy(() -> AiTestProperties.defaults().groupsReadTimeout(Duration.ofSeconds(seconds)).build())
                .isInstanceOf(IllegalArgumentException.class);

    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    @DisplayName("되묻기 대기 시간이 0 이하이면 설정을 거절한다")
    void rejectsNonPositiveInterviewTimeout(long seconds) {

        assertThatThrownBy(() -> AiTestProperties.defaults().interviewReadTimeout(Duration.ofSeconds(seconds)).build())
                .isInstanceOf(IllegalArgumentException.class);

    }

    @Test
    @DisplayName("설정 파일도 묶기는 600초, 되묻기는 20초를 기본으로 둔다")
    void configFileKeepsTheSameDefaults() {

        configFile.run(context -> {
            AiClientProperties properties = context.getBean(AiClientProperties.class);
            assertThat(properties.groupsReadTimeout()).isEqualTo(Duration.ofSeconds(600));
            assertThat(properties.interviewReadTimeout()).isEqualTo(Duration.ofSeconds(20));
        });

    }

    @Test
    @DisplayName("묶기 대기 시간은 AI_GROUPS_READ_TIMEOUT 환경변수로 바꾼다")
    void groupsTimeoutFollowsEnvironmentVariable() {

        configFile.withPropertyValues("AI_GROUPS_READ_TIMEOUT=7s")
                .run(context -> assertThat(context.getBean(AiClientProperties.class).groupsReadTimeout())
                        .isEqualTo(Duration.ofSeconds(7)));

    }

    @EnableConfigurationProperties(AiClientProperties.class)
    static class BindAiProperties {
    }
}
