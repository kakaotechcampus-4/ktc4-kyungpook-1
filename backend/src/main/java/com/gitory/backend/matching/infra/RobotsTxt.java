package com.gitory.backend.matching.infra;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** robots.txt 한 파일에서 우리 이름(Gitory)에 적용되는 규칙만 골라 낸 결과 */
final class RobotsTxt {

    static final String PRODUCT_TOKEN = "gitory";

    private static final String ANY_AGENT = "*";

    private final List<Rule> rules;
    private final Duration crawlDelay;

    private RobotsTxt(List<Rule> rules, Duration crawlDelay) {
        this.rules = rules;
        this.crawlDelay = crawlDelay;
    }

    static RobotsTxt allowAll() {

        return new RobotsTxt(List.of(), Duration.ZERO);

    }

    static RobotsTxt parse(String text) {

        List<Group> groups = new ArrayList<>();
        Group current = null;
        boolean collectingAgents = false;
        String content = text.startsWith("\uFEFF") ? text.substring(1) : text;
        for (String rawLine : content.split("\\R")) {
            String line = stripComment(rawLine).trim();
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            switch (key) {
                case "user-agent" -> {
                    if (!collectingAgents) {
                        current = new Group();
                        groups.add(current);
                        collectingAgents = true;
                    }
                    current.agents.add(value.toLowerCase(Locale.ROOT));
                }
                case "allow", "disallow" -> {
                    collectingAgents = false;
                    if (current != null && !value.isEmpty()) {
                        current.rules.add(new Rule("allow".equals(key), value));
                    }
                }
                case "crawl-delay" -> {
                    collectingAgents = false;
                    if (current != null) {
                        current.crawlDelay = max(current.crawlDelay, parseSeconds(value));
                    }
                }
                default -> {
                }
            }
        }
        return select(groups);

    }

    boolean isAllowed(String pathAndQuery) {

        if ("/robots.txt".equals(pathAndQuery)) {
            return true;
        }
        Rule best = null;
        for (Rule rule : rules) {
            if (!matches(rule.pattern, pathAndQuery)) {
                continue;
            }
            if (best == null || rule.pattern.length() > best.pattern.length()
                    || (rule.pattern.length() == best.pattern.length() && rule.allow && !best.allow)) {
                best = rule;
            }
        }
        return best == null || best.allow;

    }

    Duration crawlDelay() {

        return crawlDelay;

    }

    private static RobotsTxt select(List<Group> groups) {

        List<Group> chosen = groups.stream().filter(g -> g.agents.contains(PRODUCT_TOKEN)).toList();
        if (chosen.isEmpty()) {
            chosen = groups.stream().filter(g -> g.agents.contains(ANY_AGENT)).toList();
        }
        List<Rule> rules = new ArrayList<>();
        Duration delay = Duration.ZERO;
        for (Group group : chosen) {
            rules.addAll(group.rules);
            delay = max(delay, group.crawlDelay);
        }
        return new RobotsTxt(List.copyOf(rules), delay);

    }

    // 정규식 대신 조각을 차례로 찾아 길고 복잡한 규칙에도 시간이 오래 걸리지 않게 한다
    private static boolean matches(String pattern, String target) {

        boolean anchored = pattern.endsWith("$");
        String body = anchored ? pattern.substring(0, pattern.length() - 1) : pattern;
        String[] parts = body.split("\\*", -1);
        if (parts.length == 1) {
            return anchored ? target.equals(body) : target.startsWith(body);
        }
        if (!target.startsWith(parts[0])) {
            return false;
        }
        int position = parts[0].length();
        for (int i = 1; i < parts.length - 1; i++) {
            int found = target.indexOf(parts[i], position);
            if (found < 0) {
                return false;
            }
            position = found + parts[i].length();
        }
        String last = parts[parts.length - 1];
        if (anchored) {
            return target.length() - last.length() >= position && target.endsWith(last);
        }
        return target.indexOf(last, position) >= 0;

    }

    private static String stripComment(String line) {

        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);

    }

    private static Duration parseSeconds(String value) {

        try {
            double seconds = Double.parseDouble(value);
            if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds < 0) {
                return Duration.ZERO;
            }
            return Duration.ofMillis(Math.round(seconds * 1000));
        } catch (NumberFormatException e) {
            return Duration.ZERO;
        }

    }

    private static Duration max(Duration a, Duration b) {

        return a.compareTo(b) >= 0 ? a : b;

    }

    private static final class Group {

        final List<String> agents = new ArrayList<>();
        final List<Rule> rules = new ArrayList<>();
        Duration crawlDelay = Duration.ZERO;
    }

    private record Rule(boolean allow, String pattern) {
    }
}
