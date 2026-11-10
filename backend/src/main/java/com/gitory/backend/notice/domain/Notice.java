package com.gitory.backend.notice.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

import static lombok.AccessLevel.PROTECTED;

@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "notification")
public class Notice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID publicId;
    private Long userId;

    @Enumerated(EnumType.STRING)
    private NoticeKind kind;

    @JdbcTypeCode(SqlTypes.JSON)
    private NoticePayload payload;

    private Instant createdAt;
    private Instant readAt;

    private Notice(Long userId, NoticeKind kind, NoticePayload payload, Instant now) {
        this.publicId = UUID.randomUUID();
        this.userId = userId;
        this.kind = kind;
        this.payload = payload;
        this.createdAt = now;
    }

    public static Notice leave(Long userId, NoticeKind kind, NoticePayload payload, Instant now) {

        return new Notice(userId, kind, payload, now);

    }

    /** 처음 읽은 시각을 남기려고 이미 읽은 알림은 바꾸지 않는다 */
    public void read(Instant now) {

        if (readAt == null) {
            this.readAt = now;
        }

    }
}
