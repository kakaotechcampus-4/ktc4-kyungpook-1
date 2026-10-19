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
    @DisplayName("설정이 없으면 경험 그룹화는 600초, 되묻기는 20초를 기다린다")
    void defaultsGroupsAndInterviewTimeouts() {

        AiClientProperties properties = AiTestProperties.defaults().build();

        assertThat(properties.groupsReadTimeout()).isEqualTo(Duration.ofSeconds(600));
        assertThat(properties.interviewReadTimeout()).isEqualTo(Duration.ofSeconds(20));

    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    @DisplayName("경험 그룹화 대기 시간이 0 이하이면 설정을 거절한다")
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
    @DisplayName("설정 파일도 경험 그룹화는 600초, 되묻기는 20초를 기본으로 둔다")
    void configFileKeepsTheSameDefaults() {

        configFile.run(context -> {
            AiClientProperties properties = context.getBean(AiClientProperties.class);
            assertThat(properties.groupsReadTimeout()).isEqualTo(Duration.ofSeconds(600));
            assertThat(properties.interviewReadTimeout()).isEqualTo(Duration.ofSeconds(20));
        });

    }

    @Test
    @DisplayName("경험 그룹화 대기 시간은 AI_GROUPS_READ_TIMEOUT 환경변수로 바꾼다")
    void groupsTimeoutFollowsEnvironmentVariable() {

        configFile.withPropertyValues("AI_GROUPS_READ_TIMEOUT=7s")
                .run(context -> assertThat(context.getBean(AiClientProperties.class).groupsReadTimeout())
                        .isEqualTo(Duration.ofSeconds(7)));

    }

    @Test
    @DisplayName("설정이 없으면 diff 근거는 300초, STAR 초안은 180초를 기다린다 — 설정 파일도 같다")
    void defaultsCardDraftTimeouts() {

        AiClientProperties properties = AiTestProperties.defaults().build();

        assertThat(properties.diffEvidenceReadTimeout()).isEqualTo(Duration.ofSeconds(300));
        assertThat(properties.starReadTimeout()).isEqualTo(Duration.ofSeconds(180));
        configFile.run(context -> {
            AiClientProperties bound = context.getBean(AiClientProperties.class);
            assertThat(bound.diffEvidenceReadTimeout()).isEqualTo(Duration.ofSeconds(300));
            assertThat(bound.starReadTimeout()).isEqualTo(Duration.ofSeconds(180));
        });

    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    @DisplayName("diff 근거·STAR 초안 대기 시간이 0 이하이면 설정을 거절한다")
    void rejectsNonPositiveCardDraftTimeouts(long seconds) {

        Duration invalid = Duration.ofSeconds(seconds);

        assertThatThrownBy(() -> AiTestProperties.defaults().diffEvidenceReadTimeout(invalid).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiTestProperties.defaults().starReadTimeout(invalid).build())
                .isInstanceOf(IllegalArgumentException.class);

    }

    @Test
    @DisplayName("diff 근거·STAR 초안 대기 시간은 AI_DIFF_EVIDENCE_READ_TIMEOUT·AI_STAR_READ_TIMEOUT 환경변수로 바꾼다")
    void cardDraftTimeoutsFollowEnvironmentVariables() {

        configFile.withPropertyValues("AI_DIFF_EVIDENCE_READ_TIMEOUT=7s", "AI_STAR_READ_TIMEOUT=8s")
                .run(context -> {
                    AiClientProperties bound = context.getBean(AiClientProperties.class);
                    assertThat(bound.diffEvidenceReadTimeout()).isEqualTo(Duration.ofSeconds(7));
                    assertThat(bound.starReadTimeout()).isEqualTo(Duration.ofSeconds(8));
                });

    }

    @EnableConfigurationProperties(AiClientProperties.class)
    static class BindAiProperties {
    }
}
