package com.gitory.backend.ingest.domain;

/** 요청한 저장소가 없거나 내 저장소가 아닐 때 발생하는 에러 */
public class ConnectedRepositoryNotFoundException extends RuntimeException {

    public ConnectedRepositoryNotFoundException() {
        super("요청한 저장소를 찾을 수 없습니다.");
    }
}
