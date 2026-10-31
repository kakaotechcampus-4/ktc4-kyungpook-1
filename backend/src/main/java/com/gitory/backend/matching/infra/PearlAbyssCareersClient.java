package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.DevelopmentPostingFilter;
import com.gitory.backend.matching.domain.FetchedPosting;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 펄어비스 채용 페이지가 서버에서 그린 HTML 에서 공고 목록을 읽고, 개발 공고만 상세 페이지의 본문을 읽는다
 * 목록이 한 쪽에 다 나오지 않으면 빠진 공고가 사라짐으로 바뀌므로, 분류 '전체' 의 개수와 읽은 공고 수가 다르면 PARSE 로 실패한다
 */
@Component
public class PearlAbyssCareersClient {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Pattern OPENING_NO = Pattern.compile("[?&]_jobOpeningNo=(\\d+)");
    private static final Pattern END_DATE = Pattern.compile("~\\s*(\\d{4}-\\d{2}-\\d{2})");
    private static final String DETAIL_PATH = "/ko-KR/Company/Careers/detail?_jobOpeningNo=";
    // 한국어 채용 페이지의 오피스는 국내 사옥 이름(홈 원 등)이라 한국 근무로 읽히게 앞에 붙인다
    private static final String COUNTRY = "대한민국";
    private static final int MAX_TITLE_LENGTH = 255;

    public List<FetchedPosting> list(PoliteFetchSession session, CompanySource source) {

        URI listUrl = URI.create(source.board());
        String origin = listUrl.getScheme() + "://" + listUrl.getRawAuthority();
        Document page = Jsoup.parse(session.get(listUrl));
        Elements items = page.select("a.career_list_item");
        Element total = page.selectFirst(".js-jobGroupSearch[data-jobgroupcode=0] .count");
        if (total == null || !total.text().strip().equals(String.valueOf(items.size()))) {
            throw malformed(source, "분류 '전체' 개수가 없거나 읽은 공고 수 " + items.size() + " 와 다르다");
        }
        List<FetchedPosting> postings = new ArrayList<>();
        for (Element item : items) {
            FetchedPosting listed = listedPosting(item, origin, source);
            // 상세 요청은 한 건마다 1초 이상 걸리므로 개발 공고에만 쓴다
            postings.add(DevelopmentPostingFilter.accepts(listed)
                    ? PostingDetails.withText(listed, source, () -> detailText(session, listed, source))
                    : listed);
        }
        return postings;

    }

    private static FetchedPosting listedPosting(Element item, String origin, CompanySource source) {

        Matcher no = OPENING_NO.matcher(item.attr("href"));
        Element titleElement = item.selectFirst(".cell_title .title");
        String title = titleElement == null ? "" : titleElement.text().strip();
        if (!no.find() || title.isEmpty()) {
            throw malformed(source, "_jobOpeningNo·제목이 없는 공고가 있다");
        }
        Element office = item.selectFirst(".cell_title .office");
        String location = office == null || office.text().isBlank() ? COUNTRY : COUNTRY + " " + office.text().strip();
        return new FetchedPosting(no.group(1), title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title,
                origin + DETAIL_PATH + no.group(1), location, null, endDateOf(item), "");

    }

    // 기간이 '시작 ~ 끝' 날짜로 오면 끝 날 끝까지를 마감으로 보고, 상시채용·인재풀은 마감 없음이다
    private static Instant endDateOf(Element item) {

        Element period = item.selectFirst(".cell_title .full_day");
        Matcher end = END_DATE.matcher(period == null ? "" : period.text());
        if (!end.find()) {
            return null;
        }
        try {
            return LocalDate.parse(end.group(1)).plusDays(1).atStartOfDay(SEOUL).toInstant();
        } catch (DateTimeException e) {
            return null;
        }

    }

    private static String detailText(PoliteFetchSession session, FetchedPosting listed, CompanySource source) {

        Element content = Jsoup.parse(session.get(URI.create(listed.sourceUrl()))).selectFirst("div.content.js-fixedEndPoint");
        if (content == null) {
            throw malformed(source, "공고 " + listed.externalId() + " 의 본문 영역이 없다");
        }
        return PostingTextConverter.fromHtml(content.html());

    }

    private static PoliteFetchException malformed(CompanySource source, String reason) {

        return new PoliteFetchException(FetchFailureCode.PARSE, source.key() + " 페이지를 읽지 못했다: " + reason);

    }
}
