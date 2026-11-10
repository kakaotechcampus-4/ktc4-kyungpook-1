package com.gitory.backend.ingest.domain;

/** 폴더 설명이 120자를 넘거나 DB 에 저장할 수 없는 글자를 담았을 때 발생하는 에러 */
public class InvalidRepositoryDescriptionException extends RuntimeException {

    public InvalidRepositoryDescriptionException(String message) {
        super(message);
    }
}
