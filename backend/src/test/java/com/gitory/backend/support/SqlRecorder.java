package com.gitory.backend.support;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hibernate 가 DB 로 보내는 SQL 을 소문자로 모아, 저장 한 번에 어떤 문장이 몇 번 나갔는지 테스트가 셀 수 있게 한다
 * 테스트의 spring.jpa.properties.hibernate.session_factory.statement_inspector 에 이 클래스 이름을 걸어야 모인다
 */
public class SqlRecorder implements StatementInspector {

    private static final List<String> RECORDED = new CopyOnWriteArrayList<>();

    @Override
    public String inspect(String sql) {

        RECORDED.add(sql.toLowerCase(Locale.ROOT));
        return sql;

    }

    public static void clear() {

        RECORDED.clear();

    }

    public static long count(String start, String fragment) {

        return RECORDED.stream()
                .filter(sql -> sql.startsWith(start) && sql.contains(fragment))
                .count();

    }
}
