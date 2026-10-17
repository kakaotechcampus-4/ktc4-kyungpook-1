package com.gitory.backend.common.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    @DisplayName("요청마다 서버가 UUID 를 만들어 요청 id 로 쓴다")
    void generatesRequestId() throws Exception {

        String requestId = requestIdSeenBy(new MockHttpServletRequest());

        assertThatCode(() -> UUID.fromString(requestId)).doesNotThrowAnyException();

    }

    @Test
    @DisplayName("요청에 X-Request-Id 가 실려 와도 그 값은 쓰지 않고 서버가 만든 UUID 를 쓴다")
    void ignoresIncomingRequestId() throws Exception {

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "client-chosen-1");

        String requestId = requestIdSeenBy(request);

        assertThat(requestId).isNotEqualTo("client-chosen-1");
        assertThatCode(() -> UUID.fromString(requestId)).doesNotThrowAnyException();

    }

    @Test
    @DisplayName("요청이 끝나면 요청 id 를 지워 같은 스레드의 다음 작업이 물려받지 않는다")
    void clearsRequestIdAfterRequest() throws Exception {

        requestIdSeenBy(new MockHttpServletRequest());

        assertThat(MDC.get("requestId")).isNull();

    }

    private String requestIdSeenBy(MockHttpServletRequest request) throws Exception {

        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set(MDC.get("requestId")));

        return seen.get();

    }
}
