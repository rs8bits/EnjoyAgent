package com.enjoy.agent.support.api.response;

import java.util.UUID;

/** SSE error：只返回可公开的错误码和文案，不回传异常堆栈。 */
public record SupportErrorEvent(
        UUID turnId,
        String code,
        String message,
        boolean retryable
) {
}
