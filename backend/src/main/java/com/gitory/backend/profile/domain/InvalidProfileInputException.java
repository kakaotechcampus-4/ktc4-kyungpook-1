package com.gitory.backend.profile.domain;

public class InvalidProfileInputException extends RuntimeException {

    public InvalidProfileInputException(String message) {
        super(message);
    }
}
