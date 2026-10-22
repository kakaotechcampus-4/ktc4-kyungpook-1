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
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

/** 응답 모양은 2026-10-22 에 받은 실제 목록에서 쓰는 칸만 남겨 줄였다 */
@Timeout(30)
class KtCareersClientTest {

    private static final String LIST = "/api/recruit?currentPage=1&pageSize=3&isInProgress=true&isContainsContents=true";

    private final KtCareersClient client = new KtCareersClient();

    private FakeCareersSite site;
    private CompanySource source;

    @BeforeEach
    void startSite() {

        site = FakeCareersSite.start();
        source = new CompanySource("kt", "KT", CompanySourceType.KT, site.base() + LIST);

    }

    @AfterEach
    void stopSite() {

        site.close();

    }

    @Test
    @DisplayName("모집 분야 하나를 공고 하나로 나눠 공고·분야 id·'공고명 - 분야' 제목·주소·마감을 읽고, 본문은 목록의 HTML 을 글로 바꿔 함께 쓴다")
    void splitsNoticeIntoSectorPostings() {

        site.put(LIST, list(notice(151493, "[알티미디어] 2024년 신입/경력사원 채용", "<p>자격 요건</p><ul><li>Java</li></ul>",
                sector(1, "서울(서초)-백엔드 개발"), sector(2, "서울(서초)-PM"))));

        assertThat(client.list(FakeCareersSite.session(), source)).containsExactly(
                new FetchedPosting("151493-1", "[알티미디어] 2024년 신입/경력사원 채용 - 서울(서초)-백엔드 개발",
                        "https://kt.recruiter.co.kr/career/jobs/151493", "대한민국", null,
                        Instant.parse("2026-10-22T07:00:59Z"), "자격 요건\nJava"),
                new FetchedPosting("151493-2", "[알티미디어] 2024년 신입/경력사원 채용 - 서울(서초)-PM",
                        "https://kt.recruiter.co.kr/career/jobs/151493", "대한민국", null,
                        Instant.parse("2026-10-22T07:00:59Z"), "자격 요건\nJava"));

    }

    @Test
    @DisplayName("본문이 목록에 함께 오므로 상세를 요청하지 않고, 분야 이름이 개발 직무인 공고만 개발 공고 거르기를 지난다")
    void neverRequestsDetailAndFiltersBySector() {

        site.put(LIST, list(notice(1, "[kt ds] 신입사원 채용", "", sector(1, "백엔드 개발"), sector(2, "경영기획"))));

        List<FetchedPosting> postings = client.list(FakeCareersSite.session(), source);

        assertThat(postings).filteredOn(DevelopmentPostingFilter::accepts).extracting(FetchedPosting::externalId)
                .containsExactly("1-1");
        assertThat(site.hits()).containsExactly("GET " + LIST);

    }

    @Test
    @DisplayName("본문이 이미지뿐이라 글이 없어도 공고는 빈 본문으로 넘긴다")
    void keepsImageOnlyNotice() {

        site.put(LIST, list(notice(1, "[kt ds] 신입사원 채용", "<img src=\\\"https://kt.recruiter.co.kr/upload/a.jpg\\\"><br>",
                sector(1, "백엔드 개발"))));

        assertThat(client.list(FakeCareersSite.session(), source)).singleElement()
                .satisfies(posting -> assertThat(posting.text()).isEmpty());

    }

    @Test
    @DisplayName("모집 분야가 없는 공고는 공고 하나로 넘기고, 게시하지 않은 공고는 건너뛴다")
    void noticeWithoutSectorsIsOnePostingAndUnpostedIsSkipped() {

        site.put(LIST, list(notice(1, "[KT] 인재풀 등록", ""),
                notice(2, "[KT] 숨긴 공고", "", sector(1, "백엔드 개발")).replace("\"isPost\":true", "\"isPost\":false")));

        assertThat(client.list(FakeCareersSite.session(), source))
                .extracting(FetchedPosting::externalId, FetchedPosting::title)
                .containsExactly(tuple("1", "[KT] 인재풀 등록"));

    }

    @Test
    @DisplayName("공고가 한 쪽(pageSize)을 꽉 채우면 뒤 쪽이 있을 수 있어 회사 전체를 PARSE 로 실패시킨다")
    void fullPageFailsWithParse() {

        site.put(LIST, list(Collections.nCopies(3, notice(1, "[KT] 채용", "", sector(1, "백엔드 개발"))).toArray(String[]::new)));

        assertThat(failureCodeOf()).isEqualTo(FetchFailureCode.PARSE);

    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("목록 모양이 다르면 일부만 건너뛰지 않고 회사 전체를 PARSE 로 실패시킨다")
    @ValueSource(strings = {
            "<html>점검 중</html>",
            "{\"isSuccess\":false,\"errorMessage\":\"오류\",\"data\":null}",
            "{\"isSuccess\":true,\"data\":[{\"recruitNoticeName\":\"[KT] 채용\",\"recruitNoticeUrl\":\"https://kt.recruiter.co.kr/career/jobs/1\"}]}",
            "{\"isSuccess\":true,\"data\":[{\"recruitNoticeSn\":1,\"recruitNoticeName\":\"[KT] 채용\",\"recruitNoticeUrl\":null}]}",
            "{\"isSuccess\":true,\"data\":[{\"recruitNoticeSn\":1,\"recruitNoticeName\":\"[KT] 채용\","
                    + "\"recruitNoticeUrl\":\"https://kt.recruiter.co.kr/career/jobs/1\",\"recruitSectorList\":[{\"recruitSectorName\":\"개발\"}]}]}"
    })
    void unreadableListFailsWithParse(String body) {

        site.put(LIST, body);

        assertThat(failureCodeOf()).isEqualTo(FetchFailureCode.PARSE);

    }

    private FetchFailureCode failureCodeOf() {

        Throwable thrown = catchThrowable(() -> client.list(FakeCareersSite.session(), source));
        assertThat(thrown).isInstanceOf(PoliteFetchException.class);
        return ((PoliteFetchException) thrown).code();

    }

    private static String list(String... notices) {

        return "{\"isSuccess\":true,\"errorMessage\":null,\"data\":[" + String.join(",", notices) + "]}";

    }

    private static String notice(long id, String name, String contents, String... sectors) {

        return "{\"recruitNoticeSn\":" + id + ",\"recruitNoticeName\":\"" + name + "\",\"recruitSectorList\":["
                + String.join(",", sectors) + "],\"recruitNoticeUrl\":\"https://kt.recruiter.co.kr/career/jobs/" + id
                + "\",\"isPost\":true,\"isInProgress\":true,\"receiveEndDatetime\":\"2026-10-22 16:00:59\","
                + "\"contents\":\"" + contents + "\"}";

    }

    private static String sector(long id, String name) {

        return "{\"recruitSectorSn\":" + id + ",\"recruitSectorName\":\"" + name + "\"}";

    }
}
