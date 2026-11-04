package com.gitory.backend.ingest.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * README 원문에서 저장소를 소개하는 문장 하나를 고른다
 * 같은 README 에서는 늘 같은 문장이 나와야 해서 AI 를 쓰지 않고 문단의 위치와 모양만 본다
 */
public final class ReadmeSummary {

    private static final int MIN_PARAGRAPH_LENGTH = 20;
    private static final int MAX_LENGTH = 120;
    private static final String ELLIPSIS = "…";
    private static final Set<String> INTRO_HEADINGS =
            Set.of("소개", "개요", "프로젝트 소개", "about", "overview", "introduction");
    // 설치·실행 절의 글은 "Runs the app in the development mode." 처럼 무엇을 하는 프로젝트인지 말하지 않는다
    private static final Set<String> USAGE_HEADINGS = Set.of("available scripts", "scripts", "getting started",
            "installation", "install", "usage", "learn more", "설치", "실행", "사용법", "시작하기", "설치 방법", "실행 방법");

    // 닫히지 않는 [ 나 ![ 가 가득한 긴 줄이 아래 정규식들을 제곱 시간으로 느리게 하므로 앞쪽만 본다
    private static final int MAX_LINE_LENGTH = 1_000;

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]");
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*]\\([^)]*\\)|!\\[[^\\]]*]\\[[^\\]]*]");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)](?:\\([^)]*\\)|\\[[^\\]]*])");
    private static final Pattern HTML_TAG = Pattern.compile("</?[A-Za-z][^>]*>");
    private static final Pattern UNFINISHED_TAG = Pattern.compile("^<[A-Za-z][^>]*$");
    private static final Pattern MARKER = Pattern.compile("\\*\\*|__|~~|`|(?<![\\p{L}\\p{N}])_|_(?![\\p{L}\\p{N}])|\\*");
    private static final Pattern LIST_ITEM = Pattern.compile("^(?:[-*+]|\\d+[.)])\\s");
    private static final Pattern REFERENCE_DEFINITION = Pattern.compile("^\\[[^\\]]+]:\\s*\\S.*$");
    private static final Pattern SETEXT_UNDERLINE = Pattern.compile("^(?:=+|-{2,})$");
    private static final Pattern COMMAND = Pattern.compile("^(?:\\$|(?:npm|pip|yarn|docker)\\b)");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?。！？]+[\"'”’)\\]」』]*(?=\\s|$)");

    private ReadmeSummary() {
    }

    /** 소개 머리글 아래의 첫 글 문단을 먼저 보고, 없으면 문서 전체의 첫 글 문단에서 첫 문장을 돌려준다 */
    public static Optional<String> pick(String markdown) {

        if (markdown == null) {
            return Optional.empty();
        }

        Paragraph firstProse = null;
        for (Paragraph paragraph : paragraphsOf(markdown)) {
            if (paragraph.underUsageHeading() || !isProse(paragraph.text())) {
                continue;
            }
            if (paragraph.underIntroHeading()) {
                return Optional.of(firstSentence(paragraph.text()));
            }
            if (firstProse == null) {
                firstProse = paragraph;
            }
        }

        return Optional.ofNullable(firstProse).map(paragraph -> firstSentence(paragraph.text()));

    }

    private static List<Paragraph> paragraphsOf(String markdown) {

        String normalized = markdown.replace("\uFEFF", "").replaceAll("\r\n?", "\n");
        normalized = CONTROL.matcher(normalized).replaceAll("");
        normalized = withoutComments(normalized);

        Reader reader = new Reader();
        for (String line : normalized.split("\n", -1)) {
            String stripped = line.strip();
            reader.read(stripped.length() > MAX_LINE_LENGTH ? stripped.substring(0, MAX_LINE_LENGTH) : stripped);
        }
        reader.flush();

        return reader.paragraphs;

    }

    private static String withoutComments(String text) {

        StringBuilder kept = new StringBuilder(text.length());
        int from = 0;
        for (int start = text.indexOf("<!--"); start >= 0; start = text.indexOf("<!--", from)) {
            int end = text.indexOf("-->", start + 4);
            if (end < 0) {
                break;
            }
            kept.append(text, from, start);
            from = end + 3;
        }

        return kept.append(text, from, text.length()).toString();

    }

    // 코드·표·목록·머리글과 여러 줄에 걸친 태그의 속성 줄은 글 문단이 아니라서 문단을 끊는 줄로만 다룬다
    private static final class Reader {

        private final List<Paragraph> paragraphs = new ArrayList<>();
        private final List<String> lines = new ArrayList<>();
        private boolean underIntroHeading;
        private boolean underUsageHeading;
        private int usageLevel;
        private String fence;
        private boolean insideTag;

        void read(String line) {

            if (fence != null) {
                if (line.startsWith(fence)) {
                    fence = null;
                }
                return;
            }
            if (insideTag) {
                insideTag = !line.contains(">");
                return;
            }
            if (UNFINISHED_TAG.matcher(line).find()) {
                flush();
                insideTag = true;
                return;
            }
            if (line.startsWith("```") || line.startsWith("~~~")) {
                flush();
                fence = line.substring(0, 3);
                return;
            }
            if (line.startsWith("#")) {
                flush();
                int level = line.length() - line.replaceFirst("^#+", "").length();
                String heading = line.replaceAll("^#+|#+$", "");
                underIntroHeading = isIntroHeading(heading);
                enterSection(level, isUsageHeading(heading));
                return;
            }
            if (SETEXT_UNDERLINE.matcher(line).matches() && !lines.isEmpty()) {
                String title = String.join(" ", lines);
                lines.clear();
                underIntroHeading = isIntroHeading(title);
                enterSection(line.startsWith("=") ? 1 : 2, isUsageHeading(title));
                return;
            }
            if (line.startsWith("|") || LIST_ITEM.matcher(line).find() || REFERENCE_DEFINITION.matcher(line).matches()) {
                flush();
                return;
            }

            String text = clean(line.replaceFirst("^(?:>\\s?)+", ""));
            if (text.isBlank() || hasFewLetters(text)) {
                flush();
                return;
            }

            // 태그로 시작하는 줄은 p·div 같은 블록이라 붙어 있어도 줄마다 다른 문단이다
            boolean block = line.startsWith("<");
            if (block) {
                flush();
            }
            lines.add(text);
            if (block) {
                flush();
            }

        }

        // 설치·실행 절은 그 아래 작은 머리글(### `npm start`)까지 같은 절이라, 같거나 큰 머리글이 나와야 벗어난다
        private void enterSection(int level, boolean usage) {

            if (usage) {
                underUsageHeading = true;
                usageLevel = level;
            } else if (usageLevel == 0 || level <= usageLevel) {
                underUsageHeading = false;
                usageLevel = 0;
            }

        }

        void flush() {

            if (lines.isEmpty()) {
                return;
            }
            paragraphs.add(new Paragraph(String.join(" ", lines).replaceAll("\\s+", " ").strip(), underIntroHeading,
                    underUsageHeading));
            lines.clear();

        }
    }

    private record Paragraph(String text, boolean underIntroHeading, boolean underUsageHeading) {
    }

    private static String clean(String line) {

        String text = IMAGE.matcher(line).replaceAll("");
        text = LINK.matcher(text).replaceAll("$1");
        text = HTML_TAG.matcher(text).replaceAll("");
        text = MARKER.matcher(text).replaceAll("").strip();

        // 줄 끝의 역슬래시는 줄바꿈 표시라서 글에 남기지 않는다
        return text.endsWith("\\") ? text.substring(0, text.length() - 1).stripTrailing() : text;

    }

    private static boolean isIntroHeading(String heading) {

        String title = clean(heading)
                .replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "")
                .replaceFirst("^\\d+[.)]\\s*", "");

        return INTRO_HEADINGS.contains(title.toLowerCase(Locale.ROOT));

    }

    private static boolean isUsageHeading(String heading) {

        String title = clean(heading)
                .replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "")
                .replaceFirst("^\\d+[.)]\\s*", "");

        return USAGE_HEADINGS.contains(title.toLowerCase(Locale.ROOT));

    }

    // 구분선이나 이모지만 있는 줄처럼 글자·숫자가 절반도 안 되는 줄은 글이 아니다
    private static boolean hasFewLetters(String line) {

        long letters = line.codePoints().filter(Character::isLetterOrDigit).count();
        long visible = line.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint)).count();

        return letters * 2 < visible;

    }

    private static boolean isProse(String text) {

        return DescriptionText.length(text) >= MIN_PARAGRAPH_LENGTH
                && !text.endsWith(":")
                && !COMMAND.matcher(text).find()
                && !text.toLowerCase(Locale.ROOT).contains("bootstrapped with");

    }

    private static String firstSentence(String paragraph) {

        Matcher end = SENTENCE_END.matcher(paragraph);
        String sentence = end.find() ? paragraph.substring(0, end.end()) : paragraph;
        if (DescriptionText.length(sentence) <= MAX_LENGTH) {
            return sentence;
        }

        return DescriptionText.cut(sentence, MAX_LENGTH).stripTrailing() + ELLIPSIS;

    }
}
