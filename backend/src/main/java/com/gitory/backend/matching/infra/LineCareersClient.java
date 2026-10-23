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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * 라인플러스 채용 사이트가 미리 만들어 둔 정적 JSON 에서 공고를 읽는다
 * 목록 파일 하나에 해외 계열사 공고까지 모두 있어 근무 도시로 한국 공고를 가르고, 본문은 공고마다 있는 상세 JSON 에서 읽는다
 */
@Component
public class LineCareersClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_TITLE_LENGTH = 255;

    public List<FetchedPosting> list(PoliteFetchSession session, CompanySource source) {

        URI listUrl = URI.create(source.board());
        String origin = listUrl.getScheme() + "://" + listUrl.getRawAuthority();
        JsonNode edges = parse(session.get(listUrl), source).path("result").path("data").path("allStrapiJobs").path("edges");
        if (!edges.isArray()) {
            throw malformed(source, "allStrapiJobs.edges 배열이 없다");
        }
        List<FetchedPosting> postings = new ArrayList<>();
        for (JsonNode edge : edges) {
            JsonNode job = edge.path("node");
            if (!job.path("publish").asBoolean(false)) {
                continue;
            }
            FetchedPosting listed = listedPosting(job, origin, source);
            // 상세 요청은 한 건마다 1초 이상 걸리므로 개발 공고에만 쓴다
            postings.add(DevelopmentPostingFilter.accepts(listed)
                    ? PostingDetails.withText(listed, source, () -> detailText(session, origin, listed, source))
                    : listed);
        }
        return postings;

    }

    private static FetchedPosting listedPosting(JsonNode job, String origin, CompanySource source) {

        JsonNode id = job.path("strapiId");
        String title = textOf(job.path("title"));
        if (!id.isIntegralNumber() || id.asLong() <= 0 || title.isEmpty()) {
            throw malformed(source, "strapiId·title 이 없거나 알맞지 않은 공고가 있다");
        }
        return new FetchedPosting(String.valueOf(id.asLong()), title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title,
                origin + "/ko/jobs/" + id.asLong(), citiesOf(job), null, instantOf(job.path("end_date")), "");

    }

    private static String citiesOf(JsonNode job) {

        StringJoiner cities = new StringJoiner(", ");
        for (JsonNode city : job.path("cities")) {
            String name = textOf(city.path("name"));
            if (!name.isEmpty()) {
                cities.add(name);
            }
        }
        return cities.toString();

    }

    private static String detailText(PoliteFetchSession session, String origin, FetchedPosting listed,
                                     CompanySource source) {

        JsonNode content = parse(session.get(URI.create(origin + "/page-data/ko/jobs/" + listed.externalId() + "/page-data.json")), source)
                .path("result").path("data").path("strapiJobs").path("content");
        if (!content.isString()) {
            throw malformed(source, "공고 " + listed.externalId() + " 의 본문 칸이 없다");
        }
        return PostingTextConverter.fromHtml(content.asString());

    }

    // 읽지 못하는 마감일은 없는 것으로 둔다
    private static Instant instantOf(JsonNode value) {

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
