package com.gitory.backend.support;

import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultHandler;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 브라우저 흉내. 응답으로 온 쿠키를 받아 적고 다음 요청에 그대로 실어 보낸다.
 *
 * 세션을 쓰는 왕복 테스트는 MockHttpSession 을 넘기는 방식으로 쓸 수 없다 —
 * spring-session 이 그 객체를 무시하므로 세션이 실제로 이어지는지 확인하지 못한다.
 * 그래서 쿠키를 들고 다니는 이 방식이 필요하고, 그 로직이 두 테스트에 복사되어 있었다.
 */
public final class TestBrowser {

    private final MockMvc mvc;

    private final Map<String, Cookie> cookieJar = new LinkedHashMap<>();

    public TestBrowser(MockMvc mvc) {
        this.mvc = mvc;
    }

    /** 쿠키를 실어 보내고, 응답으로 온 쿠키를 받아 적는다. */
    public ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        if (!cookieJar.isEmpty()) {
            request = request.cookie(cookieJar.values().toArray(new Cookie[0]));
        }
        MvcResult result = mvc.perform(request).andReturn();
        for (Cookie cookie : result.getResponse().getCookies()) {
            if (cookie.getMaxAge() == 0) {
                cookieJar.remove(cookie.getName()); // 만료 지시 = 삭제
            } else {
                cookieJar.put(cookie.getName(), cookie);
            }
        }
        return new StaticResultActions(result);
    }

    /** 쿠키를 전부 버린다 — 같은 서버에 새 브라우저로 처음 접속하는 것과 같다. */
    public void clearCookies() {
        cookieJar.clear();
    }

    public boolean hasCookie(String name) {
        return cookieJar.containsKey(name);
    }

    /** 프론트가 하는 것과 같다 — CSRF 헤더 값은 서버가 내려준 쿠키에서 읽는다. */
    public String csrfToken() {
        Cookie cookie = cookieJar.get("XSRF-TOKEN");
        assertThat(cookie).as("XSRF-TOKEN 쿠키가 내려와야 프론트가 CSRF 헤더를 만들 수 있다").isNotNull();
        return cookie.getValue();
    }

    /**
     * 리다이렉트 URL 의 쿼리 문자열을 푼다.
     *
     * URI#getQuery 는 퍼센트 인코딩을 이미 풀어 준다 — state 의 '=' 패딩(%3D)이
     * 여기서 복원된다. 인코딩된 채로 콜백에 실으면 저장된 인가 요청을 찾지 못해
     * access_denied 가 아니라 authorization_request_not_found 로 떨어진다.
     */
    public static Map<String, String> queryOf(String url) {
        Map<String, String> params = new LinkedHashMap<>();
        Arrays.stream(URI.create(url).getQuery().split("&")).forEach(pair -> {
            String[] parts = pair.split("=", 2);
            params.put(parts[0], parts.length > 1 ? parts[1] : "");
        });
        return params;
    }

    /** 이미 실행한 결과에 대해 단언만 이어 가려고 쓰는 얇은 래퍼. */
    private record StaticResultActions(MvcResult result) implements ResultActions {

        @Override
        public ResultActions andExpect(ResultMatcher matcher) throws Exception {
            matcher.match(result);
            return this;
        }

        @Override
        public ResultActions andDo(ResultHandler handler) throws Exception {
            handler.handle(result);
            return this;
        }

        @Override
        public MvcResult andReturn() {
            return result;
        }
    }
}
