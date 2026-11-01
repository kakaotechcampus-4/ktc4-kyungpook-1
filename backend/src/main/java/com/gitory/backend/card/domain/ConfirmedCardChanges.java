package com.gitory.backend.card.domain;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 레포 추천은 그 레포의 확정 카드로 계산하므로, 확정 카드가 생기거나 글이 바뀌거나 사라지면 알린다
 * 카드는 추천을 모르고 알리기만 하며, 확정 카드를 바꾸는 경로를 새로 만들면 여기를 불러야 추천이 따라 바뀐다
 */
@Component
@RequiredArgsConstructor
public class ConfirmedCardChanges {

    private final ApplicationEventPublisher events;

    public void announce(Card card) {

        if (card.getUserRepositoryId() != null) {
            events.publishEvent(new ConfirmedCardChanged(card.getUserId(), card.getUserRepositoryId()));
        }

    }
}
