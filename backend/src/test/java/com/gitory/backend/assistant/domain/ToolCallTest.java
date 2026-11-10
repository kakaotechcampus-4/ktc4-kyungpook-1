package com.gitory.backend.assistant.domain;

import com.gitory.backend.agent.port.AssistantStepReply;
import com.gitory.backend.agent.port.AssistantTool;
import com.gitory.backend.card.domain.StarField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallTest {

    private static final UUID CARD = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    private static final UUID LETTER = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");

    private final AssistantRun run = AssistantRun.start(1L, "카드 빈 칸 채워 줘", 6, List.of(),
            List.of(new CardLabel("c1", CARD, "결제 서버 개선", List.of(StarField.R))),
            List.of(new LetterLabel("l1", LETTER, "백엔드 개발자", "토스", 3)));

    @Test
    @DisplayName("카드 이름표는 이 실행의 카드로, 자소서 이름표는 이 실행의 자소서로 풀리고 모르는 이름표는 풀리지 않는다")
    void resolvesCardAndLetterRefsOfTheRun() {

        ToolCall ask = ToolCall.of(run, reply(AssistantTool.ASK_CARD_GAP, "c1", null));
        ToolCall unknownCard = ToolCall.of(run, reply(AssistantTool.ASK_CARD_GAP, "c9", null));
        ToolCall polish = ToolCall.of(run, reply(AssistantTool.POLISH_COVER_LETTER, null, "l1"));
        ToolCall unknownLetter = ToolCall.of(run, reply(AssistantTool.POLISH_COVER_LETTER, null, "l9"));
        ToolCall withoutRef = ToolCall.of(run, reply(AssistantTool.ASK_CARD_GAP, null, null));

        assertThat(ask.card().cardId()).isEqualTo(CARD);
        assertThat(ask.resolvable()).isTrue();
        assertThat(unknownCard.resolvable()).isFalse();
        assertThat(polish.letter().letterId()).isEqualTo(LETTER);
        assertThat(polish.resolvable()).isTrue();
        assertThat(unknownLetter.resolvable()).isFalse();
        assertThat(withoutRef.resolvable()).isFalse();

    }

    @Test
    @DisplayName("이름표를 두기 전에 만든 실행은 카드와 자소서 이름표를 풀지 못한다")
    void runWithoutLabelsResolvesNoCardOrLetter() {

        AssistantRun old = AssistantRun.start(1L, "카드 빈 칸 채워 줘", 6, List.of(), List.of(), List.of());
        ReflectionTestUtils.setField(old, "cards", null);
        ReflectionTestUtils.setField(old, "letters", null);

        assertThat(ToolCall.of(old, reply(AssistantTool.ASK_CARD_GAP, "c1", null)).resolvable()).isFalse();
        assertThat(ToolCall.of(old, reply(AssistantTool.POLISH_COVER_LETTER, null, "l1")).resolvable()).isFalse();

    }

    @Test
    @DisplayName("채우기는 이 실행에서 맞춰 본 공고 이름표가 풀려야 실행할 수 있다")
    void fillNeedsResolvedPosting() {

        RunItem.Posting posting = new RunItem.Posting("p1", UUID.randomUUID(), "토스", "백엔드", 2, null, null);

        assertThat(fill(posting).resolvable()).isTrue();
        assertThat(fill(null).resolvable()).isFalse();

    }

    private static ToolCall fill(RunItem.Posting posting) {

        return new ToolCall(UUID.randomUUID(), AssistantTool.FILL_POSTING_GAPS, null, "p1", posting, null, null, null,
                List.of(), null, null, null, null);

    }

    private static AssistantStepReply reply(AssistantTool tool, String cardRef, String letterRef) {

        return AssistantStepReply.call(tool, null, null, null, cardRef, letterRef, null, List.of());

    }
}
