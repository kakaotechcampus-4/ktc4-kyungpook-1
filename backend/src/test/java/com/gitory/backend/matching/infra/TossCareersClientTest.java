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

/** 응답 모양은 2026-10-22 에 받은 실제 목록에서 쓰는 칸만 남겨 줄였다 */
@Timeout(30)
class TossCareersClientTest {

    private static final String LIST_PATH = "/api/v3/ipd-eggnog/career/jobs";

    private final TossCareersClient client = new TossCareersClient();

    private FakeCareersSite site;
    private CompanySource source;

    @BeforeEach
    void startSite() {

        site = FakeCareersSite.start();
        source = new CompanySource("toss", "토스", CompanySourceType.TOSS, site.base() + LIST_PATH);

    }

    @AfterEach
    void stopSite() {

        site.close();

    }

    @Test
    @DisplayName("목록에서 공고 id·제목·주소·근무지·수정 시각·접수 마감일을 읽고, 본문은 Job Description 칸의 글을 줄 그대로 쓴다")
    void readsFieldsAndDescriptionFromList() {

        site.put(LIST_PATH, list(job(7797504003L, " Server Developer ", "Seoul",
                description("# 이런 일을 해요\\n- Kotlin 서버 개발") + "," + closingDate("2026-11-01"))));

        assertThat(client.list(FakeCareersSite.session(), source)).containsExactly(new FetchedPosting("7797504003",
                "Server Developer", "https://toss.im/career/job-detail?gh_jid=7797504003", "Seoul",
                Instant.parse("2026-10-11T12:22:59Z"), Instant.parse("2026-11-01T15:00:00Z"),
                "# 이런 일을 해요\n- Kotlin 서버 개발"));

    }

    @Test
    @DisplayName("본문이 목록에 함께 오므로 개발 공고여도 상세 페이지를 요청하지 않는다")
    void neverRequestsDetail() {

        site.put(LIST_PATH, list(job(1, "Server Developer", "Seoul", description("본문")),
                job(2, "Product Designer", "Seoul", description("본문"))));

        client.list(FakeCareersSite.session(), source);

        assertThat(site.hits()).containsExactly("GET " + LIST_PATH);

    }

    @Test
    @DisplayName("커리어 페이지에 노출하지 않는 공고는 건너뛴다")
    void skipsHiddenJobs() {

        site.put(LIST_PATH, list(job(1, "Server Developer", "Seoul",
                "{\"name\":\"커리어페이지 메뉴에 \\\"미노출\\\" 되어야 하는 Job인가요?\",\"value\":true}"),
                job(2, "Android Developer", "Seoul", description("본문"))));

        assertThat(client.list(FakeCareersSite.session(), source)).extracting(FetchedPosting::externalId).containsExactly("2");

    }

    @Test
    @DisplayName("포스터(이미지) 공고처럼 본문 글이 없어도 공고는 빈 본문으로 넘긴다")
    void keepsPostingWithoutDescriptionText() {

        site.put(LIST_PATH, list(job(1, "Android Developer", "Seoul",
                "{\"name\":\"포스터 형태의 JD인가요?\",\"value\":true}," + description(""))));

        assertThat(client.list(FakeCareersSite.session(), source)).singleElement()
                .satisfies(posting -> assertThat(posting.text()).isEmpty());

    }

    @Test
    @DisplayName("접수 마감일 칸이 비었거나 날짜가 아니면 마감 없음으로 둔다")
    void blankOrUnreadableClosingDateMeansNoExpiry() {

        site.put(LIST_PATH, list(job(1, "Server Developer", "Seoul", closingDate("")),
                job(2, "Server Developer", "Seoul", closingDate("곧 마감"))));

        assertThat(client.list(FakeCareersSite.session(), source)).extracting(FetchedPosting::expiresAt)
                .containsExactly(null, null);

    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("목록 모양이 다르면 일부만 건너뛰지 않고 회사 전체를 PARSE 로 실패시킨다")
    @ValueSource(strings = {
            "<html>점검 중</html>",
            "{\"resultType\":\"SUCCESS\",\"success\":{}}",
            "{\"success\":[{\"title\":\"Server Developer\",\"absolute_url\":\"https://toss.im/career/job-detail?gh_jid=1\"}]}",
            "{\"success\":[{\"id\":1,\"title\":\" \",\"absolute_url\":\"https://toss.im/career/job-detail?gh_jid=1\"}]}",
            "{\"success\":[{\"id\":1,\"title\":\"Server Developer\",\"absolute_url\":\"javascript:alert(1)\"}]}"
    })
    void unreadableListFailsWithParse(String body) {

        site.put(LIST_PATH, body);

        Throwable thrown = catchThrowable(() -> client.list(FakeCareersSite.session(), source));

        assertThat(thrown).isInstanceOfSatisfying(PoliteFetchException.class,
                e -> assertThat(e.code()).isEqualTo(FetchFailureCode.PARSE));

    }

    private static String list(String... jobs) {

        return "{\"resultType\":\"SUCCESS\",\"error\":null,\"success\":[" + String.join(",", jobs) + "]}";

    }

    private static String job(long id, String title, String location, String metadata) {

        return "{\"id\":" + id + ",\"title\":\"" + title + "\",\"absolute_url\":\"https://toss.im/career/job-detail?gh_jid=" + id
                + "\",\"location\":{\"name\":\"" + location + "\"},\"updated_at\":\"2026-10-11T08:22:59-04:00\","
                + "\"application_deadline\":null,\"metadata\":[" + metadata + "]}";

    }

    private static String description(String text) {

        return "{\"name\":\"Job Description을 작성해 주세요.(작성 전, 채용 커뮤니케이션 가이드 노션을 꼭 참고해 주세요.)\","
                + "\"value\":\"" + text + "\",\"value_type\":\"long_text\"}";

    }

    private static String closingDate(String date) {

        return "{\"name\":\"커리어페이지 채용공고 클로징 일자 (서류접수 마감일이 정해진 경우)\",\"value\":\"" + date + "\"}";

    }
}
