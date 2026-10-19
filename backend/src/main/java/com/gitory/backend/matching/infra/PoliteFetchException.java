package com.gitory.backend.matching.infra;

public class PoliteFetchException extends RuntimeException {

    private final FetchFailureCode code;

    public PoliteFetchException(FetchFailureCode code, String message) {
        super(message);
        this.code = code;
    }

    public FetchFailureCode code() {

        return code;

    }
}
