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

/** 응답 모양은 2026-10-22 에 받은 실제 목록·상세 JSON 에서 쓰는 칸만 남겨 줄였다 */
@Timeout(30)
class SmilegateCareersClientTest {

    private static final String LIST_PATH = "/api/apply/announce/guest";
    private static final String DETAIL = "/api/apply/announce/guest/";

    private final SmilegateCareersClient client = new SmilegateCareersClient();

    private FakeCareersSite site;
    private CompanySource source;

    @BeforeEach
    void startSite() {

        site = FakeCareersSite.start();
        source = new CompanySource("smilegate", "스마일게이트", CompanySourceType.SMILEGATE, site.base() + LIST_PATH, null);

    }

    @AfterEach
    void stopSite() {

        site.close();

    }

    @Test
    @DisplayName("목록을 POST 로 받아 공고 id·제목·주소·마감을 읽고, 본문은 상세의 업무·자격·역량·우대 칸을 이어 채운다")
    void readsFieldsFromPostedListAndTextFromDetail() {

        site.put(LIST_PATH, page(1, announce(6300, " [LOST ARK] 게임 데이터엔지니어 담당 ", "20261031")));
        site.put(DETAIL + 6300, detail("- 데이터 파이프라인 개발", "- Python 3년 이상"));

        assertThat(client.list(FakeCareersSite.session(), source)).containsExactly(new FetchedPosting("6300",
                "[LOST ARK] 게임 데이터엔지니어 담당", site.base() + "/apply/announce/view?seq=6300", "대한민국", null,
                Instant.parse("2026-10-31T15:00:00Z"), "- 데이터 파이프라인 개발\n- Python 3년 이상"));
        assertThat(site.posted()).singleElement().satisfies(body -> assertThat(body).contains("\"pageIndex\":1", "\"pageSize\":100"));

    }

    @Test
    @DisplayName("전체 개수만큼 읽을 때까지 쪽 번호를 올려 POST 하고, 개발 공고에만 상세를 GET 으로 요청한다")
    void postsEveryPageAndRequestsDetailOnlyForDevelopment() {

        site.putInOrder(LIST_PATH, page(2, announce(1, "[퍼블리싱/플랫폼] LLM 엔지니어 담당", "20261031")),
                page(2, announce(2, "[인사] 채용 담당", "20261031")));
        site.put(DETAIL + 1, detail("- 업무", "- 자격"));

        assertThat(client.list(FakeCareersSite.session(), source)).extracting(FetchedPosting::externalId).containsExactly("1", "2");
        assertThat(site.hits()).containsExactly("POST " + LIST_PATH, "GET " + DETAIL + 1, "POST " + LIST_PATH);
        assertThat(site.posted()).extracting(body -> body.contains("\"pageIndex\":2")).containsExactly(false, true);

    }

    @Test
    @DisplayName("비밀번호를 아는 사람에게만 여는 공고는 건너뛴다")
    void skipsPasswordProtectedAnnounces() {

        site.put(LIST_PATH, page(2, announce(1, "[퍼블리싱/플랫폼] LLM 엔지니어 담당", "20261031")
                .replace("\"urlPassword\":null", "\"urlPassword\":\"****\""), announce(2, "[인사] 채용 담당", "20261031")));

        assertThat(client.list(FakeCareersSite.session(), source)).extracting(FetchedPosting::externalId).containsExactly("2");
        assertThat(site.hits()).doesNotContain("GET " + DETAIL + 1);

    }

    @Test
    @DisplayName("상세 하나를 못 읽거나 업무·자격 칸이 없으면 그 공고만 본문 없이 넘긴다")
    void skipsOnlyTheFailedDetail() {

        site.put(LIST_PATH, page(3, announce(1, "LLM 엔지니어", null), announce(2, "데이터 엔지니어", null),
                announce(3, "서버 프로그래머", null)));
        site.put(DETAIL + 1, "");
        site.fail(DETAIL + 1, 500);
        site.put(DETAIL + 2, "{\"announceSeq\":2}");
        site.put(DETAIL + 3, detail("- 서버 개발", ""));

        assertThat(client.list(FakeCareersSite.session(), source)).extracting(FetchedPosting::externalId, FetchedPosting::text)
                .containsExactly(tuple("1", ""), tuple("2", ""), tuple("3", "- 서버 개발"));

    }

    @Test
    @DisplayName("전체 개수보다 적게 읽고 목록이 끝나면 빠진 공고가 사라짐으로 바뀌지 않게 PARSE 로 실패한다")
    void shortListFailsWithParse() {

        site.putInOrder(LIST_PATH, page(5, announce(1, "[인사] 채용 담당", null)), page(5));

        assertThat(failureCodeOf()).isEqualTo(FetchFailureCode.PARSE);

    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("목록 모양이 다르면 일부만 건너뛰지 않고 회사 전체를 PARSE 로 실패시킨다")
    @ValueSource(strings = {
            "<html>점검 중</html>",
            "{\"count\":0,\"announce\":[]}",
            "{\"announceCount\":1,\"announce\":{}}",
            "{\"announceCount\":1,\"announce\":[{\"title\":\"서버 프로그래머\",\"urlPassword\":null}]}",
            "{\"announceCount\":1,\"announce\":[{\"announceSeq\":1,\"title\":\"\",\"urlPassword\":null}]}"
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

    private static String page(int total, String... announces) {

        return "{\"count\":" + announces.length + ",\"announceCount\":" + total + ",\"announce\":[" + String.join(",", announces) + "]}";

    }

    private static String announce(long seq, String title, String endDate) {

        return "{\"announceSeq\":" + seq + ",\"companyNm\":\"스마일게이트\",\"title\":\"" + title + "\",\"endDate\":"
                + (endDate == null ? "null" : "\"" + endDate + "\"") + ",\"urlPassword\":null,\"jobMainNm\":\"기술/인프라\"}";

    }

    private static String detail(String workInfo, String qualification) {

        return "{\"announceSeq\":1,\"workInfo\":\"" + workInfo + "\",\"qualificationDesc\":\"" + qualification
                + "\",\"abilityDesc\":null,\"specialDesc\":\"\",\"description\":\"&lt;p&gt;기타 안내사항&lt;/p&gt;\"}";

    }
}
