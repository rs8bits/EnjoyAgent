package com.enjoy.agent.shared.exception;

import com.enjoy.agent.shared.api.ApiResponse;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理业务异常。
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<ApiError>> handleApiException(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiResponse.failure(new ApiError(ex.getCode(), ex.getMessage()), ex.getMessage()));
    }

    /**
     * 处理参数校验失败异常。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<ApiError>> handleValidationException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));

        return ResponseEntity.badRequest()
                .body(ApiResponse.failure(new ApiError("VALIDATION_ERROR", message), message));
    }

    /**
     * 兜底处理未预期异常。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<ApiError>> handleUnexpectedException(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure(
                        new ApiError("INTERNAL_SERVER_ERROR", "Unexpected server error"),
                        "Unexpected server error"
                ));
    }

    /**
     * 把字段级校验错误格式化成统一文本。
     */
    private String formatFieldError(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }
}
