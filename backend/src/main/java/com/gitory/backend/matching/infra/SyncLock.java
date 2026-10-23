package com.gitory.backend.matching.infra;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 서버가 여러 대여도 공고 가져오기와 색인 다시 보내기가 동시에 한 곳에서만 돌도록 DB 의 세션 잠금을 건다
 * 잠금은 연결에 묶이므로 잡은 연결로 풀어야 하고, 풀에 돌려주기 전에 풀지 못하면 그 연결이 잠금을 쥔 채 남는다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncLock {

    public static final long KEY = 7_020_191_900_001L;

    private final DataSource dataSource;

    /** 잠금을 잡지 못해 실행하지 않았으면 false */
    public boolean runExclusively(Runnable task) {

        try (Connection connection = dataSource.getConnection()) {
            if (!tryLock(connection)) {
                return false;
            }
            try {
                task.run();
            } finally {
                unlock(connection);
            }
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("공고 잠금을 다루지 못했다", e);
        }

    }

    private static boolean tryLock(Connection connection) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
            statement.setLong(1, KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }

    }

    private static void unlock(Connection connection) {

        try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_unlock(?)")) {
            statement.setLong(1, KEY);
            statement.executeQuery().close();
        } catch (SQLException e) {
            log.warn("공고 잠금을 풀지 못했다", e);
        }

    }
}
