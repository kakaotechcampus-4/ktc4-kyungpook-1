package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.DevelopmentPostingFilter;
import com.gitory.backend.matching.domain.FetchedPosting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

/** 응답 모양은 2026-10-22 에 받은 실제 목록·상세 JSON 에서 쓰는 칸만 남겨 줄였다 */
@Timeout(30)
class LineCareersClientTest {

    private static final String LIST_PATH = "/page-data/ko/jobs/page-data.json";

    private final LineCareersClient client = new LineCareersClient();

    private FakeCareersSite site;
    private CompanySource source;

    @BeforeEach
    void startSite() {

        site = FakeCareersSite.start();
        source = new CompanySource("lineplus", "라인플러스", CompanySourceType.LINE, site.base() + LIST_PATH);

    }

    @AfterEach
    void stopSite() {

        site.close();

    }

    @Test
    @DisplayName("목록에서 공고 id·제목·주소·근무 도시·마감을 읽고, 본문은 그 공고의 상세 JSON 에 있는 HTML 을 글로 바꿔 채운다")
    void readsFieldsFromListAndTextFromDetail() {

        site.put(LIST_PATH, list(job(3125, "LINE SRE Dev Engineer ", "Bundang", "2026-10-31T14:59:00.000Z", true)));
        site.put(detailPath(3125), detail("<h3>자격 요건</h3><ul><li>Kubernetes &amp; Linux</li></ul>"));

        assertThat(client.list(FakeCareersSite.session(), source)).containsExactly(new FetchedPosting("3125",
                "LINE SRE Dev Engineer", site.base() + "/ko/jobs/3125", "Bundang", null,
                Instant.parse("2026-10-31T14:59:00Z"), "자격 요건\nKubernetes & Linux"));

    }

    @Test
    @DisplayName("한국 개발 공고에만 상세를 요청하고, 해외 도시나 개발이 아닌 공고는 상세를 부르지 않으며 개발 공고 거르기에서도 빠진다")
    void requestsDetailOnlyForKoreanDevelopmentPostings() {

        site.put(LIST_PATH, list(job(1, "Backend Software Engineer", "Gwacheon", null, true),
                job(2, "Backend Software Engineer", "Taipei", null, true),
                job(3, "Legal Specialist", "Bundang", null, true)));
        site.put(detailPath(1), detail("<p>본문</p>"));

        List<FetchedPosting> postings = client.list(FakeCareersSite.session(), source);

        assertThat(postings).extracting(FetchedPosting::externalId).containsExactly("1", "2", "3");
        assertThat(postings).filteredOn(DevelopmentPostingFilter::accepts).extracting(FetchedPosting::externalId)
                .containsExactly("1");
        assertThat(site.hits()).containsExactly("GET " + LIST_PATH, "GET " + detailPath(1));

    }

    @Test
    @DisplayName("게시하지 않은 공고는 건너뛴다")
    void skipsUnpublishedJobs() {

        site.put(LIST_PATH, list(job(1, "Backend Software Engineer", "Seoul", null, false)));

        assertThat(client.list(FakeCareersSite.session(), source)).isEmpty();
        assertThat(site.hits()).containsExactly("GET " + LIST_PATH);

    }

    @ParameterizedTest(name = "상태 {0}")
    @DisplayName("상세 하나를 못 읽으면 그 공고만 본문 없이 넘기고 다음 공고의 상세는 그대로 읽는다")
    @ValueSource(ints = {404, 500})
    void skipsOnlyTheFailedDetail(int status) {

        site.put(LIST_PATH, list(job(1, "Backend Software Engineer", "Seoul", null, true),
                job(2, "LINE Pay Server Engineer", "Seoul", null, true)));
        site.put(detailPath(1), "");
        site.fail(detailPath(1), status);
        site.put(detailPath(2), detail("<p>둘째 본문</p>"));

        assertThat(client.list(FakeCareersSite.session(), source))
                .extracting(FetchedPosting::externalId, FetchedPosting::text)
                .containsExactly(tuple("1", ""), tuple("2", "둘째 본문"));

    }

    @Test
    @DisplayName("상세 JSON 에 본문 칸이 없어도 그 공고만 본문 없이 넘긴다")
    void detailWithoutContentSkipsOnlyThatPosting() {

        site.put(LIST_PATH, list(job(1, "Backend Software Engineer", "Seoul", null, true)));
        site.put(detailPath(1), "{\"result\":{\"data\":{\"strapiJobs\":{}}}}");

        assertThat(client.list(FakeCareersSite.session(), source)).singleElement()
                .satisfies(posting -> assertThat(posting.text()).isEmpty());

    }

    @Test
    @DisplayName("상세에서 요청이 너무 많다는 429 를 받으면 다음 상세를 보내지 않고 회사 전체를 실패시킨다")
    void tooManyRequestsOnDetailFailsWholeCompany() {

        site.put(LIST_PATH, list(job(1, "Backend Software Engineer", "Seoul", null, true),
                job(2, "LINE Pay Server Engineer", "Seoul", null, true)));
        site.put(detailPath(1), "");
        site.fail(detailPath(1), 429);
        site.put(detailPath(2), detail("<p>본문</p>"));

        Throwable thrown = catchThrowable(() -> client.list(FakeCareersSite.session(), source));

        assertThat(thrown).isInstanceOfSatisfying(PoliteFetchException.class,
                e -> assertThat(e.code()).isEqualTo(FetchFailureCode.HTTP_429));
        assertThat(site.hits()).doesNotContain("GET " + detailPath(2));

    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("목록 모양이 다르면 일부만 건너뛰지 않고 회사 전체를 PARSE 로 실패시킨다")
    @ValueSource(strings = {
            "<html>점검 중</html>",
            "{\"result\":{\"data\":{}}}",
            "{\"result\":{\"data\":{\"allStrapiJobs\":{\"edges\":[{\"node\":{\"publish\":true,\"title\":\"Backend Software Engineer\"}}]}}}}",
            "{\"result\":{\"data\":{\"allStrapiJobs\":{\"edges\":[{\"node\":{\"publish\":true,\"strapiId\":1,\"title\":\"\"}}]}}}}"
    })
    void unreadableListFailsWithParse(String body) {

        site.put(LIST_PATH, body);

        Throwable thrown = catchThrowable(() -> client.list(FakeCareersSite.session(), source));

        assertThat(thrown).isInstanceOfSatisfying(PoliteFetchException.class,
                e -> assertThat(e.code()).isEqualTo(FetchFailureCode.PARSE));

    }

    private static String detailPath(long id) {

        return "/page-data/ko/jobs/" + id + "/page-data.json";

    }

    private static String list(String... jobs) {

        StringBuilder edges = new StringBuilder();
        for (String job : jobs) {
            edges.append(edges.isEmpty() ? "" : ",").append("{\"node\":").append(job).append('}');
        }
        return "{\"result\":{\"data\":{\"allStrapiJobs\":{\"edges\":[" + edges + "]}}}}";

    }

    private static String job(long id, String title, String city, String endDate, boolean publish) {

        return "{\"publish\":" + publish + ",\"strapiId\":" + id + ",\"title\":\"" + title + "\",\"end_date\":"
                + (endDate == null ? "null" : "\"" + endDate + "\"") + ",\"until_filled\":false,"
                + "\"cities\":[{\"name\":\"" + city + "\",\"id\":2,\"country\":1}]}";

    }

    private static String detail(String html) {

        return "{\"result\":{\"data\":{\"strapiJobs\":{\"strapiId\":1,\"content\":\"" + html + "\"}}}}";

    }
}
