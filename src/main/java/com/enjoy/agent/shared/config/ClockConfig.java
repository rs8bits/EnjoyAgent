package com.enjoy.agent.shared.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间相关配置。
 */
@Configuration
public class ClockConfig {

    /**
     * 统一使用 UTC 时钟，避免不同时区带来的时间偏差。
     */
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
