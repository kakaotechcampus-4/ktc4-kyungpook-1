package com.gitory.backend.common.infra.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * LLM 을 부르는 요청을 사용자별로 1분·하루 단위로 세고, 한도를 넘으면 거절한다
 * 서버가 다시 떠도 숫자가 남아야 해서 Redis 에 세며, 제한은 보호 장치라 Redis 에 붙지 못하면 막지 않고 통과시킨다
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(RateLimitProperties.class)
public class UserRateLimiter {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Duration MINUTE_KEY_TTL = Duration.ofMinutes(2);
    private static final Duration DAY_KEY_TTL = Duration.ofDays(2);
    private static final long ACCEPTED = 0;
    private static final long OVER_MINUTE = 1;
    private static final long OVER_DAY = 2;

    // 여러 요청이 동시에 와도 한도를 넘지 않도록 확인과 더하기를 Redis 안에서 한 번에 한다
    // 하루 한도를 먼저 봐야 둘 다 넘었을 때 더 긴 기다림을 알려준다
    private static final RedisScript<Long> ACQUIRE = RedisScript.of("""
            local amount = tonumber(ARGV[1])
            local minuteLimit = tonumber(ARGV[2])
            local dayLimit = tonumber(ARGV[3])
            if dayLimit > 0 and tonumber(redis.call('GET', KEYS[2]) or '0') + amount > dayLimit then
              return 2
            end
            if minuteLimit > 0 and tonumber(redis.call('GET', KEYS[1]) or '0') + amount > minuteLimit then
              return 1
            end
            if minuteLimit > 0 then
              redis.call('INCRBY', KEYS[1], amount)
              redis.call('EXPIRE', KEYS[1], ARGV[4])
            end
            if dayLimit > 0 then
              redis.call('INCRBY', KEYS[2], amount)
              redis.call('EXPIRE', KEYS[2], ARGV[5])
            end
            return 0
            """, Long.class);

    private final RateLimitProperties properties;
    private final StringRedisTemplate redis;
    private final Clock clock;

    /** amount 만큼 한 번에 세고, 남은 횟수보다 많으면 하나도 세지 않고 통째로 거절한다 */
    public void acquire(RateLimitedAction action, Long userId, int amount) {

        if (!properties.enabled()) {
            return;
        }

        RateLimitProperties.Limit limit = properties.of(action);
        if (limit.perMinute() == null && limit.perDay() == null) {
            return;
        }

        ZonedDateTime now = Instant.now(clock).atZone(SEOUL);
        String prefix = "rate:" + action.key() + ":" + userId;
        Long verdict;
        try {
            verdict = redis.execute(ACQUIRE,
                    List.of(prefix + ":m:" + now.toEpochSecond() / 60, prefix + ":d:" + DAY.format(now)),
                    String.valueOf(amount), limitArg(limit.perMinute()), limitArg(limit.perDay()),
                    String.valueOf(MINUTE_KEY_TTL.toSeconds()), String.valueOf(DAY_KEY_TTL.toSeconds()));
        } catch (DataAccessException unreachable) {
            log.warn("Redis 에 붙지 못해 {} 호출 제한을 건너뛴다 ({})", action.key(), unreachable.getClass().getSimpleName());
            return;
        }

        if (verdict == null || verdict == ACCEPTED) {
            return;
        }

        throw new RateLimitExceededException(verdict == OVER_DAY ? untilMidnight(now) : untilNextMinute(now));

    }

    // 운영 설정에서 켜는 줄을 빠뜨려도 서버 로그로 알아챌 수 있게 한다
    @EventListener(ApplicationReadyEvent.class)
    public void logSwitch() {

        log.info("LLM 호출 제한이 {}", properties.enabled() ? "켜져 있다" : "꺼져 있다");

    }

    private static String limitArg(Integer limit) {

        return String.valueOf(limit == null ? 0 : limit);

    }

    private static long untilNextMinute(ZonedDateTime now) {

        return Math.max(1, ChronoUnit.SECONDS.between(now, now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)));

    }

    private static long untilMidnight(ZonedDateTime now) {

        return Math.max(1, ChronoUnit.SECONDS.between(now, now.toLocalDate().plusDays(1).atStartOfDay(SEOUL)));

    }
}
