package com.enjoy.agent.modelgateway.application;

/**
 * 模型返回的工具调用请求。
 */
public record ModelGatewayToolCall(
        String id,
        String name,
        String argumentsJson
) {
}
