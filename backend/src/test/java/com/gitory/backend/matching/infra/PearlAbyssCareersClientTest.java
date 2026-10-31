package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.FetchedPosting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

/** 페이지 모양은 2026-10-22 에 받은 실제 목록·상세 HTML 에서 쓰는 요소만 남겨 줄였다 */
@Timeout(30)
class PearlAbyssCareersClientTest {

    private static final String LIST_PATH = "/ko-KR/Company/Careers/List";
    private static final String DETAIL = "/ko-KR/Company/Careers/detail?_jobOpeningNo=";

    private final PearlAbyssCareersClient client = new PearlAbyssCareersClient();

    private FakeCareersSite site;
    private CompanySource source;

    @BeforeEach
    void startSite() {

        site = FakeCareersSite.start();
        source = new CompanySource("pearlabyss", "펄어비스", CompanySourceType.PEARL_ABYSS, site.base() + LIST_PATH, null);

    }

    @AfterEach
    void stopSite() {

        site.close();

    }

    @Test
    @DisplayName("목록에서 공고 번호·제목·주소·오피스·마감을 읽고, 본문은 상세 페이지 본문 영역의 HTML 을 글로 바꿔 채운다")
    void readsFieldsFromListAndTextFromDetail() {

        site.put(LIST_PATH, list(item(6, " 게임 엔진 프로그래머 모집 ", "2026-10-08 ~ 2026-10-23", "홈 원")));
        site.put(DETAIL + 6, detail("<p><strong>담당업무</strong></p><ul><li>게임 엔진 개발 (C++)</li></ul>"));

        assertThat(client.list(FakeCareersSite.session(), source)).containsExactly(new FetchedPosting("6",
                "게임 엔진 프로그래머 모집", site.base() + DETAIL + 6, "대한민국 홈 원", null,
                Instant.parse("2026-10-23T15:00:00Z"), "담당업무\n게임 엔진 개발 (C++)"));

    }

    @Test
    @DisplayName("개발 공고에만 상세를 요청하고, 상시채용·인재풀 공고는 마감 없음이다")
    void requestsDetailOnlyForDevelopmentPostings() {

        site.put(LIST_PATH, list(item(6, "게임 엔진 프로그래머 모집", "상시채용", "홈 원"),
                item(855, "[경력] 치과위생사 모집", "상시채용", "홈 원"), item(427, "인재 Pool", "인재풀", "홈 원")));
        site.put(DETAIL + 6, detail("<p>본문</p>"));

        assertThat(client.list(FakeCareersSite.session(), source)).extracting(FetchedPosting::externalId, FetchedPosting::expiresAt)
                .containsExactly(tuple("6", null), tuple("855", null), tuple("427", null));
        assertThat(site.hits()).containsExactly("GET " + LIST_PATH, "GET " + DETAIL + 6);

    }

    @Test
    @DisplayName("상세 하나를 못 읽거나 본문 영역이 없으면 그 공고만 본문 없이 넘기고 다음 상세는 그대로 읽는다")
    void skipsOnlyTheFailedDetail() {

        site.put(LIST_PATH, list(item(6, "게임 엔진 프로그래머 모집", "상시채용", "홈 원"),
                item(7, "게임플레이 프로그래머 모집", "상시채용", "홈 원"), item(562, "검은사막 게임플레이 프로그래머 모집", "상시채용", "홈 원")));
        site.put(DETAIL + 6, "");
        site.fail(DETAIL + 6, 500);
        site.put(DETAIL + 7, "<html><body><div class=\"content\">본문 영역 아님</div></body></html>");
        site.put(DETAIL + 562, detail("<p>셋째 본문</p>"));

        assertThat(client.list(FakeCareersSite.session(), source)).extracting(FetchedPosting::externalId, FetchedPosting::text)
                .containsExactly(tuple("6", ""), tuple("7", ""), tuple("562", "셋째 본문"));

    }

    @Test
    @DisplayName("본문이 이미지뿐이라 글이 없어도 공고는 빈 본문으로 넘긴다")
    void keepsImageOnlyPosting() {

        site.put(LIST_PATH, list(item(6, "게임 엔진 프로그래머 모집", "상시채용", "홈 원")));
        site.put(DETAIL + 6, detail("<img src=\"https://www.pearlabyss.com/Company/Careers/FileLoad/1.jpg\"><br>"));

        assertThat(client.list(FakeCareersSite.session(), source)).singleElement()
                .satisfies(posting -> assertThat(posting.text()).isEmpty());

    }

    @Test
    @DisplayName("분류 '전체' 개수와 읽은 공고 수가 다르면 한 쪽에 다 나오지 않은 것으로 보고 PARSE 로 실패한다")
    void countMismatchFailsWithParse() {

        site.put(LIST_PATH, page(5, item(6, "게임 엔진 프로그래머 모집", "상시채용", "홈 원")));

        assertThat(failureCodeOf()).isEqualTo(FetchFailureCode.PARSE);

    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("목록 모양이 다르면 일부만 건너뛰지 않고 회사 전체를 PARSE 로 실패시킨다")
    @ValueSource(strings = {
            "<html><body>잠시만 기다려 주세요</body></html>",
            "<html><body><button class=\"js-jobGroupSearch\" data-jobgroupcode=\"0\"><span class=\"count\">1</span></button>"
                    + "<a href=\"/Company/Careers/detail\" class=\"career_list_item\"><div class=\"cell_title\"><p class=\"title\">공고</p></div></a></body></html>",
            "<html><body><button class=\"js-jobGroupSearch\" data-jobgroupcode=\"0\"><span class=\"count\">1</span></button>"
                    + "<a href=\"/Company/Careers/detail?_jobOpeningNo=6\" class=\"career_list_item\"><div class=\"cell_title\"></div></a></body></html>"
    })
    void unreadableListFailsWithParse(String body) {

        site.put(LIST_PATH, body);

        assertThat(failureCodeOf()).isEqualTo(FetchFailureCode.PARSE);

    }

    private FetchFailureCode failureCodeOf() {

        Throwable thrown = catchThrowable(() -> client.list(FakeCareersSite.session(), source));
        assertThat(thrown).isInstanceOf(PoliteFetchException.class);
        return ((PoliteFetchException) thrown).code();

    }

    private static String list(String... items) {

        return page(items.length, items);

    }

    private static String page(int total, String... items) {

        return "<html><body><ul><li class=\"item\"><button type=\"button\" class=\"btn_category js-jobGroupSearch\" data-jobgroupcode=\"0\">"
                + "<span class=\"title\">전체</span><span class=\"count roboto\">" + total + "</span></button></li></ul>"
                + "<div class=\"career_list_body\">" + String.join("", items) + "</div></body></html>";

    }

    private static String item(long no, String title, String period, String office) {

        return "<a href=\"/Company/Careers/detail?_jobOpeningNo=" + no + "\" class=\"career_list_item\">"
                + "<div class=\"list_category cell_title\"><p class=\"title\">" + title + "</p>"
                + "<p class=\"date\"><span class=\"full_day\">" + period + "</span></p>"
                + "<div class=\"info\"><span class=\"date\">" + period + "</span>"
                + "<span class=\"office spr_careers_after spr_career_place\">" + office + "</span></div></div></a>";

    }

    private static String detail(String html) {

        return "<html><body><aside>지원하기</aside><div class=\"content js-fixedEndPoint\">" + html + "</div>"
                + "<div class=\"policy_editor_content\">개인정보 처리방침</div></body></html>";

    }
}
