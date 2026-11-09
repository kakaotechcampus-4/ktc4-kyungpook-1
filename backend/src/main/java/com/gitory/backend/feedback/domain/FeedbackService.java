package com.gitory.backend.feedback.domain;

import com.gitory.backend.common.infra.ratelimit.RateLimitedAction;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import com.gitory.backend.feedback.infra.FeedbackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FeedbackService {

    private static final int MAX_MESSAGE_LENGTH = 1000;
    private static final int MAX_PATH_LENGTH = 255;
    private static final int MAX_USER_AGENT_LENGTH = 255;

    private final FeedbackRepository feedbacks;
    private final UserRateLimiter rateLimiter;
    private final ApplicationEventPublisher events;

    // 잘못된 요청이 하루 한도를 깎지 않도록 검사를 먼저 하고 한도를 센다
    @Transactional
    public void report(Long userId, String message, String path, String userAgent) {

        String trimmed = message == null ? "" : message.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_MESSAGE_LENGTH) {
            throw new FeedbackRejectedException("글은 1자 이상 1000자 이하여야 합니다.");
        }
        if (path != null && path.length() > MAX_PATH_LENGTH) {
            throw new FeedbackRejectedException("화면 주소는 255자 이하여야 합니다.");
        }

        rateLimiter.acquire(RateLimitedAction.FEEDBACK, userId, 1);

        Feedback saved = feedbacks.save(Feedback.of(userId, trimmed, path, cut(userAgent)));
        events.publishEvent(new FeedbackReceived(saved.getId()));

    }

    private static String cut(String userAgent) {

        if (userAgent == null || userAgent.length() <= MAX_USER_AGENT_LENGTH) {
            return userAgent;
        }

        return userAgent.substring(0, MAX_USER_AGENT_LENGTH);

    }
}
