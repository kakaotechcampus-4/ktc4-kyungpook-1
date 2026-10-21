package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.FetchedPosting;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
 * Greenhouse 공개 채용 API 에서 회사 한 곳의 공고 목록을 받아 온다
 * 조건을 뽑을 본문(content)을 목록과 함께 받아 글로 바꿔 넘기며, 본문은 저장하지 않고 실행 안에서만 쓴다
 */
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(MatchingProperties.class)
public class GreenhouseClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int MAX_TITLE_LENGTH = 255;
    private static final int MAX_EXTERNAL_ID_LENGTH = 255;

    private final MatchingProperties properties;

    /** 응답 모양이 다르면 PARSE 로 실패하고, 일부 공고만 건너뛰지 않는다 */
    public List<FetchedPosting> list(PoliteFetchSession session, CompanySource source) {

        String body = session.get(URI.create(properties.greenhouseBaseUrl() + "/v1/boards/" + source.board() + "/jobs?content=true"));
        JsonNode jobs = parse(body).path("jobs");
        if (!jobs.isArray()) {
            throw malformed(source, "jobs 배열이 없다");
        }
        List<FetchedPosting> postings = new ArrayList<>();
        for (JsonNode job : jobs) {
            postings.add(postingOf(job, source));
        }
        return postings;

    }

    private static JsonNode parse(String body) {

        try {
            return JSON.readTree(body);
        } catch (JacksonException e) {
            throw new PoliteFetchException(FetchFailureCode.PARSE, "JSON 이 아닌 응답이다");
        }

    }

    private static FetchedPosting postingOf(JsonNode job, CompanySource source) {

        JsonNode id = job.path("id");
        String title = textOf(job.path("title"));
        String url = textOf(job.path("absolute_url"));
        String externalId = id.isNumber() || id.isString() ? id.asString().strip() : "";
        if (externalId.isEmpty() || externalId.length() > MAX_EXTERNAL_ID_LENGTH || title.isEmpty() || !isWebUrl(url)) {
            throw malformed(source, "id·title·absolute_url 이 없거나 알맞지 않은 공고가 있다");
        }
        return new FetchedPosting(externalId, title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title,
                url, textOf(job.path("location").path("name")), instantOf(job.path("updated_at")),
                instantOf(job.path("application_deadline")), PostingTextConverter.fromEscapedHtml(textOf(job.path("content"))));

    }

    private static boolean isWebUrl(String url) {

        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://");

    }

    private static String textOf(JsonNode value) {

        return value.isString() ? value.asString().strip() : "";

    }

    // 날짜만 오면 그날 끝까지를 마감으로 보고, 읽지 못하는 값은 없는 것으로 둔다
    private static Instant instantOf(JsonNode value) {

        if (!value.isString()) {
            return null;
        }
        String text = value.asString().strip();
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeException e) {
            try {
                return LocalDate.parse(text).plusDays(1).atStartOfDay(SEOUL).toInstant();
            } catch (DateTimeException ignored) {
                return null;
            }
        }

    }

    private static PoliteFetchException malformed(CompanySource source, String reason) {

        return new PoliteFetchException(FetchFailureCode.PARSE, source.board() + " 응답을 읽지 못했다: " + reason);

    }
}
