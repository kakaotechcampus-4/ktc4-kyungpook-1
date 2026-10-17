package com.gitory.backend.agent.port;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class AgentUnavailableExceptionTest {

    @ParameterizedTest
    @CsvSource({"true, false", "false, true", "false, false", "true, true"})
    @DisplayName("모델 호출 실패는 메시지·원인과 함께 다시 시도해도 되는지와 응답이 이상했는지를 서로 따로 담는다")
    void keepsRetryableAndInvalidResponse(boolean retryable, boolean invalidResponse) {

        IllegalStateException cause = new IllegalStateException("AI 호출 실패");

        AgentUnavailableException failure = new AgentUnavailableException("묶기 실패", retryable, invalidResponse, cause);

        assertThat(failure.isRetryable()).isEqualTo(retryable);
        assertThat(failure.isInvalidResponse()).isEqualTo(invalidResponse);
        assertThat(failure.getMessage()).isEqualTo("묶기 실패");
        assertThat(failure.getCause()).isSameAs(cause);

    }
}
