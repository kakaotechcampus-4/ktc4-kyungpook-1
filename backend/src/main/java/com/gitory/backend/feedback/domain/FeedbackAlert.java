package com.gitory.backend.feedback.domain;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** 신고가 저장된 뒤 팀에 한 줄로 알리며, 사용자가 쓴 글은 밖으로 보내지 않으려고 번호만 담는다 */
@Component
@RequiredArgsConstructor
public class FeedbackAlert {

    private final FeedbackAlertSender sender;

    // 저장이 롤백되면 알리지 않도록 커밋이 끝난 뒤에 받는다
    @TransactionalEventListener
    public void received(FeedbackReceived event) {

        sender.send("[Gitory] 문제 신고가 들어왔습니다 (#" + event.feedbackId() + ")");

    }
}
