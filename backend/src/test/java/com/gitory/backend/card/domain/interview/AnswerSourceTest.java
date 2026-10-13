package com.gitory.backend.card.domain.interview;

import com.gitory.backend.card.domain.EvidenceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerSourceTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"TYPED, USER_STATED", "SELECTED, USER_SELECTED"})
    @DisplayName("저장된 답 출처는 화면이 쓰는 이름으로 바뀐다 — 직접 쓴 답은 USER_STATED, 보기에서 고른 답은 USER_SELECTED")
    void mapsToTheNameTheScreenUses(AnswerSource source, EvidenceType evidenceType) {

        assertThat(source.evidenceType()).isEqualTo(evidenceType);

    }
}
