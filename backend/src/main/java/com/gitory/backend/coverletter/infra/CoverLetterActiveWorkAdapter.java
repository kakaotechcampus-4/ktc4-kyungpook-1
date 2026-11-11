package com.gitory.backend.coverletter.infra;

import com.gitory.backend.consent.port.ActiveWorkPort;
import com.gitory.backend.coverletter.domain.CoverLetterStage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** CoverLetter.running() 이 AI 단계로 보는 세 단계와 같아야 한다 */
@Component
@RequiredArgsConstructor
public class CoverLetterActiveWorkAdapter implements ActiveWorkPort {

    private static final List<CoverLetterStage> AI_STAGES = List.of(
            CoverLetterStage.ASSIGNING, CoverLetterStage.ASKING, CoverLetterStage.DRAFTING);

    private final CoverLetterRepository letters;

    @Override
    public boolean isRunning(Long userId) {

        return letters.existsByUserIdAndStageIn(userId, AI_STAGES);

    }
}
