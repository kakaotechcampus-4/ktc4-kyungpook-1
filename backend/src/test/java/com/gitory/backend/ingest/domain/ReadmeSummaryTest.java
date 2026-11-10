package com.gitory.backend.ingest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.stream.Stream;

class ReadmeSummaryTest {

    private static final String PROSE = "커밋 기록으로 경험 카드를 만들어 주는 서비스예요.";

    @Test
    @DisplayName("소개 머리글 아래의 첫 글 문단을 문서 맨 앞 문단보다 먼저 고른다")
    void prefersParagraphUnderIntroHeading() {

        String readme = """
                # Gitory

                맨 앞에 있는 문단이지만 소개 머리글 아래 문단이 먼저 뽑혀야 해요.

                ## 소개

                팀 프로젝트의 커밋과 PR 을 읽어 자기소개서에 쓸 경험으로 정리해요. 이 문장은 쓰지 않아요.
                """;

        assertThat(ReadmeSummary.pick(readme)).contains("팀 프로젝트의 커밋과 PR 을 읽어 자기소개서에 쓸 경험으로 정리해요.");

    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"소개", "개요", "프로젝트 소개", "About", "OVERVIEW", "introduction", "1. 프로젝트 소개", "📖 About"})
    @DisplayName("소개 머리글은 대소문자·번호·앞뒤 기호와 상관없이 알아본다")
    void recognizesIntroHeadings(String heading) {

        String readme = "맨 앞에 있는 문단이지만 소개 머리글 아래 문단이 먼저 뽑혀야 해요.\n\n## " + heading + "\n\n" + PROSE;

        assertThat(ReadmeSummary.pick(readme)).contains(PROSE);

    }

    @Test
    @DisplayName("소개 머리글 아래에 글 문단이 없으면 문서 전체에서 첫 글 문단을 고른다")
    void fallsBackToFirstParagraphWhenIntroHeadingHasNoProse() {

        String readme = """
                # Gitory

                %s

                ## About

                - 목록뿐인 소개

                ## Usage

                사용법을 설명하는 문단이지만 앞 문단이 이미 뽑혔어요.
                """.formatted(PROSE);

        assertThat(ReadmeSummary.pick(readme)).contains(PROSE);

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notProse")
    @DisplayName("코드 블록·이미지·배지·HTML 태그·표·목록·머리글은 글 문단으로 세지 않는다")
    void skipsNonProseBlocks(String kind, String block) {

        assertThat(ReadmeSummary.pick(block + "\n\n" + PROSE + "\n")).contains(PROSE);

    }

    @Test
    @DisplayName("태그로 시작하는 줄은 붙어 있어도 줄마다 다른 문단으로 본다")
    void treatsEachHtmlBlockLineAsOwnParagraph() {

        String readme = """
                <p><strong>커밋을 경험으로</strong></p>
                <p>개발 기록에서 내가 한 일을 찾아 경험 카드로 정리해요.</p>
                """;

        assertThat(ReadmeSummary.pick(readme)).contains("개발 기록에서 내가 한 일을 찾아 경험 카드로 정리해요.");

    }

    @Test
    @DisplayName("여러 줄에 걸쳐 쓴 태그의 속성 줄은 글 문단으로 세지 않는다")
    void skipsAttributeLinesOfMultiLineTag() {

        String readme = """
                <img
                  src="https://example.com/images/a-very-long-banner-file-name.png"
                  width="600">

                %s
                """.formatted(PROSE);

        assertThat(ReadmeSummary.pick(readme)).contains(PROSE);

    }

    @Test
    @DisplayName("줄 끝의 역슬래시(줄바꿈 표시)는 글에 남기지 않아 문장 끝을 알아본다")
    void dropsTrailingBackslashLineBreaks() {

        String readme = "서비스의 첫 문장은 여기까지입니다.\\\n둘째 문장은 버려요.";

        assertThat(ReadmeSummary.pick(readme)).contains("서비스의 첫 문장은 여기까지입니다.");

    }

    @Test
    @DisplayName("링크는 기호를 떼고 글만 남긴다")
    void keepsOnlyLinkText() {

        String readme = "[Gitory](https://example.com) 는 커밋을 [경험 카드](https://example.com/cards)로 바꿔 줘요.";

        assertThat(ReadmeSummary.pick(readme)).contains("Gitory 는 커밋을 경험 카드로 바꿔 줘요.");

    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "npm install gitory-command-line-tool",
            "pip install gitory-command-line-tool",
            "yarn add gitory-command-line-tool",
            "docker run --rm -p 8080:80 gitory/server",
            "$ ./gradlew bootRun --args='--spring.profiles.active=local'"})
    @DisplayName("npm·pip·yarn·docker·$ 로 시작하는 명령어 문단은 건너뛴다")
    void skipsCommandParagraphs(String command) {

        assertThat(ReadmeSummary.pick(command + "\n\n" + PROSE)).contains(PROSE);

    }

    @Test
    @DisplayName("설치·실행 절(Available Scripts, Getting Started, 설치, 사용법 등) 아래의 문단은 고르지 않는다")
    void skipsParagraphsUnderUsageHeadings() {

        String cra = """
                This project was bootstrapped with Create React App.

                ## Available Scripts

                In the project directory, you can run:

                ### `npm start`

                Runs the app in the development mode and opens the browser.
                """;
        assertThat(ReadmeSummary.pick(cra)).isEmpty();

        String mixed = """
                # 가계부

                가계부는 카드 내역을 읽어 달마다 지출을 정리해 주는 서비스입니다.

                ## 시작하기

                저장소를 받은 뒤 아래 명령으로 실행합니다 그러면 개발 서버가 뜹니다.
                """;
        assertThat(ReadmeSummary.pick(mixed)).contains("가계부는 카드 내역을 읽어 달마다 지출을 정리해 주는 서비스입니다.");

    }

    @Test
    @DisplayName("bootstrapped with 가 든 문단은 건너뛴다")
    void skipsBootstrappedBoilerplate() {

        String readme = "This project was bootstrapped with Create React App.\n\n" + PROSE;

        assertThat(ReadmeSummary.pick(readme)).contains(PROSE);

    }

    @Test
    @DisplayName("콜론으로 끝나는 문단은 뒤에 이어질 내용을 알리는 말이라 건너뛴다")
    void skipsParagraphsEndingWithColon() {

        String readme = "프로젝트 폴더에서 아래 명령을 실행해 주세요:\n\n" + PROSE;

        assertThat(ReadmeSummary.pick(readme)).contains(PROSE);

    }

    @Test
    @DisplayName("BOM 으로 시작하거나 줄바꿈이 CRLF 인 README 도 같은 결과가 나온다")
    void normalizesBomAndWindowsLineEndings() {

        String readme = "\uFEFF# 스무 자가 넘는 아주 긴 제목 줄도 글 문단이 아니에요\r\n\r\n" + PROSE + "\r\n";

        assertThat(ReadmeSummary.pick(readme)).contains(PROSE);

    }

    @Test
    @DisplayName("20자 미만인 문단은 건너뛴다")
    void skipsShortParagraphs() {

        assertThat(ReadmeSummary.pick("짧은 한 줄이에요.\n\n" + PROSE)).contains(PROSE);

    }

    @Test
    @DisplayName("구분선이나 이모지처럼 글자·숫자가 거의 없는 줄은 건너뛴다")
    void skipsLinesWithFewLetters() {

        String readme = "✨🚀".repeat(12) + "\n\n=============================\n\n" + PROSE;

        assertThat(ReadmeSummary.pick(readme)).contains(PROSE);

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sentences")
    @DisplayName("고른 문단에서 첫 문장까지만 남긴다")
    void keepsOnlyFirstSentence(String ending, String paragraph, String expected) {

        assertThat(ReadmeSummary.pick(paragraph)).contains(expected);

    }

    @Test
    @DisplayName("첫 문장이 120자를 넘으면 120자에서 자르고 말줄임표를 붙이며, 딱 120자면 그대로 둔다")
    void cutsLongSentenceAtHundredTwentyCharacters() {

        String exactly = "가".repeat(120);

        assertThat(ReadmeSummary.pick("가".repeat(130))).contains(exactly + "…");
        assertThat(ReadmeSummary.pick(exactly)).contains(exactly);

    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   \n\n  ", "# 제목뿐", "```\nnpm install\n```", "짧은 글", "- 목록 하나뿐인 문서입니다 목록 하나"})
    @DisplayName("고를 문단이 없으면 비운다")
    void returnsEmptyWhenNothingFits(String readme) {

        assertThat(ReadmeSummary.pick(readme)).isEmpty();

    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"![", "[", "<a", "<!--"})
    @DisplayName("닫히지 않는 기호가 64KB 가득한 입력도 몇 초 안에 끝난다")
    void staysFastOnUnclosedMarkers(String marker) {

        String adversarial = marker.repeat(64 * 1024 / marker.length());

        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> ReadmeSummary.pick(adversarial));

    }

    @Test
    @DisplayName("영어 README 에서도 문장 끝에서 끊는다")
    void picksFromEnglishReadme() {

        String readme = """
                # Gitory

                [![Build](https://img.shields.io/badge/build-passing-green.svg)](https://ci.example.com)

                Gitory turns your GitHub commits into experience cards for job applications. It reads commits and pull requests.
                """;

        assertThat(ReadmeSummary.pick(readme))
                .contains("Gitory turns your GitHub commits into experience cards for job applications.");

    }

    private static Stream<Arguments> notProse() {

        return Stream.of(
                Arguments.of("코드 블록", "```bash\n./gradlew bootRun 으로 서버를 실행하는 방법을 설명하는 긴 줄\n```"),
                Arguments.of("물결 코드 블록", "~~~\n코드 블록 안에 들어 있는 아주 긴 한 줄짜리 설명 문장이에요.\n~~~"),
                Arguments.of("이미지와 배지", "[![Build](https://img.shields.io/badge/build-passing-green.svg)](https://ci.example.com)\n![logo](docs/logo.png)"),
                Arguments.of("HTML 태그", "<p align=\"center\"><img src=\"docs/banner.png\" width=\"600\"></p>"),
                Arguments.of("HTML 주석", "<!-- 이 주석은 문서에 보이지 않지만 길이는 스무 자를 훌쩍 넘어요 -->"),
                Arguments.of("표", "| 이름 | 설명이 제법 길게 적힌 칸입니다 |\n| --- | --- |\n| a | b |"),
                Arguments.of("목록", "- 첫째 기능을 설명하는 긴 목록 항목입니다 입니다\n* 둘째 기능을 설명하는 긴 목록 항목입니다\n1. 순서 있는 목록의 항목도 글 문단이 아니에요"),
                Arguments.of("머리글", "# 스무 자가 넘는 아주 긴 제목 줄도 글 문단이 아니에요"));

    }

    private static Stream<Arguments> sentences() {

        return Stream.of(
                Arguments.of("마침표", "서비스의 첫 문장은 여기까지입니다. 두 번째 문장은 버려요.", "서비스의 첫 문장은 여기까지입니다."),
                Arguments.of("요.", "커밋 기록을 경험 카드로 바꿔 줘요. 두 번째 문장은 버려요.", "커밋 기록을 경험 카드로 바꿔 줘요."),
                Arguments.of("느낌표", "커밋 기록이 경험 카드가 됩니다! 두 번째 문장은 버려요.", "커밋 기록이 경험 카드가 됩니다!"),
                Arguments.of("물음표", "커밋 기록으로 어떤 경험 카드를 만들까요? 두 번째 문장은 버려요.", "커밋 기록으로 어떤 경험 카드를 만들까요?"),
                Arguments.of("점이 붙은 이름", "Node.js 와 v1.2 로 만든 서비스예요. 두 번째 문장은 버려요.", "Node.js 와 v1.2 로 만든 서비스예요."),
                Arguments.of("여러 줄 문단", "커밋 기록을 읽어\n경험 카드로 바꿔 줘요. 두 번째 문장은 버려요.", "커밋 기록을 읽어 경험 카드로 바꿔 줘요."),
                Arguments.of("끝맺음 없음", "끝맺음 기호가 없는 한 줄짜리 소개 문단입니다", "끝맺음 기호가 없는 한 줄짜리 소개 문단입니다"));

    }
}
