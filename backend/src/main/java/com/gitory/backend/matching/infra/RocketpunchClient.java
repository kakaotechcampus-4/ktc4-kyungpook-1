package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.DevelopmentPostingFilter;
import com.gitory.backend.matching.domain.FetchedPosting;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 로켓펀치 Open API 로 여러 회사의 개발 공고를 읽고, 공고마다 API 가 준 회사 이름을 넘긴다
 * 회사 채용 페이지를 따로 읽는 회사는 설정의 회사 id 로 건너뛰어 같은 공고가 두 번 들어가지 않게 한다
 */
@Component
@RequiredArgsConstructor
public class RocketpunchClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String API_KEY_HEADER = "X-RP-API-Key";
    // 명세는 /api/v1 로 적혀 있지만 그대로 부르면 서버가 /api 를 한 번 더 붙여 404 라서 /v1 로 부른다
    private static final String LIST_PATH = "/v1/jobs?jobCategories=dev&jobCategories=dataAi&jobCategories=engineer"
            + "&pageSize=50&page=";
    private static final String DETAIL_PATH = "/v1/jobs/";
    // 국내 채용 플랫폼이고 목록에 근무지 칸이 없어 한국 근무로 본다
    private static final String LOCATION = "대한민국";
    // 쪽 수가 비정상으로 크면 하루 호출 한도를 다 쓰므로 회사 전체를 실패시킨다
    private static final int MAX_PAGES = 20;
    private static final int MAX_TITLE_LENGTH = 255;
    private static final Pattern HTML_TAG = Pattern.compile("<[a-zA-Z][^>]*>");

    private final MatchingProperties properties;
    private final Clock clock;

    public boolean isConfigured() {

        return !properties.rocketpunch().apiKey().isEmpty();

    }

    /** 목록 모양이 다르면 PARSE 로 전체가 실패하고, 상세 하나를 못 읽으면 그 공고만 본문 없이 넘긴다 */
    public List<FetchedPosting> list(PoliteFetchSession session, CompanySource source) {

        Map<String, String> headers = Map.of(API_KEY_HEADER, properties.rocketpunch().apiKey());
        Set<String> skipped = Set.copyOf(properties.rocketpunch().skipCompanyIds());
        String base = source.board().replaceAll("/+$", "");
        Instant now = clock.instant();
        List<FetchedPosting> postings = new ArrayList<>();
        int totalPages = 1;
        for (int page = 1; page <= totalPages; page++) {
            JsonNode body = read(session.get(URI.create(base + LIST_PATH + page), headers), source);
            JsonNode items = body.path("items");
            JsonNode total = body.path("totalPages");
            if (!items.isArray() || !total.isIntegralNumber() || total.asInt() < 0 || total.asInt() > MAX_PAGES) {
                throw malformed(source, "items 배열이나 알맞은 totalPages 가 없다");
            }
            totalPages = total.asInt();
            for (JsonNode item : items) {
                if (skipped.contains(textOf(item.path("company").path("id")))) {
                    continue;
                }
                FetchedPosting listed = listedPosting(item, source);
                if (listed.expiresAt() != null && listed.expiresAt().isBefore(now)) {
                    continue;
                }
                if (!DevelopmentPostingFilter.accepts(listed)) {
                    postings.add(listed);
                    continue;
                }
                // 상세 요청은 목록 3쪽 안팎에 개발 공고마다 한 번이라 하루 호출 한도(5,000번) 안이다
                PostingDetails.fromDetail(listed, source, () -> detailed(session, base, headers, listed, now, source))
                        .ifPresent(postings::add);
            }
        }
        return postings;

    }

    private static FetchedPosting listedPosting(JsonNode item, CompanySource source) {

        JsonNode id = item.path("jobId");
        String title = textOf(item.path("title"));
        String url = textOf(item.path("webUrl"));
        if (!id.isIntegralNumber() || id.asLong() <= 0 || title.isEmpty() || !url.startsWith("https://")) {
            throw malformed(source, "jobId·title·webUrl 이 없거나 알맞지 않은 공고가 있다");
        }
        String company = textOf(item.path("company").path("name"));
        return new FetchedPosting(String.valueOf(id.asLong()),
                title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title, url, LOCATION, null,
                instantOf(item.path("endAt")), "", List.of(), company.isEmpty() ? null : company);

    }

    // 상세에서 모집이 끝났다고 확인한 공고는 목록에서 뺀다
    private static Optional<FetchedPosting> detailed(PoliteFetchSession session, String base, Map<String, String> headers,
                                                     FetchedPosting listed, Instant now, CompanySource source) {

        JsonNode detail = read(session.get(URI.create(base + DETAIL_PATH + listed.externalId()), headers), source);
        if (!detail.path("hiring").isBoolean()) {
            throw malformed(source, "공고 " + listed.externalId() + " 의 hiring 칸이 없다");
        }
        Instant expiresAt = detail.has("endAt") ? instantOf(detail.path("endAt")) : listed.expiresAt();
        if (!detail.path("hiring").asBoolean() || (expiresAt != null && expiresAt.isBefore(now))) {
            return Optional.empty();
        }
        String company = textOf(detail.path("company").path("name"));
        return Optional.of(new FetchedPosting(listed.externalId(), listed.title(), listed.sourceUrl(), listed.location(),
                null, expiresAt, bodyOf(detail), List.of(), company.isEmpty() ? listed.companyName() : company));

    }

    private static String bodyOf(JsonNode detail) {

        List<String> parts = new ArrayList<>();
        addSection(parts, null, detail.path("description"));
        addSection(parts, "주요 업무", detail.path("duty"));
        addSection(parts, "자격 요건", detail.path("minimumQualification"));
        addSection(parts, "우대 사항", detail.path("preferredQualification"));
        return String.join("\n", parts);

    }

    private static void addSection(List<String> parts, String heading, JsonNode value) {

        String raw = value.isString() ? value.asString() : "";
        // 칸이 HTML 이면 글로 바꾸고, 평문이면 줄 구조를 그대로 둔다
        String body = HTML_TAG.matcher(raw).find() ? PostingTextConverter.fromHtml(raw)
                : Arrays.stream(raw.split("\\R")).map(String::strip).filter(line -> !line.isEmpty())
                        .collect(Collectors.joining("\n"));
        if (body.isEmpty()) {
            return;
        }
        if (heading != null) {
            parts.add(heading);
        }
        parts.add(body);

    }

    private static JsonNode read(String body, CompanySource source) {

        try {
            return JSON.readTree(body);
        } catch (JacksonException e) {
            throw malformed(source, "응답이 JSON 이 아니다");
        }

    }

    private static String textOf(JsonNode value) {

        return value.isString() ? value.asString().strip() : "";

    }

    // 목록은 시간대 표시 없이 UTC 시각(예: 2027-01-19T15:00)을, 상세는 Z 가 붙은 UTC 시각을 준다(10/24 실제 응답에서 같은 공고로 확인)
    // 읽지 못하는 마감일은 없는 것으로 둔다
    private static Instant instantOf(JsonNode value) {

        if (!value.isString()) {
            return null;
        }
        String text = value.asString().strip();
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeException e) {
            try {
                return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
            } catch (DateTimeException ignored) {
                return null;
            }
        }

    }

    private static PoliteFetchException malformed(CompanySource source, String reason) {

        return new PoliteFetchException(FetchFailureCode.PARSE, source.key() + " 응답을 읽지 못했다: " + reason);

    }
}
