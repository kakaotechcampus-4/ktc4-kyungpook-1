package com.gitory.backend.assistant.domain;

import com.gitory.backend.matching.domain.ExperienceLevel;
import com.gitory.backend.matching.domain.JobCategory;
import com.gitory.backend.matching.domain.MatchTarget;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 지원할 수 있는 공고에서 회사 이름과 낱말로 걸러 맞은 낱말이 많은 순, 마감이 빠른 순으로 앞의 몇 건을 고른다
 * 회사를 말하면 그 회사 공고 중 낱말이 맞은 것만 남기고 하나도 안 맞으면 그 회사 공고를 모두 남기며 앞의 몇 건에는 같은 회사의 같은 제목을 한 번만 담는다
 * 분야와 경력 낱말은 표의 말과 같을 때만 맞은 것으로 본다
 */
final class PostingFinder {

    static final int LIMIT = 5;

    private static final Map<JobCategory, Set<String>> CATEGORY_WORDS = Map.of(
            JobCategory.BACKEND, Set.of("백엔드", "서버", "backend"),
            JobCategory.FRONTEND, Set.of("프론트엔드", "프론트", "frontend"),
            JobCategory.MOBILE, Set.of("모바일", "안드로이드", "android", "ios"),
            JobCategory.DEVOPS, Set.of("인프라", "devops", "데브옵스"),
            JobCategory.DATA, Set.of("데이터", "data"),
            JobCategory.AI_ML, Set.of("ai", "ml", "머신러닝", "인공지능"),
            JobCategory.SECURITY, Set.of("보안", "security"),
            JobCategory.QA, Set.of("qa", "테스트"));

    private static final Map<ExperienceLevel, Set<String>> EXPERIENCE_WORDS = Map.of(
            ExperienceLevel.ENTRY, Set.of("신입"),
            ExperienceLevel.EXPERIENCED, Set.of("경력"));

    private static final Comparator<Ranked> BEST_FIRST = Comparator.comparingInt(Ranked::matched).reversed()
            .thenComparing(Ranked::expiresAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder()));

    private PostingFinder() {
    }

    record Found(List<MatchTarget> top, int total) {
    }

    private record Ranked(MatchTarget target, int matched) {

        Instant expiresAt() {

            return target.expiresAt();

        }
    }

    static Found find(List<MatchTarget> targets, String company, List<String> keywords) {

        String wantedCompany = normalized(company);
        List<String> words = keywords.stream()
                .map(PostingFinder::normalized)
                .filter(word -> !word.isEmpty())
                .distinct()
                .toList();

        List<Ranked> candidates = targets.stream()
                .filter(target -> wantedCompany.isEmpty() || normalized(target.company()).contains(wantedCompany))
                .map(target -> new Ranked(target, matchedWords(target, words)))
                .toList();
        boolean keepUnmatched = words.isEmpty()
                || !wantedCompany.isEmpty() && candidates.stream().noneMatch(candidate -> candidate.matched() > 0);

        List<Ranked> ranked = candidates.stream()
                .filter(candidate -> keepUnmatched || candidate.matched() > 0)
                .sorted(BEST_FIRST)
                .toList();

        Set<String> seen = new HashSet<>();
        List<MatchTarget> top = ranked.stream()
                .map(Ranked::target)
                .filter(target -> seen.add(normalized(target.company()) + "\n" + normalized(target.role())))
                .limit(LIMIT)
                .toList();

        return new Found(top, ranked.size());

    }

    private static int matchedWords(MatchTarget target, List<String> words) {

        String role = normalized(target.role());
        List<String> techs = target.techs() == null ? List.of() : target.techs().stream()
                .map(PostingFinder::normalized)
                .toList();

        return (int) words.stream()
                .filter(word -> role.contains(word)
                        || techs.stream().anyMatch(tech -> tech.contains(word))
                        || listed(CATEGORY_WORDS, target.category(), word)
                        || listed(EXPERIENCE_WORDS, target.experienceLevel(), word))
                .count();

    }

    private static boolean listed(Map<?, Set<String>> table, Object key, String word) {

        Set<String> words = key == null ? null : table.get(key);

        return words != null && words.contains(word);

    }

    private static String normalized(String text) {

        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);

    }
}
