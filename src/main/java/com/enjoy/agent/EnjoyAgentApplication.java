package com.enjoy.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot 应用启动入口。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class EnjoyAgentApplication {

    /**
     * 启动整个应用。
     */
    public static void main(String[] args) {
        SpringApplication.run(EnjoyAgentApplication.class, args);
    }
}
