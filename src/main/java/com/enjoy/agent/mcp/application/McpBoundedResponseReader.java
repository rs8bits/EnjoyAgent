package com.enjoy.agent.mcp.application;

import com.enjoy.agent.shared.exception.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;

/** Reads an HTTP response without ever buffering more than the configured byte limit. */
final class McpBoundedResponseReader {

    private McpBoundedResponseReader() {
    }

    static String readUtf8(
            ClientHttpResponse response,
            int maxBytes,
            String errorCode,
            String tooLargeMessage
    ) throws IOException {
        long declaredLength = response.getHeaders().getContentLength();
        if (declaredLength > maxBytes) {
            throw tooLarge(errorCode, tooLargeMessage);
        }
        try (InputStream input = response.getBody()) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw tooLarge(errorCode, tooLargeMessage);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static ApiException tooLarge(String errorCode, String message) {
        return new ApiException(errorCode, message, HttpStatus.BAD_GATEWAY);
    }
}
