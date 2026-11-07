package com.gitory.backend.assistant.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantRunTest {

    @Test
    @DisplayName("끝났거나 실패한 실행은 다시 바뀌지 않는다")
    void finishedOrFailedRunNeverChangesAgain() {

        AssistantRun done = AssistantRun.start(1L, "공고 찾아 줘", 6, List.of());
        done.finish("찾았어요.");
        AssistantRun failed = AssistantRun.start(1L, "공고 찾아 줘", 6, List.of());
        failed.fail(AssistantRunError.AI_UNAVAILABLE);

        for (AssistantRun run : List.of(done, failed)) {
            assertThatThrownBy(() -> run.addUserMessage("답")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> run.ask("물음", "FREE", List.of())).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> run.finish("끝")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> run.fail(AssistantRunError.INTERNAL_ERROR))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(run::claim).isInstanceOf(IllegalStateException.class);
        }
        assertThat(done.getItems()).hasSize(2);
        assertThat(failed.getItems()).hasSize(1);
        assertThat(failed.getErrorCode()).isEqualTo("AI_UNAVAILABLE");

    }
}
