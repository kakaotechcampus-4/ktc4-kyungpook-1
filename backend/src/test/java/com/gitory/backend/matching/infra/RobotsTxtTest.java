package com.gitory.backend.matching.infra;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RobotsTxtTest {

    private static String load(String host) throws IOException {

        try (InputStream in = RobotsTxtTest.class.getResourceAsStream("/matching/robots/" + host + ".txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

    }

    @Test
    @DisplayName("Greenhouse 는 공고 목록 API 를 허용하고 /embed/ 만 막는다")
    void greenhouseBlocksOnlyEmbed() throws IOException {

        RobotsTxt robots = RobotsTxt.parse(load("boards-api.greenhouse.io"));

        assertThat(robots.isAllowed("/v1/boards/acme/jobs?content=true")).isTrue();
        assertThat(robots.isAllowed("/embed/job_app?token=1")).isFalse();

    }

    @ParameterizedTest
    @ValueSource(strings = {"careers.upstage.ai", "careers.kakaopay.com", "career.hyundai-autoever.com"})
    @DisplayName("그리팅 채용 사이트는 공고 목록과 상세는 허용하고 지원서·/m/·/a/ 경로는 막는다")
    void greetingSitesBlockApplyAndPrivatePaths(String host) throws IOException {

        RobotsTxt robots = RobotsTxt.parse(load(host));

        assertThat(robots.isAllowed("/ko")).isTrue();
        assertThat(robots.isAllowed("/ko/o/12345")).isTrue();
        assertThat(robots.isAllowed("/ko/o/12345/apply")).isFalse();
        assertThat(robots.isAllowed("/en/o/12345/apply?ref=x")).isFalse();
        assertThat(robots.isAllowed("/m/abc")).isFalse();
        assertThat(robots.isAllowed("/ko/a/abc")).isFalse();

    }

    @Test
    @DisplayName("일치하는 규칙 중 가장 긴 것이 이긴다")
    void longestMatchWins() {

        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow: /jobs
                Allow: /jobs/open
                """);

        assertThat(robots.isAllowed("/jobs/closed")).isFalse();
        assertThat(robots.isAllowed("/jobs/open/1")).isTrue();

    }

    @Test
    @DisplayName("길이가 같으면 Allow 가 이긴다")
    void allowWinsTie() {

        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow: /page
                Allow: /page
                """);

        assertThat(robots.isAllowed("/page")).isTrue();

    }

    @Test
    @DisplayName("별표 없이 $ 만 있으면 정확히 같은 경로만 막는다")
    void dollarWithoutStarMatchesExactPathOnly() {

        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow: /exact$
                """);

        assertThat(robots.isAllowed("/exact")).isFalse();
        assertThat(robots.isAllowed("/exact/1")).isTrue();

    }

    @Test
    @DisplayName("끝의 $ 는 경로가 거기서 끝날 때만 일치한다")
    void dollarAnchorsTheEnd() {

        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow: /*.pdf$
                """);

        assertThat(robots.isAllowed("/files/a.pdf")).isFalse();
        assertThat(robots.isAllowed("/files/a.pdf?download=1")).isTrue();
        assertThat(robots.isAllowed("/files/a.pdfx")).isTrue();

    }

    @Test
    @DisplayName("경로 가운데의 * 는 아무 글자나 일치한다")
    void starMatchesAnyText() {

        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow: /o/*/apply
                """);

        assertThat(robots.isAllowed("/o/123/apply")).isFalse();
        assertThat(robots.isAllowed("/o/a/b/apply")).isFalse();
        assertThat(robots.isAllowed("/o/123/view")).isTrue();

    }

    @Test
    @DisplayName("우리 이름의 묶음이 있으면 * 묶음은 쓰지 않고, 없으면 * 묶음을 쓴다")
    void ourGroupBeatsWildcardGroup() {

        String text = """
                User-agent: *
                Disallow: /

                User-agent: GITORY
                Allow: /
                Disallow: /private
                """;

        assertThat(RobotsTxt.parse(text).isAllowed("/jobs")).isTrue();
        assertThat(RobotsTxt.parse(text).isAllowed("/private/1")).isFalse();
        assertThat(RobotsTxt.parse("User-agent: *\nDisallow: /\n").isAllowed("/jobs")).isFalse();

    }

    @Test
    @DisplayName("연달아 적힌 User-agent 줄은 한 묶음이다")
    void consecutiveUserAgentsShareOneGroup() {

        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: Googlebot
                User-agent: Gitory
                Disallow: /secret

                User-agent: *
                Disallow: /
                """);

        assertThat(robots.isAllowed("/secret/a")).isFalse();
        assertThat(robots.isAllowed("/jobs")).isTrue();

    }

    @Test
    @DisplayName("Crawl-delay 는 적용되는 묶음에서만 읽는다")
    void readsCrawlDelayOfApplicableGroup() {

        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: Gitory
                Crawl-delay: 2.5

                User-agent: *
                Crawl-delay: 30
                """);

        assertThat(robots.crawlDelay()).isEqualTo(Duration.ofMillis(2500));

    }

    @Test
    @DisplayName("BOM 으로 시작하고 줄 끝에 주석이 붙은 파일도 규칙을 읽는다")
    void readsRulesDespiteBomAndTrailingComments() {

        RobotsTxt robots = RobotsTxt.parse("\uFEFFUser-agent: * # 모두\nDisallow: /private # 옛 경로\n");

        assertThat(robots.isAllowed("/private/1")).isFalse();
        assertThat(robots.isAllowed("/jobs")).isTrue();

    }

    @Test
    @DisplayName("우리 이름의 묶음이 여러 개면 규칙을 합친다")
    void mergesMultipleGroupsOfOurName() {

        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: Gitory
                Disallow: /a

                User-agent: *
                Disallow: /

                User-agent: Gitory
                Disallow: /b
                """);

        assertThat(robots.isAllowed("/a/1")).isFalse();
        assertThat(robots.isAllowed("/b/1")).isFalse();
        assertThat(robots.isAllowed("/c")).isTrue();

    }

    @Test
    @DisplayName("빈 Disallow 는 모두 허용이다")
    void emptyDisallowAllowsEverything() {

        RobotsTxt robots = RobotsTxt.parse("User-agent: *\nDisallow:\n");

        assertThat(robots.isAllowed("/anything")).isTrue();

    }

    @Test
    @DisplayName("전부 막혀 있어도 robots.txt 자체는 허용한다")
    void robotsFileItselfIsAlwaysAllowed() {

        RobotsTxt robots = RobotsTxt.parse("User-agent: *\nDisallow: /\n");

        assertThat(robots.isAllowed("/robots.txt")).isTrue();

    }
}
