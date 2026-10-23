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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KT 그룹 채용 사이트의 공고 API 에서 진행 중인 공고를 본문과 함께 읽어, 상세를 따로 요청하지 않는다
 * 공채 하나에 모집 분야가 여럿이고 직무는 분야 이름에만 있어, 분야 하나를 공고 하나로 나눠 개발 공고 거르기가 분야로 판단하게 한다
 */
@Component
public class KtCareersClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    // KT 그룹사 공고만 올라오는 국내 채용 사이트라 근무지 칸이 없어도 한국으로 본다
    private static final String LOCATION = "대한민국";
    private static final int MAX_TITLE_LENGTH = 255;
    private static final Pattern PAGE_SIZE = Pattern.compile("[?&]pageSize=(\\d{1,4})(&|$)");

    public List<FetchedPosting> list(PoliteFetchSession session, CompanySource source) {

        JsonNode body = parse(session.get(URI.create(source.board())), source);
        JsonNode notices = body.path("data");
        if (!body.path("isSuccess").asBoolean(false) || !notices.isArray()) {
            throw malformed(source, "isSuccess 가 아니거나 data 배열이 없다");
        }
        // 응답에 전체 개수가 없어, 한 쪽을 꽉 채웠으면 뒤 쪽 공고가 사라짐으로 바뀌지 않게 회사 전체를 실패시킨다
        if (notices.size() >= pageSizeOf(source)) {
            throw malformed(source, "공고 " + notices.size() + "건이 한 쪽을 채워 뒤 쪽이 있을 수 있다");
        }
        List<FetchedPosting> postings = new ArrayList<>();
        for (JsonNode notice : notices) {
            if (notice.path("isPost").isBoolean() && !notice.path("isPost").asBoolean()) {
                continue;
            }
            postings.addAll(postingsOf(notice, source));
        }
        return postings;

    }

    private static List<FetchedPosting> postingsOf(JsonNode notice, CompanySource source) {

        JsonNode id = notice.path("recruitNoticeSn");
        String name = textOf(notice.path("recruitNoticeName"));
        String url = textOf(notice.path("recruitNoticeUrl"));
        if (!id.isIntegralNumber() || id.asLong() <= 0 || name.isEmpty() || !url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw malformed(source, "recruitNoticeSn·recruitNoticeName·recruitNoticeUrl 이 없거나 알맞지 않은 공고가 있다");
        }
        // 본문 대부분이 이미지 한 장이라 글이 비어도 공고는 넘기고, 이미지 글 읽기는 다음 이슈에서 한다
        String text = PostingTextConverter.fromHtml(textOf(notice.path("contents")));
        Instant expiresAt = instantOf(notice.path("receiveEndDatetime"));
        List<FetchedPosting> postings = new ArrayList<>();
        for (JsonNode sector : notice.path("recruitSectorList")) {
            JsonNode sectorId = sector.path("recruitSectorSn");
            String sectorName = textOf(sector.path("recruitSectorName"));
            if (!sectorId.isIntegralNumber() || sectorName.isEmpty()) {
                throw malformed(source, "공고 " + id.asLong() + " 의 모집 분야에 recruitSectorSn·recruitSectorName 이 없다");
            }
            postings.add(new FetchedPosting(id.asLong() + "-" + sectorId.asLong(), titleOf(name + " - " + sectorName), url,
                    LOCATION, null, expiresAt, text));
        }
        if (postings.isEmpty()) {
            postings.add(new FetchedPosting(String.valueOf(id.asLong()), titleOf(name), url, LOCATION, null, expiresAt, text));
        }
        return postings;

    }

    private static int pageSizeOf(CompanySource source) {

        Matcher size = PAGE_SIZE.matcher(source.board());
        if (!size.find()) {
            throw malformed(source, "board 주소에 pageSize 가 없다");
        }
        return Integer.parseInt(size.group(1));

    }

    private static String titleOf(String title) {

        return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title;

    }

    // 시각에 시간대가 없어 서울 시각으로 보고, 읽지 못하는 값은 없는 것으로 둔다
    private static Instant instantOf(JsonNode value) {

        if (!value.isString()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.asString().strip(), DATE_TIME).atZone(SEOUL).toInstant();
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
