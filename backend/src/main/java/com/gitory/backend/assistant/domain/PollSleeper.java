package com.gitory.backend.assistant.domain;

/** 모자란 조건 채우기를 기다리는 동안 쉬는 일을 떼어 두어, 시험이 실제로 기다리지 않게 한다 */
@FunctionalInterface
public interface PollSleeper {

    void sleep(long millis) throws InterruptedException;

}
