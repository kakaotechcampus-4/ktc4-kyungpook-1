package com.gitory.backend.matching.infra;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PostingImagesTest {

    private static final String PAGE = "https://careers.example.com/jobs/1";
    private static final String IMAGE = "<img src=\"https://cdn.example.com/upload/a.jpg\">";

    @Test
    @DisplayName("본문 글이 200자 이상이면 이미지를 찾지 않는다")
    void skipsImagesWhenTextIsLongEnough() {

        assertThat(PostingImages.of("가".repeat(200), IMAGE, PAGE)).isEmpty();

    }

    @Test
    @DisplayName("본문 글이 200자보다 짧으면 본문의 이미지 주소를 넘긴다")
    void findsImagesWhenTextIsShort() {

        assertThat(PostingImages.of("가".repeat(199), IMAGE, PAGE)).containsExactly("https://cdn.example.com/upload/a.jpg");

    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("가로나 세로가 100px 보다 작다고 적힌 이미지는 아이콘으로 보고 거른다")
    @ValueSource(strings = {
            "<img src=\"https://cdn.example.com/icon.png\" width=\"70\" height=\"59\">",
            "<img src=\"https://cdn.example.com/icon.png\" style=\"max-width: 100%; height: 59.4px; width: 70px;\">",
            "<img src=\"https://cdn.example.com/icon.png\" width=\"850\" height=\"40\">"
    })
    void dropsImagesDeclaredSmall(String html) {

        assertThat(PostingImages.of("", html, PAGE)).isEmpty();

    }

    @Test
    @DisplayName("크기가 적히지 않았거나 100px 이상인 이미지는 넘긴다")
    void keepsImagesWithoutSizeOrLargeEnough() {

        String html = "<img src=\"https://cdn.example.com/a.png\" style=\"max-width: 100%; height: auto;\">"
                + "<img src=\"https://cdn.example.com/b.jpg\" style=\"max-width: 100%; height: 584.6px; width: 850px;\">"
                + "<img src=\"https://cdn.example.com/c.jpg\" width=\"500\" height=\"480\">";

        assertThat(PostingImages.of("", html, PAGE)).containsExactly("https://cdn.example.com/a.png",
                "https://cdn.example.com/b.jpg", "https://cdn.example.com/c.jpg");

    }

    @Test
    @DisplayName("공고 하나에 이미지는 앞에서부터 6장까지만 넘긴다")
    void limitsImagesPerPosting() {

        String html = IntStream.rangeClosed(1, 8)
                .mapToObj(i -> "<img src=\"https://cdn.example.com/" + i + ".jpg\">")
                .collect(Collectors.joining());

        assertThat(PostingImages.of("", html, PAGE)).hasSize(6).startsWith("https://cdn.example.com/1.jpg")
                .endsWith("https://cdn.example.com/6.jpg");

    }

    @Test
    @DisplayName("상대 주소는 공고 페이지 주소로 풀고, 웹 주소가 아니거나 이미 넘긴 주소는 빼고 넘긴다")
    void resolvesRelativeAndDropsNonWebOrRepeatedImages() {

        String html = "<img src=\"/upload/a.jpg\"><img src=\"data:image/png;base64,AAAA\"><img src=\"/upload/a.jpg\"><img>";

        assertThat(PostingImages.of("", html, PAGE)).containsExactly("https://careers.example.com/upload/a.jpg");

    }
}
