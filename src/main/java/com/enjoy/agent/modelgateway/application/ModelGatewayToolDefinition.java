package com.enjoy.agent.modelgateway.application;

/**
 * 提供给模型的工具定义。
 */
public record ModelGatewayToolDefinition(
        String name,
        String description,
        String inputSchemaJson
) {
}
