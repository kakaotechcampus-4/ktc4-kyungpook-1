package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.FetchedPosting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

/** 응답 모양은 2026-10-24 에 받은 실제 목록·상세에서 쓰는 칸만 남겨 줄였다 */
@Timeout(30)
class RocketpunchClientTest {

    private static final String KEY = "rp-test-key-123";
    private static final String LIST = "/v1/jobs?jobCategories=dev&jobCategories=dataAi&jobCategories=engineer&pageSize=50&page=";
    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");

    private FakeCareersSite site;
    private CompanySource source;

    @BeforeEach
    void startSite() {

        site = FakeCareersSite.start();
        source = new CompanySource("rocketpunch", "로켓펀치", CompanySourceType.ROCKETPUNCH, site.base(), null);

    }

    @AfterEach
    void stopSite() {

        site.close();

    }

    @Test
    @DisplayName("목록을 마지막 쪽까지 읽고, 공고 id·제목·로켓펀치 주소·회사 이름과 시간대 없이 적힌 UTC 마감을 읽는다")
    void readsEveryPageWithCompanyNames() {

        site.put(LIST + 1, page(2, item(11, "마케팅 매니저", "nudge", "넛지헬스케어", "\"2026-11-30T14:59\"")));
        site.put(LIST + 2, page(2, item(12, "영업 담당자", "scatter", "스캐터랩", "null")));

        assertThat(client(KEY).list(FakeCareersSite.session(), source)).containsExactly(
                new FetchedPosting("11", "마케팅 매니저", "https://www.rocketpunch.com/jobs/11", "대한민국", null,
                        Instant.parse("2026-11-30T14:59:00Z"), "", List.of(), "넛지헬스케어"),
                new FetchedPosting("12", "영업 담당자", "https://www.rocketpunch.com/jobs/12", "대한민국", null,
                        null, "", List.of(), "스캐터랩"));
        assertThat(site.hits()).containsExactly("GET " + LIST + 1, "GET " + LIST + 2);

    }

    @Test
    @DisplayName("건너뛸 회사 id 목록에 있는 회사의 공고는 넘기지 않고 상세도 요청하지 않는다")
    void skipsConfiguredCompanies() {

        site.put(LIST + 1, page(1, item(11, "백엔드 개발자", "estgames", "이스트게임즈", "null"),
                item(12, "마케팅 매니저", "nudge", "넛지헬스케어", "null")));

        assertThat(client(KEY, "estgames").list(FakeCareersSite.session(), source))
                .extracting(FetchedPosting::externalId).containsExactly("12");
        assertThat(site.hits()).containsExactly("GET " + LIST + 1);

    }

    @Test
    @DisplayName("개발 공고에만 상세를 요청하고, 본문은 상세 내용·주요 업무·자격 요건·우대 사항을 이어 붙인 글이다")
    void requestsDetailOnlyForDevelopmentPostings() {

        site.put(LIST + 1, page(1, item(11, "백엔드 개발자", "acme", "에이크미", "null"),
                item(12, "마케팅 매니저", "acme", "에이크미", "null")));
        site.put("/v1/jobs/11", detail(true, "null", "\"결제 서비스를 만듭니다\"", "\"<ul><li>API 개발</li><li>운영</li></ul>\"",
                "\"Java 3년 이상\\n Spring 경험\"", "null"));

        List<FetchedPosting> postings = client(KEY).list(FakeCareersSite.session(), source);

        assertThat(postings).extracting(FetchedPosting::externalId, FetchedPosting::text).containsExactly(
                tuple("11", "결제 서비스를 만듭니다\n주요 업무\nAPI 개발\n운영\n자격 요건\nJava 3년 이상\nSpring 경험"),
                tuple("12", ""));
        assertThat(site.hits()).containsExactly("GET " + LIST + 1, "GET /v1/jobs/11");

    }

