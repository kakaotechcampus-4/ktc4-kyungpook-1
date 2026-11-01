package com.gitory.backend.coverletter.domain;

public class InvalidCoverLetterInputException extends RuntimeException {

    public InvalidCoverLetterInputException(String message) {
        super(message);
    }
}
