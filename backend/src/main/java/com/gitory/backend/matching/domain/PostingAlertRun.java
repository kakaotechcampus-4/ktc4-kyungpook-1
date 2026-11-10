package com.gitory.backend.matching.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

import static lombok.AccessLevel.PROTECTED;

/**
 * 새 공고 알림이 조건을 뽑은 공고를 어디까지 확인했는지 남기는 전역 한 줄이며, id 는 늘 1 이다
 * 이 시각 뒤에 조건을 뽑은 공고만 다음 실행이 보므로, 같은 공고를 같은 사용자에게 두 번 알리지 않는다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "posting_alert_run")
public class PostingAlertRun {

    public static final int ONLY_ID = 1;

    @Id
    private Integer id;

    private Instant checkedThrough;

    private PostingAlertRun(Instant checkedThrough) {
        this.id = ONLY_ID;
        this.checkedThrough = checkedThrough;
    }

    public static PostingAlertRun through(Instant checkedThrough) {

        return new PostingAlertRun(checkedThrough);

    }

    public void advance(Instant checkedThrough) {

        this.checkedThrough = checkedThrough;

    }
}
