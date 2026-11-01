package com.gitory.backend.card.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

/** 기대값은 화면의 applyMask(StarBlock.tsx)에 같은 입력을 넣어 얻은 결과다 */
class MaskRuleTest {

    @ParameterizedTest
    @MethodSource("sameAsScreen")
    @DisplayName("가리기 결과가 화면의 applyMask 와 같다")
    void appliesRulesLikeTheScreen(String text, List<MaskRule> rules, String expected) {

        assertThat(MaskRule.apply(text, rules)).isEqualTo(expected);

    }

    static Stream<Arguments> sameAsScreen() {

        return Stream.of(
                argumentSet("같은 from 은 앞 규칙만 쓴다", "banana",
                        List.of(new MaskRule("a", "X"), new MaskRule("a", "Y")), "bXnXnX"),
                argumentSet("긴 from 을 먼저 찾는다", "abcab",
                        List.of(new MaskRule("ab", "1"), new MaskRule("abc", "2")), "21"),
                argumentSet("원문 기준 한 번에 바꿔 한 규칙의 결과를 다른 규칙이 다시 가리지 않는다", "ab",
                        List.of(new MaskRule("a", "b"), new MaskRule("b", "c")), "bc"),
                argumentSet("바꿀 말의 $ 와 \\ 는 글자 그대로 둔다", "가나다 나",
                        List.of(new MaskRule("나", "$&$1\\")), "가$&$1\\다 $&$1\\"),
                argumentSet("찾을 말의 정규식 특수문자는 글자 그대로 찾는다", "a.b a+b (x) [y] axb",
                        List.of(new MaskRule("a.b", "<1>"), new MaskRule("a+b", "<2>"), new MaskRule("(x)", "<3>"),
                                new MaskRule("[y]", "<4>")), "<1> <2> <3> <4> axb"),
                argumentSet("빈 from 은 건너뛰고 빈 to 는 지운다", "가나",
                        List.of(new MaskRule("", "Z"), new MaskRule("나", "")), "가"),
                argumentSet("대소문자를 구분한다", "Gitory gitory",
                        List.of(new MaskRule("gitory", "G")), "Gitory G"));

    }
}
