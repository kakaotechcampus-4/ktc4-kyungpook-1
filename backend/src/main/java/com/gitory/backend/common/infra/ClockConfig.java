package com.gitory.backend.common.infra;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 시간 제한을 테스트에서 시계를 바꿔 확인할 수 있도록 시계를 빈으로 둔다 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock() {

        return Clock.systemUTC();

    }
}
