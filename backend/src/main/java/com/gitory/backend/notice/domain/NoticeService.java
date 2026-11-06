package com.gitory.backend.notice.domain;

import com.gitory.backend.notice.infra.NoticeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NoticeService {

    private final NoticeRepository notices;
    private final Clock clock;

    @Transactional
    public void leave(Long userId, NoticeKind kind, NoticePayload payload) {

        notices.save(Notice.leave(userId, kind, payload, Instant.now(clock)));

    }

    /** 최근에 남긴 것부터 30개까지만 준다 */
    @Transactional(readOnly = true)
    public List<NoticeView> list(Long userId) {

        return notices.findTop30ByUserIdOrderByCreatedAtDescIdDesc(userId).stream().map(NoticeView::from).toList();

    }

    /** 남의 알림과 없는 알림은 구별하지 않고 NoticeNotFoundException 이다 */
    @Transactional
    public void read(UUID publicId, Long userId) {

        notices.findByPublicIdAndUserId(publicId, userId)
                .orElseThrow(NoticeNotFoundException::new)
                .read(Instant.now(clock));

    }

    @Transactional
    public void readAll(Long userId) {

        notices.markAllRead(userId, Instant.now(clock));

    }
}
