package com.enjoy.agent.mcp.application;

import com.enjoy.agent.shared.exception.ApiException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;

/** Enforces a total wall-clock deadline around blocking OAuth HTTP operations. */
final class McpHttpDeadline {

    private McpHttpDeadline() {
    }

    static <T> T call(int timeoutSeconds, Callable<T> operation, String errorCode, String timeoutMessage) {
        FutureTask<T> task = new FutureTask<>(operation);
        Thread worker = Thread.ofVirtual().name("mcp-oauth-http").start(task);
        try {
            return task.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            task.cancel(true);
            worker.interrupt();
            throw new ApiException(errorCode, timeoutMessage, HttpStatus.GATEWAY_TIMEOUT);
        } catch (InterruptedException ex) {
            task.cancel(true);
            worker.interrupt();
            Thread.currentThread().interrupt();
            throw new ApiException(errorCode, "OAuth HTTP request was cancelled", HttpStatus.REQUEST_TIMEOUT);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof ApiException apiException) {
                throw apiException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new ApiException(errorCode, "OAuth HTTP request failed", HttpStatus.BAD_GATEWAY);
        }
    }
}
