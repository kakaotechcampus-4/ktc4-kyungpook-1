package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.DevelopmentPostingFilter;
import com.gitory.backend.matching.domain.FetchedPosting;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * 스마일게이트 채용 사이트가 로그인하지 않은 방문자에게 쓰는 공고 API 에서 공고를 읽는다
 * 목록은 검색 조건을 JSON 으로 보내는 조회용 POST 로만 주고, 개발 공고의 본문은 공고마다 GET 으로 읽는다
 */
@Component
public class SmilegateCareersClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE.withLocale(Locale.ROOT);
    // 국내 계열사 공고만 올라오는 채용 사이트라 근무지 칸이 없어도 한국으로 본다
    private static final String LOCATION = "대한민국";
    private static final int PAGE_SIZE = 100;
    // 전체 개수 칸이 잘못 와도 끝없이 요청하지 않게 막는 상한이며, 지금은 두 쪽이다
    private static final int MAX_PAGES = 10;
    private static final int MAX_TITLE_LENGTH = 255;
    private static final List<String> BODY_FIELDS = List.of("workInfo", "qualificationDesc", "abilityDesc", "specialDesc");

    public List<FetchedPosting> list(PoliteFetchSession session, CompanySource source) {

        URI listUrl = URI.create(source.board());
        String origin = listUrl.getScheme() + "://" + listUrl.getRawAuthority();
        List<FetchedPosting> postings = new ArrayList<>();
        int read = 0;
        int expected = 0;
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode body = parse(session.post(listUrl, searchOf(page)), source);
            JsonNode announces = body.path("announce");
            if (!announces.isArray() || !body.path("announceCount").isIntegralNumber()) {
                throw malformed(source, "announce 배열이나 announceCount 가 없다");
            }
            for (JsonNode announce : announces) {
                // 비밀번호를 아는 사람에게만 여는 공고라 열어 보지 않는다
                if (!announce.path("urlPassword").isNull() && !announce.path("urlPassword").isMissingNode()) {
                    continue;
                }
                FetchedPosting listed = listedPosting(announce, origin, source);
                // 상세 요청은 한 건마다 1초 이상 걸리므로 개발 공고에만 쓴다
                postings.add(DevelopmentPostingFilter.accepts(listed)
                        ? PostingDetails.withText(listed, source, () -> detailText(session, origin, listed, source))
                        : listed);
            }
            read += announces.size();
            expected = body.path("announceCount").asInt();
            if (announces.isEmpty() || read >= expected) {
                break;
            }
        }
        // 목록을 끝까지 못 읽고 저장하면 빠진 공고가 사라짐으로 바뀌므로 회사 전체를 실패시킨다
        if (read < expected) {
            throw malformed(source, "전체 " + expected + "건 중 " + read + "건만 읽었다");
        }
        return postings;

    }

    // 사이트 화면이 보내는 검색 조건에서 쪽 번호와 쪽 크기만 바꾼다
    private static String searchOf(int page) {

        return "{\"careerTypeCd\":[],\"companyCd\":[],\"gameGenreCd\":[],\"hireTypeCd\":[],\"jobDtlCd\":[],\"jobMainCd\":[],"
                + "\"keyword\":null,\"pageIndex\":" + page + ",\"pageSize\":" + PAGE_SIZE + ",\"projectSeq\":null,\"usreId\":null}";

    }

    private static FetchedPosting listedPosting(JsonNode announce, String origin, CompanySource source) {

        JsonNode id = announce.path("announceSeq");
        String title = textOf(announce.path("title"));
        if (!id.isIntegralNumber() || id.asLong() <= 0 || title.isEmpty()) {
            throw malformed(source, "announceSeq·title 이 없거나 알맞지 않은 공고가 있다");
        }
        return new FetchedPosting(String.valueOf(id.asLong()), title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title,
                origin + "/apply/announce/view?seq=" + id.asLong(), LOCATION, null, endDateOf(announce.path("endDate")), "");

    }

    private static String detailText(PoliteFetchSession session, String origin, FetchedPosting listed,
                                     CompanySource source) {

        JsonNode detail = parse(session.get(URI.create(origin + "/api/apply/announce/guest/" + listed.externalId())), source);
        StringJoiner body = new StringJoiner("\n");
        boolean found = false;
        for (String field : BODY_FIELDS) {
            JsonNode value = detail.path(field);
            found |= value.isString();
            String text = textOf(value);
            if (!text.isEmpty()) {
                body.add(text);
            }
        }
        if (!found) {
            throw malformed(source, "공고 " + listed.externalId() + " 의 업무·자격 칸이 없다");
        }
        return body.toString();

    }

    // 날짜만 오므로 그날 끝까지를 마감으로 보고, 읽지 못하는 값은 없는 것으로 둔다
    private static Instant endDateOf(JsonNode value) {

        if (!value.isString()) {
            return null;
        }
        try {
            return LocalDate.parse(value.asString().strip(), BASIC_DATE).plusDays(1).atStartOfDay(SEOUL).toInstant();
        } catch (DateTimeException e) {
            return null;
        }

    }

    private static JsonNode parse(String body, CompanySource source) {

        try {
            return JSON.readTree(body);
        } catch (JacksonException e) {
            throw malformed(source, "JSON 이 아닌 응답이다");
        }

    }

    private static String textOf(JsonNode value) {

        return value.isString() ? value.asString().strip() : "";

    }

    private static PoliteFetchException malformed(CompanySource source, String reason) {

        return new PoliteFetchException(FetchFailureCode.PARSE, source.key() + " 응답을 읽지 못했다: " + reason);

    }
}
