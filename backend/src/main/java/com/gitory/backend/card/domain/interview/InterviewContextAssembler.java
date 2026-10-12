package com.gitory.backend.card.domain.interview;

import com.gitory.backend.agent.port.InterviewCardSourceType;
import com.gitory.backend.agent.port.InterviewConfidence;
import com.gitory.backend.agent.port.InterviewMissingSlot;
import com.gitory.backend.agent.port.InterviewQuestionRequest;
import com.gitory.backend.agent.port.InterviewStarSlot;
import com.gitory.backend.card.domain.Card;
import com.gitory.backend.card.domain.CardVersionReader;
import com.gitory.backend.card.domain.EvidenceType;
import com.gitory.backend.card.domain.SlotContent;
import com.gitory.backend.card.domain.StarField;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 카드의 지금 버전에서 묻는 칸 하나를 AI 질문 요청으로 조립한다
 * 후보 없이 직접 작성한 카드만 다루므로 후보와 근거 커밋은 보내지 않는다
 */
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(InterviewLimits.class)
public class InterviewContextAssembler {

    // 한 칸에 문장이 하나뿐이다
    private static final int ONLY_STATEMENT_SEQ = 1;

    private final CardVersionReader reader;
    private final InterviewLimits limits;

    /** 지금 버전의 글을 읽으므로 카드와 같은 읽기 트랜잭션 안에서 불러야 한다 */
    public InterviewQuestionRequest assemble(Card card, StarField slot, int existingTurnCount) {

        SlotContent content = reader.read(card.getId(), card.getCurrentVersion()).slots().get(slot);
        InterviewMissingSlot missingSlot = new InterviewMissingSlot(InterviewStarSlot.valueOf(slot.name()),
                ONLY_STATEMENT_SEQ, bodyOf(content), confidenceOf(content), List.of());

        return new InterviewQuestionRequest(card.getId(), null, List.of(missingSlot), existingTurnCount,
                limits.maxTurns(), InterviewCardSourceType.valueOf(InterviewSourceType.of(card).name()));

    }

    private static String bodyOf(SlotContent content) {

        return content == null ? null : content.body();

    }

    // 사용자가 쓰거나 고른 글은 확신도를 비워 두므로, 이미 답한 칸을 약한 칸으로 되묻지 않게 HIGH 로 보낸다
    private static InterviewConfidence confidenceOf(SlotContent content) {

        if (content == null) {
            return InterviewConfidence.LOW;
        }

        if (content.confidence() != null) {
            return InterviewConfidence.valueOf(content.confidence().name());
        }

        return isWrittenByUser(content.evidenceType()) ? InterviewConfidence.HIGH : InterviewConfidence.LOW;

    }

    private static boolean isWrittenByUser(EvidenceType evidenceType) {

        return evidenceType == EvidenceType.USER_STATED || evidenceType == EvidenceType.USER_SELECTED;

    }
}
