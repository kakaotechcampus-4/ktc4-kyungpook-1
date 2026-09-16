-- 세션 저장소 (2026-09-16)
--
-- 로그인 상태를 담는 유일한 곳이다. 스펙이 "장기 토큰을 클라이언트에 두지 않는다"고
-- 못 박았으므로 브라우저가 들고 다니는 것은 세션 쿠키뿐이고, 그 쿠키가 가리키는
-- 실체가 이 두 테이블이다. 메모리에 두면 인스턴스를 재시작할 때마다 전원이 로그아웃된다.
--
-- ⚠️ 이 DDL 은 우리가 쓴 것이 아니라 spring-session-jdbc 4.1.1 의
--    org/springframework/session/jdbc/schema-postgresql.sql 을 그대로 옮긴 것이다.
--    컬럼명·타입을 고치면 JdbcIndexedSessionRepository 의 쿼리와 어긋나 로그인이 깨진다.
--    Spring Session 을 올릴 때는 그 파일과 이 파일을 대조하고, 달라졌으면 V<n> 을 새로 판다.
--
-- 왜 Flyway 로 만드는가 — spring.session.jdbc.initialize-schema 가 대신 만들어 줄 수 있지만,
-- 그러면 스키마 출처가 Flyway 와 Spring Session 두 곳이 된다. 이 레포는 "스키마의 단일
-- 출처는 Flyway"가 규칙이라(V1 머리말) 여기에 넣고 initialize-schema 는 never 로 끈다.

CREATE TABLE SPRING_SESSION (
    PRIMARY_ID            CHAR(36) NOT NULL,
    SESSION_ID            CHAR(36) NOT NULL,
    CREATION_TIME         BIGINT   NOT NULL,
    LAST_ACCESS_TIME      BIGINT   NOT NULL,
    MAX_INACTIVE_INTERVAL INT      NOT NULL,
    EXPIRY_TIME           BIGINT   NOT NULL,
    PRINCIPAL_NAME        VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36)     NOT NULL,
    ATTRIBUTE_NAME     VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES    BYTEA        NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
);
