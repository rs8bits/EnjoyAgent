package com.enjoy.agent.modelgateway.application;

/** Internal transport signal translated to a stable, non-sensitive API error. */
final class ModelResponseLimitException extends RuntimeException {

    ModelResponseLimitException(long maxValue) {
        super("Model response exceeded configured limit: " + maxValue);
    }
}
