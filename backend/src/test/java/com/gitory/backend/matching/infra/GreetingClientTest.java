package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.FetchedPosting;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@Timeout(30)
class GreetingClientTest {

    private static final String LIST_PATH = "/ko/home";

    private final Map<String, String> pages = new ConcurrentHashMap<>();
    private final List<String> hits = new CopyOnWriteArrayList<>();
    private final GreetingClient client = new GreetingClient();

    private HttpServer server;
    private String base;
    private CompanySource source;

    @BeforeEach
    void startServer() throws IOException {

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            hits.add(path);
            byte[] body = pages.getOrDefault(path, "").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(pages.containsKey(path) ? 200 : 404, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        source = new CompanySource("greet", "그리팅사", CompanySourceType.GREETING, base + LIST_PATH);

    }

    @AfterEach
    void stopServer() {

        server.stop(0);

    }

    @Test
    @DisplayName("목록에서 id·제목·근무지·마감일을 읽고 본문은 상세 페이지의 HTML 을 글로 바꿔 채운다")
    void readsFieldsFromListAndTextFromDetail() {

        pages.put(LIST_PATH, page(openingsQuery(opening(10, "서버 개발자", "서울특별시 강남구 대치동", "2026-10-29T14:59:59Z"))));
        pages.put("/ko/o/10", page(detailQuery("<h3>자격 요건</h3><ul><li>Java &amp; Spring</li></ul>")));

        List<FetchedPosting> postings = client.list(session(), source);

        assertThat(postings).containsExactly(new FetchedPosting("10", "서버 개발자", base + "/ko/o/10",
                "서울특별시 강남구 대치동", null, Instant.parse("2026-10-29T14:59:59Z"), "자격 요건\nJava & Spring"));

    }

    @Test
    @DisplayName("상세 본문의 엔티티는 풀지 않고 읽어 꺾쇠로 적힌 글 뒤의 본문이 남는다")
    void keepsTextAfterEntityEncodedAngleBrackets() {

        pages.put(LIST_PATH, page(openingsQuery(opening(10, "서버 개발자", null, null))));
        pages.put("/ko/o/10", page(detailQuery("<li>XSS, &lt;script&gt; 삽입 진단</li><li>Java 5년 이상</li>")));

        assertThat(client.list(session(), source)).singleElement()
                .satisfies(posting -> assertThat(posting.text()).isEqualTo("XSS, <script> 삽입 진단\nJava 5년 이상"));

    }

    @Test
    @DisplayName("근무지 칸이 비어 있으면 한국 근무로 본다")
    void treatsMissingPlaceAsKorea() {

        pages.put(LIST_PATH, page(openingsQuery(opening(10, "서버 개발자", null, null))));
        pages.put("/ko/o/10", page(detailQuery("<p>본문</p>")));

        assertThat(client.list(session(), source)).singleElement()
                .satisfies(posting -> assertThat(posting.location()).isEqualTo("대한민국"));

    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("마감일이 없거나 읽지 못하면 마감 없음으로 둔다")
    @ValueSource(strings = {"null", "\"2026-10-29\"", "\"곧 마감\"", "20261029"})
    void missingOrUnreadableDueDateMeansNoExpiry(String dueDateJson) {

        pages.put(LIST_PATH, page(openingsQuery(opening(10, "마케팅 매니저", null, null)
                .replace("\"dueDate\":null", "\"dueDate\":" + dueDateJson))));

        assertThat(client.list(session(), source)).singleElement()
                .satisfies(posting -> assertThat(posting.expiresAt()).isNull());

    }

    @Test
    @DisplayName("제목이 255자를 넘으면 255자까지만 남긴다")
    void cutsTitleAtMaxLength() {

        pages.put(LIST_PATH, page(openingsQuery(opening(10, "가".repeat(300), null, null))));

        assertThat(client.list(session(), source)).singleElement()
                .satisfies(posting -> assertThat(posting.title()).hasSize(255));

    }

    @Test
    @DisplayName("개발 공고가 아니거나 해외 근무인 공고는 상세 페이지를 요청하지 않는다")
    void requestsDetailOnlyForDevelopmentPostings() {

        pages.put(LIST_PATH, page(openingsQuery(opening(10, "서버 개발자", null, null),
                opening(11, "마케팅 매니저", null, null), opening(12, "서버 개발자", "일본 도쿄도", null))));
        pages.put("/ko/o/10", page(detailQuery("<p>본문</p>")));

        List<FetchedPosting> postings = client.list(session(), source);

        assertThat(postings).extracting(FetchedPosting::externalId).containsExactly("10", "11", "12");
        assertThat(hits).filteredOn(path -> path.startsWith("/ko/o/")).containsExactly("/ko/o/10");

    }

    @Test
    @DisplayName("게시하지 않은 공고는 건너뛴다")
    void skipsUndeployedOpenings() {

        pages.put(LIST_PATH, page(openingsQuery(opening(10, "서버 개발자", null, null)
                .replace("\"deploy\":true", "\"deploy\":false"))));

        assertThat(client.list(session(), source)).isEmpty();

    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("공고 데이터를 읽을 수 없는 목록 페이지는 PARSE 로 실패한다")
    @ValueSource(strings = {
            "<html>잠시만 기다려 주세요</html>",
            "<html><script id=\"__NEXT_DATA__\">{ 깨진 JSON</script></html>",
            "<html><script id=\"__NEXT_DATA__\">{\"props\":{\"pageProps\":{\"dehydratedState\":{\"queries\":[]}}}}</script></html>",
            "<html><script id=\"__NEXT_DATA__\">{\"props\":{\"pageProps\":{\"dehydratedState\":{\"queries\":["
                    + "{\"queryKey\":[\"openings\"],\"state\":{\"data\":{}}}]}}}}</script></html>",
            "<html><script id=\"__NEXT_DATA__\">{\"props\":{\"pageProps\":{\"dehydratedState\":{\"queries\":["
                    + "{\"queryKey\":[\"openings\"],\"state\":{\"data\":[{\"title\":\"서버 개발자\"}]}}]}}}}</script></html>",
            "<html><script id=\"__NEXT_DATA__\">{\"props\":{\"pageProps\":{\"dehydratedState\":{\"queries\":["
                    + "{\"queryKey\":[\"openings\"],\"state\":{\"data\":[{\"openingId\":0,\"title\":\"서버 개발자\"}]}}]}}}}</script></html>"
    })
    void unreadableListFailsWithParse(String body) {

        pages.put(LIST_PATH, body);

        assertThat(failureCodeOf()).isEqualTo(FetchFailureCode.PARSE);

    }

    @Test
    @DisplayName("상세 페이지에 본문 칸이 없으면 PARSE 로 실패한다")
    void detailWithoutBodyFailsWithParse() {

        pages.put(LIST_PATH, page(openingsQuery(opening(10, "서버 개발자", null, null))));
        pages.put("/ko/o/10", page("[{\"queryKey\":[\"career\",\"getOpeningById\"],\"state\":{\"data\":{\"data\":{}}}}]"));

        assertThat(failureCodeOf()).isEqualTo(FetchFailureCode.PARSE);

    }

    private FetchFailureCode failureCodeOf() {

        Throwable thrown = catchThrowable(() -> client.list(session(), source));
        assertThat(thrown).isInstanceOf(PoliteFetchException.class);
        return ((PoliteFetchException) thrown).code();

    }

    private static PoliteFetchSession session() {

        PoliteFetchProperties properties = new PoliteFetchProperties(null, null, null, Duration.ZERO, null, null);
        return new PoliteFetcher(properties, Clock.systemUTC()).open();

    }

    private static String page(String queries) {

        return "<html><body><script id=\"__NEXT_DATA__\" type=\"application/json\">"
                + "{\"props\":{\"pageProps\":{\"dehydratedState\":{\"queries\":" + queries + "}}}}</script></body></html>";

    }

    private static String openingsQuery(String... openings) {

        return "[{\"queryKey\":[\"publicCareer\",\"getCareerBaseInfo\",\"careers\"],\"state\":{\"data\":{}}},"
                + "{\"queryKey\":[\"openings\"],\"state\":{\"data\":[" + String.join(",", openings) + "]}}]";

    }

    private static String detailQuery(String html) {

        return "[{\"queryKey\":[\"career\",\"getOpeningById\",{\"openingId\":10}],\"state\":{\"data\":"
                + "{\"success\":true,\"data\":{\"openingsInfo\":{\"detail\":\"" + html + "\"}}}}}]";

    }

    private static String opening(long id, String title, String place, String dueDate) {

        String position = place == null ? "{\"workspacePlace\":null}" : "{\"workspacePlace\":{\"place\":\"" + place + "\"}}";
        return "{\"deploy\":true,\"openingId\":" + id + ",\"title\":\"" + title + "\",\"dueDate\":"
                + (dueDate == null ? "null" : "\"" + dueDate + "\"")
                + ",\"openingJobPosition\":{\"openingJobPositions\":[" + position + "]}}";

    }
}
