package com.gitory.backend.assistant.domain;

import org.springframework.stereotype.Component;

@Component
class ThreadPollSleeper implements PollSleeper {

    @Override
    public void sleep(long millis) throws InterruptedException {

        Thread.sleep(millis);

    }
}
