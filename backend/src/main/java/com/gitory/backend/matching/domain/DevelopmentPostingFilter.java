package com.gitory.backend.matching.domain;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 한국에서 일하는 개발 직무 공고만 남기는 규칙이다
 * 칩 설계·재무·영업 같은 공고와 해외 근무지 공고는 매칭 대상이 아니라 저장하지 않는다
 * 제목에 시니어·관리자 표시가 있는 공고도 신입·주니어가 지원할 공고가 아니라 저장하지 않는다
 */
public final class DevelopmentPostingFilter {

    private static final List<String> KOREA_WORDS = List.of("korea", "seoul", "busan", "incheon", "daejeon",
            "pangyo", "seongnam", "suwon", "hwaseong", "dongtan", "한국", "대한민국", "서울", "부산", "인천", "대전", "판교", "성남",
            "수원", "화성", "광역시");

    // 짧은 낱말(ios, sre, qa, ml)이 다른 낱말 안에 끼어 걸리지 않게 영문은 낱말 경계를 본다
    private static final Pattern DEVELOPMENT_ROLE = Pattern.compile("\\b(software|s/w|developer|back-?end|front-?end|"
            + "full[- ]?stack|android|ios|devops|sre|site reliability|machine learning|ml|mlops|llms?|programmer|sw engineer|data engineer|"
            + "data scientist|applied scientist|(mobile|client|server|research|security|network|platform) engineer|"
            + "ai (agent |research )?engineer|threat detection engineer|qa engineer|qa automation|sdet|"
            + "test automation)\\b|개발자|프로그래머|소프트웨어|백엔드|프론트엔드|(서버|데이터|클라우드|프로덕트)\\s?엔지니어",
            Pattern.CASE_INSENSITIVE);

    // 한글 낱말은 앞 글자가 한글이면 다른 낱말의 일부(예: 그리드)로 보고 거르지 않는다
    private static final Pattern SENIOR_TITLE = Pattern.compile(
            "\\b(senior|sr|staff|principal|lead|manager|director|head of|vp|chief)\\b"
                    + "|(?<![가-힣])(시니어|리드|팀장|책임|수석|파트장|실장|본부장|매니저)", Pattern.CASE_INSENSITIVE);

    private DevelopmentPostingFilter() {
    }

    public static boolean accepts(FetchedPosting posting) {

        String location = posting.location().toLowerCase(Locale.ROOT);
        return KOREA_WORDS.stream().anyMatch(location::contains) && DEVELOPMENT_ROLE.matcher(posting.title()).find()
                && !SENIOR_TITLE.matcher(posting.title()).find();

    }
}
