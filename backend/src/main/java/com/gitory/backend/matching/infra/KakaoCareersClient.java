package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.FetchedPosting;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * 카카오 채용 사이트의 공고 목록 API 를 쪽마다 읽으며, 소개·업무·자격 칸이 목록에 함께 와서 상세를 따로 요청하지 않는다
 * board 에는 page 를 뺀 목록 주소를 적고, 카카오페이처럼 그리팅으로 이미 읽는 계열사와 겹치지 않게 company=KAKAO 만 쓴다
 */
@Component
public class KakaoCareersClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int MAX_TITLE_LENGTH = 255;
    // 쪽 수 칸이 잘못 와도 끝없이 요청하지 않게 막는 상한이며, 지금 테크 공고는 한 쪽이다
    private static final int MAX_PAGES = 20;
    private static final List<String> BODY_FIELDS = List.of("introduction", "workContentDesc", "qualification");

    public List<FetchedPosting> list(PoliteFetchSession session, CompanySource source) {

        List<FetchedPosting> postings = new ArrayList<>();
        int totalPages = 1;
        for (int page = 1; page <= Math.min(totalPages, MAX_PAGES); page++) {
            JsonNode body = parse(session.get(URI.create(source.board() + "&page=" + page)), source);
            JsonNode jobs = body.path("jobList");
            if (!jobs.isArray() || !body.path("totalPage").isIntegralNumber()) {
                throw malformed(source, "jobList 배열이나 totalPage 가 없다");
            }
            totalPages = body.path("totalPage").asInt();
            for (JsonNode job : jobs) {
                if (job.path("privateFlag").asBoolean(false) || job.path("closeFlag").asBoolean(false)) {
                    continue;
                }
                postings.add(postingOf(job, source));
            }
        }
        return postings;

    }

    private static FetchedPosting postingOf(JsonNode job, CompanySource source) {

        String id = textOf(job.path("realId"));
        String title = textOf(job.path("jobOfferTitle"));
        if (id.isEmpty() || !id.matches("[A-Za-z]-\\d+") || title.isEmpty()) {
            throw malformed(source, "realId·jobOfferTitle 이 없거나 알맞지 않은 공고가 있다");
        }
        URI board = URI.create(source.board());
        return new FetchedPosting(id, title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title,
                board.getScheme() + "://" + board.getRawAuthority() + "/jobs/" + id, textOf(job.path("locationName")),
                instantOf(job.path("uptDate")), instantOf(job.path("endDate")), bodyOf(job));

    }

    private static String bodyOf(JsonNode job) {

        StringJoiner body = new StringJoiner("\n");
        for (String field : BODY_FIELDS) {
            String text = PostingTextConverter.fromHtml(textOf(job.path(field)));
            if (!text.isEmpty()) {
                body.add(text);
            }
        }
        return body.toString();

    }

    // 시각에 시간대가 없어 서울 시각으로 보고, 읽지 못하는 값은 없는 것으로 둔다
    private static Instant instantOf(JsonNode value) {

        if (!value.isString()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.asString().strip()).atZone(SEOUL).toInstant();
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
