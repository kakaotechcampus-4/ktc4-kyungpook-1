package com.gitory.backend.matching.infra;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 공고 본문 HTML 을 AI 가 읽을 글로 바꾼다
 * Greenhouse 는 HTML 을 엔티티로 한 번 더 감싸 주므로 먼저 풀어야 태그로 읽히고, 제목·문단·목록 경계는 줄바꿈으로 남긴다
 */
public final class PostingTextConverter {

    private static final Pattern SPACES = Pattern.compile("[ \\t]+");

    private PostingTextConverter() {
    }

    public static String fromHtml(String html) {

        if (html == null || html.isBlank()) {
            return "";
        }
        Document document = Jsoup.parseBodyFragment(Parser.unescapeEntities(html, false));
        StringBuilder text = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {

                if (node instanceof TextNode textNode) {
                    text.append(textNode.text());
                } else if (node instanceof Element element && (element.isBlock() || element.nameIs("br"))) {
                    text.append('\n');
                }

            }

            @Override
            public void tail(Node node, int depth) {

                if (node instanceof Element element && element.isBlock()) {
                    text.append('\n');
                }

            }
        }, document.body());
        return Arrays.stream(text.toString().split("\n"))
                .map(line -> SPACES.matcher(line).replaceAll(" ").strip())
                .filter(line -> !line.isEmpty())
                .collect(Collectors.joining("\n"));

    }
}
