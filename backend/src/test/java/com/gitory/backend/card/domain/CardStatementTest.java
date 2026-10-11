package com.gitory.backend.card.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardStatementTest {

    @Test
    @DisplayName("사용자가 쓴 문장은 USER_STATED·순번 1·확신도와 되묻기 턴 없이 만들고 앞뒤 공백을 그대로 둔다")
    void userStatedKeepsTextAsWritten() {

        CardStatement statement = CardStatement.userStated(3L, (short) 2, StarField.A, "  인덱스를 추가했다  \n");

        assertThat(statement.getCardId()).isEqualTo(3L);
        assertThat(statement.getVersionNo()).isEqualTo((short) 2);
        assertThat(statement.getStarSlot()).isEqualTo(StarField.A);
        assertThat(statement.getSeq()).isEqualTo((short) 1);
        assertThat(statement.getBody()).isEqualTo("  인덱스를 추가했다  \n");
        assertThat(statement.getEvidenceType()).isEqualTo(EvidenceType.USER_STATED);
        assertThat(statement.getConfidence()).isNull();
        assertThat(statement.getSourceTurnId()).isNull();

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("blankTexts")
    @DisplayName("빈 글이나 공백뿐인 글(NBSP·U+2007·U+202F·BOM 포함)로는 문장을 만들지 않는다")
    void rejectsBlankText(String body) {

        assertThatThrownBy(() -> CardStatement.userStated(3L, (short) 1, StarField.S, body))
                .isInstanceOf(IllegalArgumentException.class);

    }

    @Test
    @DisplayName("칸 글에 U+0000 이 있으면 InvalidCardInputException 이고 문장을 만들지 않는다")
    void rejectsNul() {

        assertThatThrownBy(() -> CardStatement.userStated(3L, (short) 1, StarField.A, "인덱스를\u0000추가했다"))
                .isInstanceOf(InvalidCardInputException.class);

    }

    static Stream<Named<String>> blankTexts() {

        return Stream.of(
                Named.of("null", null),
                Named.of("빈 글", ""),
                Named.of("공백뿐", "   "),
                Named.of("줄바꿈·탭", "\n\t"),
                Named.of("NBSP 뿐", "\u00A0"),
                Named.of("U+2007 뿐", "\u2007"),
                Named.of("U+202F 뿐", "\u202F"),
                Named.of("BOM 뿐", "\uFEFF"));

    }
}
