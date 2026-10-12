package com.gitory.backend.recommend.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

class CandidateTextTest {

    @ParameterizedTest
    @MethodSource("blankTexts")
    @DisplayName("빈 글이거나 공백, NBSP, U+2007, U+202F, U+FEFF 만 든 글은 빈 글로 보고 거절한다")
    void treatsInvisibleSpacesAsBlank(String text) {

        assertThat(CandidateText.isBlank(text)).isTrue();
        assertThatThrownBy(() -> CandidateText.require(text, "text", 300))
                .isInstanceOf(InvalidCandidateInputException.class);

    }

    static Stream<Named<String>> blankTexts() {

        return Stream.of(
                Named.of("빈 글", ""),
                Named.of("공백뿐", "   "),
                Named.of("NBSP 뿐", "\u00A0"),
                Named.of("U+2007 뿐", "\u2007"),
                Named.of("U+202F 뿐", "\u202F"),
                Named.of("BOM 뿐", "\uFEFF"),
                Named.of("줄바꿈·탭", "\n\t"),
                Named.of("공백과 NBSP·BOM 이 섞인 글", " \u00A0\uFEFF "));

    }

    @Test
    @DisplayName("글이 없으면 거절한다")
    void rejectsNull() {

        assertThatThrownBy(() -> CandidateText.require(null, "text", 300))
                .isInstanceOf(InvalidCandidateInputException.class);

    }

    @Test
    @DisplayName("보이는 글자가 하나라도 있으면 빈 글이 아니며 앞뒤 공백이 있어도 거절하지 않는다")
    void acceptsTextWithSurroundingSpaces() {

        assertThat(CandidateText.isBlank(" 답 ")).isFalse();
        assertThatCode(() -> CandidateText.require(" 답 ", "text", 300)).doesNotThrowAnyException();

    }

    @Test
    @DisplayName("U+0000 이 어디에든 있으면 거절한다")
    void rejectsNul() {

        assertThatThrownBy(() -> CandidateText.require("가나\u0000다", "path", 300))
                .isInstanceOf(InvalidCandidateInputException.class)
                .hasMessageContaining("U+0000");

    }

    @Test
    @DisplayName("글자 수가 최대와 같으면 받고 하나 넘으면 거절한다")
    void limitsLengthInclusively() {

        assertThatCode(() -> CandidateText.require("a".repeat(300), "text", 300)).doesNotThrowAnyException();
        assertThatThrownBy(() -> CandidateText.require("a".repeat(301), "text", 300))
                .isInstanceOf(InvalidCandidateInputException.class)
                .hasMessageContaining("300");

    }

    @Test
    @DisplayName("글자 수는 UTF-16 기준이라 이모지 하나가 두 글자로 센다")
    void countsUtf16Units() {

        assertThatCode(() -> CandidateText.require("😀".repeat(150), "text", 300)).doesNotThrowAnyException();
        assertThatThrownBy(() -> CandidateText.require("😀".repeat(151), "text", 300))
                .isInstanceOf(InvalidCandidateInputException.class);

    }

    @Test
    @DisplayName("거절 메시지에 어느 칸이 문제인지 이름이 들어 있다")
    void namesTheField() {

        assertThatThrownBy(() -> CandidateText.require("", "path", 197))
                .hasMessageContaining("path");

    }
}
