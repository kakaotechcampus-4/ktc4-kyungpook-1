package com.gitory.backend.assistant.domain;

public class InvalidAssistantInputException extends RuntimeException {

    public InvalidAssistantInputException(String message) {
        super(message);
    }
}
