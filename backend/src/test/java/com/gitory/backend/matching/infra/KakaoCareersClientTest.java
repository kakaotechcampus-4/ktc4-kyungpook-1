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
class KakaoCareersClientTest {

    private static final String LIST = "/public/api/job-list?part=TECHNOLOGY&company=KAKAO";

    private final KakaoCareersClient client = new KakaoCareersClient();

    private FakeCareersSite site;
    private CompanySource source;

    @BeforeEach
    void startSite() {

        site = FakeCareersSite.start();
        source = new CompanySource("kakao", "카카오", CompanySourceType.KAKAO, site.base() + LIST, null);

    }

    @AfterEach
    void stopSite() {

        site.close();

    }

    @Test
    @DisplayName("목록에서 공고 id·제목·주소·근무지·수정 시각·마감을 읽고, 본문은 소개·업무·자격 칸의 HTML 을 글로 바꿔 잇는다")
    void readsFieldsAndBodyFromList() {

        site.put(LIST + "&page=1", page(1, job("P-14324", " Data Scientist (경력) ", "2026-11-30T23:59:59")));

        assertThat(client.list(FakeCareersSite.session(), source)).containsExactly(new FetchedPosting("P-14324",
                "Data Scientist (경력)", site.base() + "/jobs/P-14324", "판교", Instant.parse("2026-10-14T07:57:30Z"),
                Instant.parse("2026-11-30T14:59:59Z"), "데이터 조직입니다.\n< 모델 개발 >\n- 추천 모델\n- Python 3년 이상\n- Spark 경험"));

    }

    @Test
    @DisplayName("설정한 회사(company=KAKAO) 목록 주소에 쪽 번호만 붙여 마지막 쪽까지 읽고, 상세는 요청하지 않는다")
    void readsEveryPageOfConfiguredCompanyOnly() {

        site.put(LIST + "&page=1", page(2, job("P-1", "Server Developer", null)));
        site.put(LIST + "&page=2", page(2, job("P-2", "Android Developer", null)));

        assertThat(client.list(FakeCareersSite.session(), source)).extracting(FetchedPosting::externalId)
                .containsExactly("P-1", "P-2");
        assertThat(site.hits()).containsExactly("GET " + LIST + "&page=1", "GET " + LIST + "&page=2");

    }

    @Test
    @DisplayName("비공개이거나 마감 표시가 된 공고는 건너뛴다")
    void skipsPrivateAndClosedJobs() {

        site.put(LIST + "&page=1", page(1, job("P-1", "Server Developer", null).replace("\"privateFlag\":false", "\"privateFlag\":true"),
                job("P-2", "Server Developer", null).replace("\"closeFlag\":false", "\"closeFlag\":true"),
                job("P-3", "Server Developer", null)));

        assertThat(client.list(FakeCareersSite.session(), source)).extracting(FetchedPosting::externalId).containsExactly("P-3");

    }

    @Test
    @DisplayName("쪽 수가 상한 20쪽을 넘으면 뒤 쪽 공고가 사라짐으로 바뀌지 않게 PARSE 로 실패한다")
    void tooManyPagesFailsWithParse() {

        for (int page = 1; page <= 20; page++) {
            site.put(LIST + "&page=" + page, page(21, job("P-" + page, "Server Developer", null)));
        }

        Throwable thrown = catchThrowable(() -> client.list(FakeCareersSite.session(), source));

        assertThat(thrown).isInstanceOfSatisfying(PoliteFetchException.class,
                e -> assertThat(e.code()).isEqualTo(FetchFailureCode.PARSE));
        assertThat(site.hits()).hasSize(20);

    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("목록 모양이 다르면 일부만 건너뛰지 않고 회사 전체를 PARSE 로 실패시킨다")
    @ValueSource(strings = {
            "<html>점검 중</html>",
            "{\"jobList\":[],\"totalJobCount\":0}",
            "{\"jobList\":{},\"totalPage\":1}",
            "{\"jobList\":[{\"jobOfferTitle\":\"Server Developer\"}],\"totalPage\":1}",
            "{\"jobList\":[{\"realId\":\"14324\",\"jobOfferTitle\":\"Server Developer\"}],\"totalPage\":1}"
    })
    void unreadableListFailsWithParse(String body) {

        site.put(LIST + "&page=1", body);

        Throwable thrown = catchThrowable(() -> client.list(FakeCareersSite.session(), source));

        assertThat(thrown).isInstanceOfSatisfying(PoliteFetchException.class,
                e -> assertThat(e.code()).isEqualTo(FetchFailureCode.PARSE));

    }

    private static String page(int totalPage, String... jobs) {

        return "{\"jobList\":[" + String.join(",", jobs) + "],\"totalJobCount\":" + jobs.length + ",\"totalPage\":" + totalPage + "}";

    }

    private static String job(String realId, String title, String endDate) {

        return "{\"realId\":\"" + realId + "\",\"privateFlag\":false,\"closeFlag\":false,\"jobOfferTitle\":\"" + title
                + "\",\"uptDate\":\"2026-10-14T16:57:30\",\"endDate\":" + (endDate == null ? "null" : "\"" + endDate + "\"")
                + ",\"introduction\":\"데이터 조직입니다.\",\"workContentDesc\":\"&lt; 모델 개발 &gt;<br/>- 추천 모델\","
                + "\"qualification\":\"- Python 3년 이상<br/>- Spark 경험\",\"jobOfferProcessDesc\":\"서류전형 &gt; 면접\","
                + "\"locationName\":\"판교\"}";

    }
}
