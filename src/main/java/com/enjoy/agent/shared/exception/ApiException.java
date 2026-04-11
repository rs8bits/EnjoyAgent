package com.enjoy.agent.shared.exception;

import org.springframework.http.HttpStatus;

/**
 * 自定义业务异常。
 */
public class ApiException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    /**
     * 构造一个带错误码和 HTTP 状态码的业务异常。
     */
    public ApiException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