    @Test
    @DisplayName("상세에서 hiring 이 false 이거나 마감이 지난 공고, 목록에서 마감이 지난 공고는 넘기지 않는다")
    void dropsClosedPostings() {

        site.put(LIST + 1, page(1, item(11, "백엔드 개발자", "a", "에이", "null"), item(12, "서버 개발자", "b", "비", "null"),
                item(13, "프론트엔드 개발자", "c", "씨", "\"2026-10-01T23:59:59\""), item(14, "iOS 개발자", "d", "디", "null")));
        site.put("/v1/jobs/11", detail(false, "null", "\"본문\"", "null", "null", "null"));
        site.put("/v1/jobs/12", detail(true, "\"2026-10-20T00:00:00Z\"", "\"본문\"", "null", "null", "null"));
        site.put("/v1/jobs/14", detail(true, "\"2026-12-01T00:00:00Z\"", "\"본문\"", "null", "null", "null"));

        assertThat(client(KEY).list(FakeCareersSite.session(), source))
                .extracting(FetchedPosting::externalId, FetchedPosting::expiresAt)
                .containsExactly(tuple("14", Instant.parse("2026-12-01T00:00:00Z")));

    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("목록 모양이 다르면 일부만 건너뛰지 않고 로켓펀치 전체를 PARSE 로 실패시킨다")
    @ValueSource(strings = {
            "<html>점검 중</html>",
            "{\"totalPages\":1}",
            "{\"totalPages\":999,\"items\":[]}",
            "{\"totalPages\":1,\"items\":[{\"title\":\"백엔드 개발자\",\"webUrl\":\"https://www.rocketpunch.com/jobs/1\"}]}",
            "{\"totalPages\":1,\"items\":[{\"jobId\":1,\"title\":\"백엔드 개발자\",\"webUrl\":\"http://evil.example/1\"}]}"
    })
    void unreadableListFailsWithParse(String body) {

        site.put(LIST + 1, body);

        assertThat(failureOf(client(KEY)).code()).isEqualTo(FetchFailureCode.PARSE);

    }

    @Test
    @DisplayName("상세 하나가 404 이거나 모양이 다르면 그 공고만 본문 없이 넘기고 다른 공고의 상세는 그대로 읽는다")
    void skipsOnlyTheUnreadableDetail() {

        site.put(LIST + 1, page(1, item(11, "백엔드 개발자", "a", "에이", "null"), item(12, "서버 개발자", "b", "비", "null"),
                item(13, "iOS 개발자", "c", "씨", "null")));
        site.put("/v1/jobs/12", "{\"title\":\"hiring 칸 없음\"}");
        site.put("/v1/jobs/13", detail(true, "null", "\"Swift 경험\"", "null", "null", "null"));

        assertThat(client(KEY).list(FakeCareersSite.session(), source))
                .extracting(FetchedPosting::externalId, FetchedPosting::text)
                .containsExactly(tuple("11", ""), tuple("12", ""), tuple("13", "Swift 경험"));

    }

    @Test
    @DisplayName("요청마다 X-RP-API-Key 헤더에 키를 싣고, 실패해도 예외 메시지에 키를 남기지 않는다")
    void sendsKeyHeaderAndKeepsItOutOfErrors() {

        site.fail(LIST + 1, 401);

        PoliteFetchException failure = failureOf(client(KEY));

        assertThat(failure.code()).isEqualTo(FetchFailureCode.HTTP_STATUS);
        assertThat(failure.getMessage()).doesNotContain(KEY);
        assertThat(site.headerValues("X-RP-API-Key")).containsExactly(KEY);

    }

    @ParameterizedTest(name = "[{0}]")
    @DisplayName("키가 비어 있거나 공백뿐이면 로켓펀치를 부를 준비가 되지 않은 것으로 본다")
    @ValueSource(strings = {"", "   "})
    void blankKeyIsNotConfigured(String key) {

        assertThat(client(key).isConfigured()).isFalse();
        assertThat(client(KEY).isConfigured()).isTrue();

    }

    private PoliteFetchException failureOf(RocketpunchClient client) {

        Throwable thrown = catchThrowable(() -> client.list(FakeCareersSite.session(), source));
        assertThat(thrown).isInstanceOf(PoliteFetchException.class);
        return (PoliteFetchException) thrown;

    }

    private static RocketpunchClient client(String key, String... skipCompanyIds) {

        MatchingProperties properties = new MatchingProperties(List.of(), null,
                new RocketpunchProperties(key, List.of(skipCompanyIds)));
        return new RocketpunchClient(properties, Clock.fixed(NOW, ZoneOffset.UTC));

    }

    private static String page(int totalPages, String... items) {

        return "{\"totalItems\":" + items.length + ",\"totalPages\":" + totalPages + ",\"page\":1,\"pageSize\":50,\"items\":["
                + String.join(",", items) + "]}";

    }

    private static String item(long id, String title, String companyId, String companyName, String endAt) {

        return "{\"jobId\":" + id + ",\"title\":\"" + title + "\",\"jobCategory\":\"dev\",\"company\":{\"id\":\"" + companyId
                + "\",\"name\":\"" + companyName + "\"},\"endAt\":" + endAt + ",\"webUrl\":\"https://www.rocketpunch.com/jobs/" + id + "\"}";

    }

    private static String detail(boolean hiring, String endAt, String description, String duty, String minimum,
                                 String preferred) {

        return "{\"status\":\"published\",\"hiring\":" + hiring + ",\"endAt\":" + endAt + ",\"description\":" + description
                + ",\"duty\":" + duty + ",\"minimumQualification\":" + minimum + ",\"preferredQualification\":" + preferred
                + ",\"company\":{\"id\":\"acme\",\"name\":\"에이크미\"}}";

    }
}
