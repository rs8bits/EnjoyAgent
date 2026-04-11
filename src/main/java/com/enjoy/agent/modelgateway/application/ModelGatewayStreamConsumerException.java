package com.enjoy.agent.modelgateway.application;

/**
 * 模型流式输出过程中，消费端处理增量文本失败。
 */
public class ModelGatewayStreamConsumerException extends RuntimeException {

    public ModelGatewayStreamConsumerException(String message, Throwable cause) {
        super(message, cause);
    }
}
