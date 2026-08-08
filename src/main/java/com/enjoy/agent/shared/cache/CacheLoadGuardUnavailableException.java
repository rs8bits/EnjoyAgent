package com.enjoy.agent.shared.cache;

/**
 * 分布式回源保护自身不可用，不代表业务 loader 执行失败。
 */
public final class CacheLoadGuardUnavailableException extends RuntimeException {

    public CacheLoadGuardUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
