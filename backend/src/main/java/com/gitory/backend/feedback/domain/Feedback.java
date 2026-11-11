package com.gitory.backend.feedback.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

import static lombok.AccessLevel.PROTECTED;

@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "feedback")
public class Feedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    private String message;

    private String path;

    private String userAgent;

    // DB 기본값에 맡기면 JPA 가 null 을 넣어 NOT NULL 제약에 걸린다
    @CreationTimestamp
    private Instant createdAt;

    private Feedback(Long userId, String message, String path, String userAgent) {
        this.userId = userId;
        this.message = message;
        this.path = path;
        this.userAgent = userAgent;
    }

    static Feedback of(Long userId, String message, String path, String userAgent) {

        return new Feedback(userId, message, path, userAgent);

    }
}
