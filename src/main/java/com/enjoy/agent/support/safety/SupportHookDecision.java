package com.enjoy.agent.support.safety;

import org.springframework.http.HttpStatus;

/** Hook 判断结果。publicMessage 可以安全地返回给终端用户。 */
public record SupportHookDecision(
        SupportHookAction action,
        String payload,
        String reasonCode,
        String publicMessage,
        HttpStatus status
) {
    public static SupportHookDecision allow(String payload) {
        return new SupportHookDecision(SupportHookAction.ALLOW, payload, null, null, HttpStatus.OK);
    }

    public static SupportHookDecision redact(String payload, String reasonCode) {
        return new SupportHookDecision(SupportHookAction.REDACT, payload, reasonCode, null, HttpStatus.OK);
    }

    public static SupportHookDecision block(String code, String message, HttpStatus status) {
        return new SupportHookDecision(SupportHookAction.BLOCK, null, code, message, status);
    }
}
