package com.gitory.backend.consent.infra;

/** GitHub GraphQL 이 한도 초과를 HTTP 200 과 errors 로 알릴 때 던진다 */
class GraphqlRateLimitedException extends RuntimeException {

    GraphqlRateLimitedException(String message) {
        super(message);
    }
}
