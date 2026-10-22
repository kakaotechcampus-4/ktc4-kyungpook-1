package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.FetchedPosting;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 토스 채용 페이지가 쓰는 공고 API 에서 공고를 읽으며, Greenhouse 칸에 본문(Job Description)이 metadata 로 함께 와서 상세를 따로 요청하지 않는다
 * 공식 문서가 없는 API 라 칸 이름이 바뀌면 목록 전체를 PARSE 로 실패시켜 알아채게 한다
 */
@Component
public class TossCareersClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int MAX_TITLE_LENGTH = 255;
    private static final String DESCRIPTION = "Job Description";
    private static final String HIDDEN = "커리어페이지 메뉴에 \"미노출\"";
    private static final String CLOSING_DATE = "커리어페이지 채용공고 클로징 일자";

    public List<FetchedPosting> list(PoliteFetchSession session, CompanySource source) {

        JsonNode jobs = parse(session.get(URI.create(source.board())), source).path("success");
        if (!jobs.isArray()) {
            throw malformed(source, "success 배열이 없다");
        }
        List<FetchedPosting> postings = new ArrayList<>();
        for (JsonNode job : jobs) {
            if (metadata(job, HIDDEN).asBoolean(false)) {
                continue;
            }
            postings.add(postingOf(job, source));
        }
        return postings;

    }

    private static FetchedPosting postingOf(JsonNode job, CompanySource source) {

        JsonNode id = job.path("id");
        String title = textOf(job.path("title"));
        String url = textOf(job.path("absolute_url"));
        if (!id.isIntegralNumber() || id.asLong() <= 0 || title.isEmpty() || !url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw malformed(source, "id·title·absolute_url 이 없거나 알맞지 않은 공고가 있다");
        }
        // 본문은 HTML 이 아니라 마크다운 글이라 줄 구조를 그대로 넘긴다
        return new FetchedPosting(String.valueOf(id.asLong()), title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title,
                url, textOf(job.path("location").path("name")), offsetInstantOf(job.path("updated_at")),
                closingOf(job), textOf(metadata(job, DESCRIPTION)));

    }

    // 접수 마감일이 정해진 공고만 이 칸에 날짜가 있고, 날짜만 오므로 그날 끝까지를 마감으로 본다
    private static Instant closingOf(JsonNode job) {

        String date = textOf(metadata(job, CLOSING_DATE));
        if (date.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(date).plusDays(1).atStartOfDay(SEOUL).toInstant();
        } catch (DateTimeException e) {
            return null;
        }

    }

    private static JsonNode metadata(JsonNode job, String namePrefix) {

        for (JsonNode field : job.path("metadata")) {
            if (textOf(field.path("name")).startsWith(namePrefix)) {
                return field.path("value");
            }
        }
        return JSON.missingNode();

    }

    private static Instant offsetInstantOf(JsonNode value) {

        if (!value.isString()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.asString().strip()).toInstant();
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
