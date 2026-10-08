package com.gitory.backend.common.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 요청마다 요청 id 를 MDC 에 넣어 감사 기록이 어느 요청에서 나왔는지 남게 한다
 * GitHub 로그인 콜백은 Spring Security 필터 안에서 끝나므로, 그보다 먼저 돌아야 연결 기록에도 요청 id 가 남는다
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    // audit 의 AuditLog 가 같은 이름으로 읽으므로 바꾸면 감사 기록에서 요청 id 가 빠진다
    private static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // 들어온 X-Request-Id 는 요청을 보낸 사람이 정한 값이라 감사 기록에 쓰지 않고 늘 서버가 만든다
        MDC.put(MDC_KEY, UUID.randomUUID().toString());

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }

    }
}
