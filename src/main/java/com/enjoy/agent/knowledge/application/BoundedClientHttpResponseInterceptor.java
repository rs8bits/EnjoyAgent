package com.enjoy.agent.knowledge.application;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** Enforces a hard byte ceiling even when an upstream omits Content-Length. */
final class BoundedClientHttpResponseInterceptor implements ClientHttpRequestInterceptor {

    private final long maximumBytes;

    BoundedClientHttpResponseInterceptor(long maximumBytes) {
        this.maximumBytes = maximumBytes;
    }

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request,
            byte[] body,
            ClientHttpRequestExecution execution
    ) throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        long contentLength = response.getHeaders().getContentLength();
        if (contentLength > maximumBytes) {
            response.close();
            throw new IOException("Upstream response exceeds configured size limit");
        }
        return new BoundedResponse(response, maximumBytes);
    }

    private static final class BoundedResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final InputStream body;

        private BoundedResponse(ClientHttpResponse delegate, long maximumBytes) throws IOException {
            this.delegate = delegate;
            this.body = new BoundedInputStream(delegate.getBody(), maximumBytes);
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() {
            return body;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static final class BoundedInputStream extends FilterInputStream {

        private final long maximumBytes;
        private long consumed;

        private BoundedInputStream(InputStream inputStream, long maximumBytes) {
            super(inputStream);
            this.maximumBytes = maximumBytes;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) {
                record(1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) {
                record(read);
            }
            return read;
        }

        private void record(int count) throws IOException {
            consumed += count;
            if (consumed > maximumBytes) {
                throw new IOException("Upstream response exceeds configured size limit");
            }
        }
    }
}
