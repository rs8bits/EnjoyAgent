package com.enjoy.agent.shared.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统级接口。
 */
@Tag(name = "系统", description = "基础探活与系统信息接口")
@RestController
@RequestMapping("/api/system")
public class SystemController {

    /**
     * 最小探活接口，用于确认服务是否存活。
     */
    @Operation(summary = "服务探活", description = "返回服务状态和当前时间")
    @GetMapping("/ping")
    public ApiResponse<Map<String, Object>> ping() {
        return ApiResponse.success(Map.of(
                "service", "enjoy-agent",
                "status", "UP",
                "timestamp", Instant.now()
        ));
    }
}
