package com.enjoy.agent.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档配置。
 */
@Configuration
public class OpenApiConfig {

    /**
     * 定义接口文档基础信息和 Bearer 鉴权方案。
     */
    @Bean
    public OpenAPI enjoyAgentOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Enjoy Agent API")
                        .version("v0.5.0")
                        .description("Enjoy Agent 平台 MVP 接口文档，当前覆盖认证、凭证、模型配置、Agent 与基础聊天能力。"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("在 Swagger UI 的 Authorize 弹窗中粘贴 accessToken 即可。")));
    }
}
