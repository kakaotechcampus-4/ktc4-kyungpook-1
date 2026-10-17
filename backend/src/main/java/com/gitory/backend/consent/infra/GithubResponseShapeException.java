package com.gitory.backend.consent.infra;

/** GitHub 이 200 으로 답했지만 기대한 목록이 없거나 값을 읽을 수 없을 때 던진다 */
class GithubResponseShapeException extends RuntimeException {

    GithubResponseShapeException(String message) {
        super(message);
    }
}
