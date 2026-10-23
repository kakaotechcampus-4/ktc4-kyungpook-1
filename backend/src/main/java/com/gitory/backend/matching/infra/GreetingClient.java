package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.DevelopmentPostingFilter;
import com.gitory.backend.matching.domain.FetchedPosting;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
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

/**
 * 그리팅 채용 페이지에서 회사 한 곳의 공고를 읽는다
 * 리다이렉트를 따르지 않으므로 설정의 board 에는 이동이 끝난 목록 페이지 주소를 적고, 상세 페이지는 같은 호스트의 /ko/o/{id} 로 읽는다
 */
@Component
public class GreetingClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    // 설정에 있는 세 회사는 한국 회사라 근무지 칸이 비어 있으면 한국으로 본다
    private static final String DEFAULT_LOCATION = "대한민국";
    private static final int MAX_TITLE_LENGTH = 255;

    /** 페이지에 공고 데이터가 없거나 모양이 다르면 PARSE 로 실패하고, 일부 공고만 건너뛰지 않는다 */
    public List<FetchedPosting> list(PoliteFetchSession session, CompanySource source) {

        URI listUrl = URI.create(source.board());
        String origin = listUrl.getScheme() + "://" + listUrl.getRawAuthority();
        JsonNode openings = dataOf(session.get(listUrl), List.of("openings"), source);
        if (!openings.isArray()) {
            throw malformed(source, "공고 목록이 배열이 아니다");
        }
        List<FetchedPosting> postings = new ArrayList<>();
        for (JsonNode opening : openings) {
            if (opening.path("deploy").isBoolean() && !opening.path("deploy").asBoolean(false)) {
                continue;
            }
            FetchedPosting listed = listedPosting(opening, origin, source);
            // 상세 페이지 요청은 한 건마다 1초 이상 걸리므로 개발 공고에만 쓴다
            postings.add(DevelopmentPostingFilter.accepts(listed) ? withText(listed, detailText(session, listed, source)) : listed);
        }
        return postings;

    }

    private static FetchedPosting listedPosting(JsonNode opening, String origin, CompanySource source) {

        JsonNode id = opening.path("openingId");
        String title = textOf(opening.path("title"));
        if (!id.isIntegralNumber() || id.asLong() <= 0 || title.isEmpty()) {
            throw malformed(source, "openingId·title 이 없거나 알맞지 않은 공고가 있다");
        }
        return new FetchedPosting(String.valueOf(id.asLong()), title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title,
                origin + "/ko/o/" + id.asLong(), locationOf(opening), null, instantOf(opening.path("dueDate")), "");

    }

    private static String locationOf(JsonNode opening) {

        for (JsonNode position : opening.path("openingJobPosition").path("openingJobPositions")) {
            String place = textOf(position.path("workspacePlace").path("place"));
            if (!place.isEmpty()) {
                return place;
            }
        }
        return DEFAULT_LOCATION;

    }

    private static String detailText(PoliteFetchSession session, FetchedPosting listed, CompanySource source) {

        JsonNode detail = dataOf(session.get(URI.create(listed.sourceUrl())), List.of("career", "getOpeningById"), source)
                .path("data").path("openingsInfo").path("detail");
        if (!detail.isString()) {
            throw malformed(source, "공고 " + listed.externalId() + " 의 본문 칸이 없다");
        }
        return PostingTextConverter.fromHtml(detail.asString());

    }

    private static FetchedPosting withText(FetchedPosting posting, String text) {

        return new FetchedPosting(posting.externalId(), posting.title(), posting.sourceUrl(), posting.location(),
                posting.sourceUpdatedAt(), posting.expiresAt(), text);

    }

    // 상태 코드가 200 이어도 보안 검사 페이지처럼 공고 데이터가 없는 페이지를 받을 수 있어 데이터까지 확인한다
    private static JsonNode dataOf(String page, List<String> queryKeyPrefix, CompanySource source) {

        Element script = Jsoup.parse(page).getElementById("__NEXT_DATA__");
        if (script == null) {
            throw malformed(source, "__NEXT_DATA__ 가 없는 페이지다");
        }
        JsonNode queries;
        try {
            queries = JSON.readTree(script.data()).path("props").path("pageProps").path("dehydratedState").path("queries");
        } catch (JacksonException e) {
            throw malformed(source, "__NEXT_DATA__ 가 JSON 이 아니다");
        }
        for (JsonNode query : queries) {
            if (startsWith(query.path("queryKey"), queryKeyPrefix)) {
                return query.path("state").path("data");
            }
        }
        throw malformed(source, queryKeyPrefix + " 데이터가 없다");

    }

    private static boolean startsWith(JsonNode queryKey, List<String> prefix) {

        for (int i = 0; i < prefix.size(); i++) {
            JsonNode part = queryKey.path(i);
            if (!part.isString() || !part.asString().equals(prefix.get(i))) {
                return false;
            }
        }
        return true;

    }

    private static String textOf(JsonNode value) {

        return value.isString() ? value.asString().strip() : "";

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

    private static PoliteFetchException malformed(CompanySource source, String reason) {

        return new PoliteFetchException(FetchFailureCode.PARSE, source.key() + " 페이지를 읽지 못했다: " + reason);

    }
}
