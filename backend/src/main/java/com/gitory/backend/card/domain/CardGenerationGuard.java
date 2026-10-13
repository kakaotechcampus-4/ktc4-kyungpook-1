package com.gitory.backend.card.domain;

import com.gitory.backend.card.port.CardGenerationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 초안 Job 이 대기 중이거나 실행 중인 동안은 카드의 되묻기를 받지 않도록 막는다 */
@Component
@RequiredArgsConstructor
public class CardGenerationGuard {

    private final CardGenerationPort generationJobs;

    public void requireNotGenerating(Card card) {

        UUID jobId = card.getGenerationJobId();
        if (jobId != null && generationJobs.isRunning(jobId)) {
            throw new CardGeneratingException();
        }

    }
}
