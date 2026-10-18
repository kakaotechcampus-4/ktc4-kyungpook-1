package com.gitory.backend.usage.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

import static lombok.AccessLevel.PROTECTED;

@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "usage_event")
public class UsageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> props;

    private String path;

    private Instant clientAt;

    // DB 기본값 now() 에 맡기면 JPA 가 null 을 넣어 NOT NULL 제약에 걸린다
    @CreationTimestamp
    private Instant receivedAt;

    private UsageEvent(Long userId, String name, Map<String, Object> props, String path, Instant clientAt) {
        this.userId = userId;
        this.name = name;
        this.props = props;
        this.path = path;
        this.clientAt = clientAt;
    }

    static UsageEvent of(Long userId, String name, Map<String, Object> props, String path, Instant clientAt) {

        return new UsageEvent(userId, name, props, path, clientAt);

    }
}
