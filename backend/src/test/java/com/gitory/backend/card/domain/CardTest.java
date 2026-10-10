package com.gitory.backend.card.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

class CardTest {

    private static final Long USER_ID = 7L;
    private static final Long REPO_LINK_ID = 11L;
    private static final String TITLE = "카드 제목";
    private static final String PERIOD = "2024.04";
    private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-09T01:02:03.123456789Z");
    private static final Instant NOW_IN_MICROS = Instant.parse("2026-10-09T01:02:03.123456Z");

    @Test
    @DisplayName("직접 작성 카드는 정성·직접 작성·작성 중·현재 버전 1·가리기 규칙 없음으로 시작하고 제목은 앞뒤 공백까지 그대로 둔다")
    void startsManualDraft() {

        Card card = Card.startManual(USER_ID, REPO_LINK_ID, "  첫 카드  ", PERIOD, "key-1");

        assertThat(card.getCardType()).isEqualTo(CardKind.QUALITATIVE);
        assertThat(card.getOrigin()).isEqualTo(CardOrigin.MANUAL);
        assertThat(card.getStatus()).isEqualTo(CardStatus.DRAFT);
        assertThat(card.getCurrentVersion()).isEqualTo((short) 1);
        assertThat(card.getMaskRules()).isEmpty();
        assertThat(card.getTitle()).isEqualTo("  첫 카드  ");
        assertThat(card.getPeriod()).isEqualTo(PERIOD);
        assertThat(card.getUserId()).isEqualTo(USER_ID);
        assertThat(card.getUserRepositoryId()).isEqualTo(REPO_LINK_ID);
        assertThat(card.getIdempotencyKey()).isEqualTo("key-1");
        assertThat(card.getPublicId()).isNotNull();
        assertThat(card.getConfirmedAt()).isNull();

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTitles")
    @DisplayName("제목이 null·빈 글·공백뿐(NBSP·U+2007·U+202F·BOM 포함)이거나 U+0000 이 있거나 256자를 넘으면 카드 시작과 제목 바꾸기 모두 InvalidCardInputException 이고 카드는 그대로다")
    void rejectsInvalidTitle(String title) {

        assertThatThrownBy(() -> Card.startManual(USER_ID, null, title, null, null))
                .isInstanceOf(InvalidCardInputException.class);

        Card card = draft();
        age(card);
        assertThatThrownBy(() -> card.rename(title)).isInstanceOf(InvalidCardInputException.class);
        assertThat(card.getTitle()).isEqualTo(TITLE);
        assertThat(card.getUpdatedAt()).isEqualTo(OLD);

    }

    @Test
    @DisplayName("정확히 256자인 제목은 카드 시작과 제목 바꾸기 모두 받는다")
    void acceptsTitleOf256() {

        String title = "가".repeat(256);

        assertThat(Card.startManual(USER_ID, null, title, null, null).getTitle()).isEqualTo(title);

        Card card = draft();
        card.rename(title);
        assertThat(card.getTitle()).isEqualTo(title);

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("blankPeriods")
    @DisplayName("기간이 null·빈 글·공백뿐(NBSP·U+2007·U+202F·BOM 포함)이면 카드 시작과 기간 바꾸기 모두 기간을 비운다")
    void blankPeriodBecomesNull(String period) {

        assertThat(Card.startManual(USER_ID, null, TITLE, period, null).getPeriod()).isNull();

        Card card = draft();
        card.changePeriod(period);
        assertThat(card.getPeriod()).isNull();

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPeriods")
    @DisplayName("기간이 50자를 넘거나 U+0000 이 있으면 카드 시작과 기간 바꾸기 모두 InvalidCardInputException 이고 카드는 그대로다")
    void rejectsInvalidPeriod(String period) {

        assertThatThrownBy(() -> Card.startManual(USER_ID, null, TITLE, period, null))
                .isInstanceOf(InvalidCardInputException.class);

        Card card = draft();
        age(card);
        assertThatThrownBy(() -> card.changePeriod(period)).isInstanceOf(InvalidCardInputException.class);
        assertThat(card.getPeriod()).isEqualTo(PERIOD);
        assertThat(card.getUpdatedAt()).isEqualTo(OLD);

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keptPeriods")
    @DisplayName("정확히 50자인 기간과 앞뒤에 공백이 있는 기간은 카드 시작과 기간 바꾸기 모두 받은 그대로 둔다")
    void keepsPeriodAsGiven(String period) {

        assertThat(Card.startManual(USER_ID, null, TITLE, period, null).getPeriod()).isEqualTo(period);

        Card card = draft();
        card.changePeriod(period);
        assertThat(card.getPeriod()).isEqualTo(period);

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("changes")
    @DisplayName("확정된 카드에 제목·기간·가리기 규칙 바꾸기, 편집 표시, 새 버전 전환을 하면 CardConfirmedException 이고 아무것도 바뀌지 않는다")
    void confirmedCardRejectsChanges(Consumer<Card> change) {

        Card card = confirmed();
        age(card);

        assertThatThrownBy(() -> change.accept(card)).isInstanceOf(CardConfirmedException.class);

        assertThat(card.getStatus()).isEqualTo(CardStatus.CONFIRMED);
        assertThat(card.getTitle()).isEqualTo(TITLE);
        assertThat(card.getPeriod()).isEqualTo(PERIOD);
        assertThat(card.getMaskRules()).isEmpty();
        assertThat(card.getCurrentVersion()).isEqualTo((short) 1);
        assertThat(card.getUpdatedAt()).isEqualTo(OLD);

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMaskRules")
    @DisplayName("가리기 규칙 목록이 null·21개 이상이거나, 규칙이 null·from 이나 to 가 null·from 이 공백뿐(NBSP·U+2007·U+202F·BOM 포함)·from 이나 to 가 100자를 넘거나 U+0000 이 있으면 InvalidCardInputException 이고 카드는 그대로다")
    void rejectsInvalidMaskRules(List<MaskRule> rules) {

        Card card = draft();
        age(card);

        assertThatThrownBy(() -> card.replaceMaskRules(rules)).isInstanceOf(InvalidCardInputException.class);
        assertThat(card.getMaskRules()).isEmpty();
        assertThat(card.getUpdatedAt()).isEqualTo(OLD);

    }

    @Test
    @DisplayName("가리기 규칙은 to 빈 글과 20개·100자 경계를 받고, from·to 의 공백과 받은 순서를 그대로 저장한다")
    void keepsMaskRulesAsGiven() {

        Card card = draft();
        List<MaskRule> rules = new ArrayList<>(List.of(
                new MaskRule(" 홍길동 ", " 팀원 A "),
                new MaskRule("카카오", ""),
                new MaskRule("가".repeat(100), "나".repeat(100))));
        IntStream.rangeClosed(4, 20).forEach(i -> rules.add(new MaskRule("가릴 말 " + i, "")));

        boolean changed = card.replaceMaskRules(rules);

        assertThat(changed).isTrue();
        assertThat(card.getMaskRules()).containsExactlyElementsOf(rules);

    }

    @Test
    @DisplayName("저장된 규칙과 순서까지 같은 목록이면 false 를 돌려주고 updatedAt 을 바꾸지 않는다")
    void sameMaskRulesAreNotSavedAgain() {

        Card card = draft();
        card.replaceMaskRules(List.of(new MaskRule("홍길동", "A"), new MaskRule("카카오", "B")));
        age(card);

        boolean changed = card.replaceMaskRules(List.of(new MaskRule("홍길동", "A"), new MaskRule("카카오", "B")));

        assertThat(changed).isFalse();
        assertThat(card.getUpdatedAt()).isEqualTo(OLD);

    }

    @Test
    @DisplayName("순서만 다른 목록은 다른 규칙으로 보고 true 를 돌려주며 받은 순서로 저장한다")
    void reorderedMaskRulesAreSaved() {

        Card card = draft();
        card.replaceMaskRules(List.of(new MaskRule("홍길동", "A"), new MaskRule("카카오", "B")));

        boolean changed = card.replaceMaskRules(List.of(new MaskRule("카카오", "B"), new MaskRule("홍길동", "A")));

        assertThat(changed).isTrue();
        assertThat(card.getMaskRules()).containsExactly(new MaskRule("카카오", "B"), new MaskRule("홍길동", "A"));

    }

    @Test
    @DisplayName("확정하면 CONFIRMED 와 확정 시각이 들어가고, 이미 확정된 카드를 또 확정하면 CardConfirmedException 이다")
    void confirmsOnlyOnce() {

        Card card = draft();

        card.confirm();

        assertThat(card.getStatus()).isEqualTo(CardStatus.CONFIRMED);
        assertThat(card.isConfirmed()).isTrue();
        assertThat(card.getConfirmedAt()).isNotNull();

        Instant confirmedAt = card.getConfirmedAt();
        assertThatThrownBy(card::confirm).isInstanceOf(CardConfirmedException.class);
        assertThat(card.getConfirmedAt()).isEqualTo(confirmedAt);

    }

    @Test
    @DisplayName("작성 중인 카드를 다시 열면 아무것도 바뀌지 않고 updatedAt 도 그대로다")
    void reopeningDraftChangesNothing() {

        Card card = draft();
        age(card);

        card.reopen();

        assertThat(card.getStatus()).isEqualTo(CardStatus.DRAFT);
        assertThat(card.getConfirmedAt()).isNull();
        assertThat(card.getUpdatedAt()).isEqualTo(OLD);

    }

    @Test
    @DisplayName("확정된 카드를 다시 열면 작성 중으로 돌아가고 확정 시각이 지워진다")
    void reopeningConfirmedCardReturnsToDraft() {

        Card card = confirmed();

        card.reopen();

        assertThat(card.getStatus()).isEqualTo(CardStatus.DRAFT);
        assertThat(card.isConfirmed()).isFalse();
        assertThat(card.getConfirmedAt()).isNull();

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contentChanges")
    @DisplayName("내용을 바꾸는 동작은 모두 카드의 updatedAt 을 지금 시각으로 갱신한다")
    void contentChangesRefreshUpdatedAt(Consumer<Card> change, boolean startConfirmed) {

        Card card = startConfirmed ? confirmed() : draft();
        age(card);

        change.accept(card);

        assertThat(card.getUpdatedAt()).isAfter(OLD);

    }

    @Test
    @DisplayName("카드가 남기는 만든·고친·확정 시각은 지금 시각에서 µs 아래를 잘라 낸 값이다")
    void recordsTimesInMicros() {

        // 실제 시계는 µs 아래가 0 인 값을 줄 때가 있어 잘라 냈는지 가릴 수 없으므로, 지금 시각을 ns 자리까지 있는 값으로 정해 둔다
        try (MockedStatic<Instant> clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(NOW);

            Card card = draft();
            assertThat(card.getCreatedAt()).isEqualTo(NOW_IN_MICROS);
            assertThat(card.getUpdatedAt()).isEqualTo(NOW_IN_MICROS);

            age(card);
            card.confirm();
            assertThat(card.getConfirmedAt()).isEqualTo(NOW_IN_MICROS);
            assertThat(card.getUpdatedAt()).isEqualTo(NOW_IN_MICROS);
        }

    }

    static Stream<Named<String>> invalidTitles() {

        return Stream.of(
                text("null", null),
                text("빈 글", ""),
                text("공백뿐", "  \t "),
                text("NBSP 뿐", "\u00A0"),
                text("U+2007 뿐", "\u2007"),
                text("U+202F 뿐", "\u202F"),
                text("BOM 뿐", "\uFEFF"),
                text("맨 앞에 U+0000", "\u0000제목"),
                text("257자", "가".repeat(257)));

    }

    static Stream<Named<String>> blankPeriods() {

        return Stream.of(
                text("null", null),
                text("빈 글", ""),
                text("공백뿐", "   "),
                text("탭·줄바꿈", "\t\n"),
                text("NBSP 뿐", "\u00A0"),
                text("U+2007 뿐", "\u2007"),
                text("U+202F 뿐", "\u202F"),
                text("BOM 뿐", "\uFEFF"));

    }

    static Stream<Named<String>> invalidPeriods() {

        return Stream.of(
                text("51자", "가".repeat(51)),
                text("가운데 U+0000", "2024\u0000.04"));

    }

    static Stream<Named<String>> keptPeriods() {

        return Stream.of(
                text("50자", "가".repeat(50)),
                text("앞뒤 공백", "  2024년 상반기 "));

    }

    static Stream<Named<Consumer<Card>>> changes() {

        return Stream.of(
                change("제목 바꾸기", card -> card.rename("새 제목")),
                change("기간 바꾸기", card -> card.changePeriod("2025.01")),
                change("가리기 규칙 바꾸기", card -> card.replaceMaskRules(List.of(new MaskRule("팀원", "A")))),
                change("편집 표시", Card::markEdited),
                change("새 버전 전환", card -> card.switchTo((short) 2)));

    }

    static Stream<Named<List<MaskRule>>> invalidMaskRules() {

        List<MaskRule> withNullRule = new ArrayList<>();
        withNullRule.add(new MaskRule("홍길동", "A"));
        withNullRule.add(null);

        return Stream.of(
                rules("목록 null", null),
                rules("규칙 21개", IntStream.rangeClosed(1, 21).mapToObj(i -> new MaskRule("가릴 말 " + i, "")).toList()),
                rules("규칙 null", withNullRule),
                rules("from null", List.of(new MaskRule(null, "A"))),
                rules("to null", List.of(new MaskRule("홍길동", null))),
                rules("from 빈 글", List.of(new MaskRule("", "A"))),
                rules("from 공백뿐", List.of(new MaskRule("   ", "A"))),
                rules("from NBSP 뿐", List.of(new MaskRule("\u00A0", "A"))),
                rules("from U+2007 뿐", List.of(new MaskRule("\u2007", "A"))),
                rules("from U+202F 뿐", List.of(new MaskRule("\u202F", "A"))),
                rules("from BOM 뿐", List.of(new MaskRule("\uFEFF", "A"))),
                rules("from 끝에 U+0000", List.of(new MaskRule("홍길동\u0000", "A"))),
                rules("두 번째 규칙 to 에 U+0000", List.of(new MaskRule("홍길동", "A"), new MaskRule("카카오", "\u0000"))),
                rules("from 101자", List.of(new MaskRule("가".repeat(101), "A"))),
                rules("to 101자", List.of(new MaskRule("홍길동", "가".repeat(101)))));

    }

    static Stream<Arguments> contentChanges() {

        return Stream.of(
                arguments(change("제목 바꾸기", card -> card.rename("새 제목")), false),
                arguments(change("기간 바꾸기", card -> card.changePeriod("2025.01")), false),
                arguments(change("가리기 규칙 바꾸기", card -> card.replaceMaskRules(List.of(new MaskRule("팀원", "A")))), false),
                arguments(change("확정", Card::confirm), false),
                arguments(change("확정 카드 다시 열기", Card::reopen), true),
                arguments(change("편집 표시", Card::markEdited), false),
                arguments(change("새 버전 전환", card -> card.switchTo((short) 2)), false));

    }

    private static Card draft() {

        return Card.startManual(USER_ID, null, TITLE, PERIOD, null);

    }

    private static Card confirmed() {

        Card card = draft();
        card.confirm();

        return card;

    }

    // Instant.now() 를 두 번 불러도 같은 값이 나올 수 있어, 갱신 여부는 과거로 돌려 둔 시각과 비교한다
    private static void age(Card card) {

        ReflectionTestUtils.setField(card, "updatedAt", OLD);

    }

    private static Named<String> text(String name, String value) {

        return Named.of(name, value);

    }

    private static Named<Consumer<Card>> change(String name, Consumer<Card> change) {

        return Named.of(name, change);

    }

    private static Named<List<MaskRule>> rules(String name, List<MaskRule> rules) {

        return Named.of(name, rules);

    }
}
