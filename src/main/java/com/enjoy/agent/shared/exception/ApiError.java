package com.enjoy.agent.shared.exception;

/**
 * 统一错误对象。
 */
public record ApiError(
        String code,
        String message
) {
}
