package com.enjoy.agent.shared.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 统一接口响应结构。
 */
@Schema(name = "ApiResponse", description = "平台统一返回结构")
public record ApiResponse<T>(
        @Schema(description = "本次请求是否成功")
        boolean success,
        @Schema(description = "业务数据")
        T data,
        @Schema(description = "消息说明")
        String message,
        @Schema(description = "响应时间")
        Instant timestamp
) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, "OK", Instant.now());
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(true, data, message, Instant.now());
    }

    public static <T> ApiResponse<T> failure(T data, String message) {
        return new ApiResponse<>(false, data, message, Instant.now());
    }
}
