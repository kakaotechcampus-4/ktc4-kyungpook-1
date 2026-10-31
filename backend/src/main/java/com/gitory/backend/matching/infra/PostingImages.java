package com.gitory.backend.matching.infra;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 본문 글이 거의 없는 공고는 본문이 이미지라, 이미지 글 읽기에 넘길 본문 이미지 주소를 찾는다
 * 크기가 작게 적힌 이미지는 아이콘·장식으로 보고 거르며, 공고 하나에 넘기는 장수를 제한한다
 */
final class PostingImages {

    // 실제 공고로 정한 값: KT 이미지 공고는 글 0자, 글이 있는 공고는 가장 짧은 것이 1,168자였다
    static final int SHORT_TEXT_LENGTH = 200;
    // KT 공고 중 이미지가 가장 많은 것이 6장이었다. 이미지 글 읽기 AI 와 맞춰 바꾼다
    static final int MAX_IMAGES = 6;
    private static final double MIN_SIDE_PX = 100;
    private static final Pattern STYLE_SIDE = Pattern.compile("(?<![-\\w])(width|height)\\s*:\\s*([0-9.]+)px");

    private PostingImages() {
    }

    static List<String> of(String text, String html, String pageUrl) {

        if (text.codePointCount(0, text.length()) >= SHORT_TEXT_LENGTH || html == null || html.isBlank()) {
            return List.of();
        }
        Set<String> urls = new LinkedHashSet<>();
        for (Element image : Jsoup.parseBodyFragment(html, pageUrl).select("img[src]")) {
            String url = image.absUrl("src");
            String scheme = url.toLowerCase(Locale.ROOT);
            if ((scheme.startsWith("https://") || scheme.startsWith("http://")) && !declaredSmall(image)) {
                urls.add(url);
            }
            if (urls.size() == MAX_IMAGES) {
                break;
            }
        }
        return new ArrayList<>(urls);

    }

    private static boolean declaredSmall(Element image) {

        return isSmall(attributeSide(image, "width")) || isSmall(attributeSide(image, "height"))
                || isSmall(styleSide(image, "width")) || isSmall(styleSide(image, "height"));

    }

    private static boolean isSmall(OptionalDouble side) {

        return side.isPresent() && side.getAsDouble() < MIN_SIDE_PX;

    }

    private static OptionalDouble attributeSide(Element image, String name) {

        return number(image.attr(name).strip());

    }

    private static OptionalDouble styleSide(Element image, String name) {

        Matcher side = STYLE_SIDE.matcher(image.attr("style").toLowerCase(Locale.ROOT));
        while (side.find()) {
            if (side.group(1).equals(name)) {
                return number(side.group(2));
            }
        }
        return OptionalDouble.empty();

    }

    private static OptionalDouble number(String value) {

        if (!value.matches("[0-9]+(\\.[0-9]+)?")) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(Double.parseDouble(value));

    }
}
