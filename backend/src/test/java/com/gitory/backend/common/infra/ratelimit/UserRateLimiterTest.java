package com.gitory.backend.common.infra.ratelimit;

import com.gitory.backend.common.infra.ratelimit.RateLimitProperties.Limit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class UserRateLimiterTest {

    // 서울 2026-10-21 10:00:42 로, 다음 분까지 18초, 서울 자정까지 50,358초가 남는다
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T01:00:42Z"), ZoneOffset.UTC);
    private static final Long USER = 7L;

    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {

        connections = connectionTo(redisContainer.getHost(), redisContainer.getMappedPort(6379));
        redis = new StringRedisTemplate(connections);

    }

    @AfterAll
    static void disconnect() {

        connections.destroy();

    }

    @BeforeEach
    void flush() {

        redis.execute(connection -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);

    }

    @Test
    @DisplayName("한도 안의 요청은 통과하고 1분·하루 횟수를 모두 센다")
    void passesWithinLimit() {

        UserRateLimiter limiter = limiter(new Limit(2, 5));

        limiter.acquire(RateLimitedAction.INTERVIEW_ASK, USER, 1);
        limiter.acquire(RateLimitedAction.INTERVIEW_ASK, USER, 1);

        assertThat(redis.opsForValue().get("rate:interview-ask:7:m:" + Instant.now(CLOCK).getEpochSecond() / 60))
                .isEqualTo("2");
        assertThat(redis.opsForValue().get("rate:interview-ask:7:d:20261021")).isEqualTo("2");

    }

    @Test
    @DisplayName("1분 한도를 넘으면 다음 분까지 남은 초를 담아 거절한다")
    void rejectsOverMinuteLimitUntilNextMinute() {

        UserRateLimiter limiter = limiter(new Limit(1, 5));
        limiter.acquire(RateLimitedAction.INTERVIEW_ASK, USER, 1);

        assertThatThrownBy(() -> limiter.acquire(RateLimitedAction.INTERVIEW_ASK, USER, 1))
                .isInstanceOfSatisfying(RateLimitExceededException.class,
                        e -> assertThat(e.retryAfterSec()).isEqualTo(18));

    }

    @Test
    @DisplayName("하루 한도를 넘으면 서울 자정까지 남은 초를 담아 거절한다")
    void rejectsOverDayLimitUntilSeoulMidnight() {

        UserRateLimiter limiter = limiter(new Limit(10, 1));
        limiter.acquire(RateLimitedAction.INTERVIEW_ASK, USER, 1);

        assertThatThrownBy(() -> limiter.acquire(RateLimitedAction.INTERVIEW_ASK, USER, 1))
                .isInstanceOfSatisfying(RateLimitExceededException.class,
                        e -> assertThat(e.retryAfterSec()).isEqualTo(50_358));

    }

    @Test
    @DisplayName("여러 개를 한 번에 세고, 남은 횟수보다 많으면 하나도 세지 않고 통째로 거절한다")
    void countsAmountAndRejectsWholeRequestOverRemaining() {

        UserRateLimiter limiter = limiter(new Limit(null, 5));
        limiter.acquire(RateLimitedAction.CARD_DRAFT, USER, 3);

        assertThatThrownBy(() -> limiter.acquire(RateLimitedAction.CARD_DRAFT, USER, 3))
                .isInstanceOf(RateLimitExceededException.class);
        assertThat(redis.opsForValue().get("rate:card-draft:7:d:20261021")).isEqualTo("3");
        assertThatCode(() -> limiter.acquire(RateLimitedAction.CARD_DRAFT, USER, 2)).doesNotThrowAnyException();

    }

    @Test
    @DisplayName("같은 사용자의 요청이 동시에 와도 한도만큼만 통과한다")
    void concurrentRequestsDoNotExceedLimit() throws Exception {

        UserRateLimiter limiter = limiter(new Limit(10, 100));
        int threads = 30;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> results = new ArrayList<>();
        Callable<Boolean> attempt = () -> {
            start.await();
            try {
                limiter.acquire(RateLimitedAction.INTERVIEW_ASK, USER, 1);
                return true;
            } catch (RateLimitExceededException rejected) {
                return false;
            }
        };
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(attempt));
        }

        start.countDown();
        int passed = 0;
        for (Future<Boolean> result : results) {
            passed += result.get() ? 1 : 0;
        }
        pool.shutdown();

        assertThat(passed).isEqualTo(10);
        assertThat(redis.opsForValue().get("rate:interview-ask:7:d:20261021")).isEqualTo("10");

    }

    @Test
    @DisplayName("Redis 에 붙을 수 없으면 막지 않고 통과시키며 WARN 을 남긴다")
    void passesWithWarningWhenRedisIsUnreachable(CapturedOutput output) {

        // 열려 있지 않은 포트라 연결이 바로 거절된다
        LettuceConnectionFactory unreachable = connectionTo("127.0.0.1", 1);
        try {
            UserRateLimiter limiter = new UserRateLimiter(properties(true, new Limit(1, 1)),
                    new StringRedisTemplate(unreachable), CLOCK);

            assertThatCode(() -> limiter.acquire(RateLimitedAction.INTERVIEW_ASK, USER, 1)).doesNotThrowAnyException();
            assertThat(output.getOut()).contains("WARN").contains("Redis 에 붙지 못해 interview-ask 호출 제한을 건너뛴다");
        } finally {
            unreachable.destroy();
        }

    }

    @Test
    @DisplayName("스위치를 끄면 Redis 를 부르지 않고 모두 통과한다")
    void passesWithoutRedisWhenDisabled() {

        StringRedisTemplate untouched = mock(StringRedisTemplate.class);
        UserRateLimiter limiter = new UserRateLimiter(properties(false, new Limit(1, 1)), untouched, CLOCK);

        limiter.acquire(RateLimitedAction.INTERVIEW_ASK, USER, 1);
        limiter.acquire(RateLimitedAction.INTERVIEW_ASK, USER, 1);

        verifyNoInteractions(untouched);

    }

    private static UserRateLimiter limiter(Limit limit) {

        return new UserRateLimiter(properties(true, limit), redis, CLOCK);

    }

    private static RateLimitProperties properties(boolean enabled, Limit limit) {

        return new RateLimitProperties(enabled, limit, limit, limit);

    }

    private static LettuceConnectionFactory connectionTo(String host, int port) {

        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
        factory.afterPropertiesSet();
        factory.start();

        return factory;

    }
}
