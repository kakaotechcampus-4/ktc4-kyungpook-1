package com.gitory.backend.matching.domain;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 한국에서 일하는 개발 직무 공고만 남기는 규칙이다
 * 칩 설계·재무·영업 같은 공고와 해외 근무지 공고는 매칭 대상이 아니라 저장하지 않는다
 * 경력 공고도 저장하고, 연차에 맞는지는 보여 줄 때 거른다
 */
public final class DevelopmentPostingFilter {

    private static final List<String> KOREA_WORDS = List.of("korea", "seoul", "busan", "incheon", "daejeon",
            "pangyo", "seongnam", "suwon", "hwaseong", "dongtan", "bundang", "gwacheon", "pohang", "jeju", "changwon",
            "ulsan", "naju", "goyang", "한국", "대한민국", "서울", "부산", "인천", "대전", "판교", "성남", "수원", "화성", "분당",
            "과천", "포항", "제주", "창원", "울산", "나주", "고양", "광역시");

    // 짧은 낱말(ios, sre, qa, ml)이 다른 낱말 안에 끼어 걸리지 않게 영문은 낱말 경계를 본다
    // Architect 는 넣지 않는다. 고객 쪽 기술직(Solutions Architect)이 개발 공고로 들어온다
    // KT 는 직무가 모집 분야 이름(SW Engineering·AX개발 등)에만 있어 그 모양도 받고, 사업개발·부동산개발은 받지 않는다
    private static final Pattern DEVELOPMENT_ROLE = Pattern.compile("\\b(software|s/w|developer|back-?end|front-?end|"
            + "full[- ]?stack|android|ios|devops|sre|site reliability|machine learning|ml|mlops|llms?|programmer|sw engineer|data engineer|"
            + "data scientist|applied scientist|research scientist|(mobile|client|server|research|security|network|platform) engineer|"
            + "ai (agent |research )?engineer|threat detection engineer|forward deployed engineer|qa engineer|qa automation|sdet|"
            + "test automation|compiler|firmware|robot(ics)?|(sw|cloud/infra) engineering)\\b"
            + "|개발자|프로그래머|소프트웨어|백엔드|프론트엔드|머신러닝|펌웨어|(서버|데이터|클라우드|프로덕트)\\s?엔지니어"
            + "|(it|ax|앱|미들웨어|풀\\s?스택|ai\\s?서비스)\\s?개발|(ai|ax)\\s?연구개발",
            Pattern.CASE_INSENSITIVE);

    private DevelopmentPostingFilter() {
    }

    public static boolean accepts(FetchedPosting posting) {

        String location = posting.location().toLowerCase(Locale.ROOT);
        return KOREA_WORDS.stream().anyMatch(location::contains) && DEVELOPMENT_ROLE.matcher(posting.title()).find();

    }
}
