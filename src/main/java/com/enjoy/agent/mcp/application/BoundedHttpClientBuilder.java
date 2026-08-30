package com.enjoy.agent.mcp.application;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * JDK HTTP client builder that disables redirects and caps response bytes before the MCP
 * SDK aggregates JSON or SSE payloads in memory.
 */
final class BoundedHttpClientBuilder implements HttpClient.Builder {

    private final HttpClient.Builder delegate;
    private final long maxResponseBytes;

    BoundedHttpClientBuilder(long maxResponseBytes) {
        this.delegate = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER);
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public HttpClient.Builder cookieHandler(CookieHandler cookieHandler) {
        delegate.cookieHandler(cookieHandler);
        return this;
    }

    @Override
    public HttpClient.Builder connectTimeout(Duration duration) {
        delegate.connectTimeout(duration);
        return this;
    }

    @Override
    public HttpClient.Builder sslContext(SSLContext sslContext) {
        delegate.sslContext(sslContext);
        return this;
    }

    @Override
    public HttpClient.Builder sslParameters(SSLParameters sslParameters) {
        delegate.sslParameters(sslParameters);
        return this;
    }

    @Override
    public HttpClient.Builder executor(Executor executor) {
        delegate.executor(executor);
        return this;
    }

    @Override
    public HttpClient.Builder followRedirects(HttpClient.Redirect ignored) {
        delegate.followRedirects(HttpClient.Redirect.NEVER);
        return this;
    }

    @Override
    public HttpClient.Builder version(HttpClient.Version version) {
        delegate.version(version);
        return this;
    }

    @Override
    public HttpClient.Builder priority(int priority) {
        delegate.priority(priority);
        return this;
    }

    @Override
    public HttpClient.Builder proxy(ProxySelector proxySelector) {
        delegate.proxy(proxySelector);
        return this;
    }

    @Override
    public HttpClient.Builder authenticator(Authenticator authenticator) {
        delegate.authenticator(authenticator);
        return this;
    }

    @Override
    public HttpClient build() {
        return new BoundedHttpClient(delegate.followRedirects(HttpClient.Redirect.NEVER).build(), maxResponseBytes);
    }

    private static final class BoundedHttpClient extends HttpClient {

        private final HttpClient delegate;
        private final long maxResponseBytes;

        private BoundedHttpClient(HttpClient delegate, long maxResponseBytes) {
            this.delegate = delegate;
            this.maxResponseBytes = maxResponseBytes;
        }

        @Override public Optional<CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
        @Override public Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return delegate.proxy(); }
        @Override public SSLContext sslContext() { return delegate.sslContext(); }
        @Override public SSLParameters sslParameters() { return delegate.sslParameters(); }
        @Override public Optional<Authenticator> authenticator() { return delegate.authenticator(); }
        @Override public Version version() { return delegate.version(); }
        @Override public Optional<Executor> executor() { return delegate.executor(); }
        @Override public WebSocket.Builder newWebSocketBuilder() { return delegate.newWebSocketBuilder(); }
        @Override public void shutdown() { delegate.shutdown(); }
        @Override public boolean awaitTermination(Duration duration) throws InterruptedException {
            return delegate.awaitTermination(duration);
        }
        @Override public boolean isTerminated() { return delegate.isTerminated(); }
        @Override public void shutdownNow() { delegate.shutdownNow(); }
        @Override public void close() { delegate.close(); }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            return delegate.send(request, bounded(responseBodyHandler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            return delegate.sendAsync(request, bounded(responseBodyHandler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            return delegate.sendAsync(request, bounded(responseBodyHandler), pushPromiseHandler);
        }

        private <T> HttpResponse.BodyHandler<T> bounded(HttpResponse.BodyHandler<T> downstream) {
            return responseInfo -> {
                requireAllowedLength(responseInfo.headers());
                return new BoundedBodySubscriber<>(downstream.apply(responseInfo), maxResponseBytes);
            };
        }

        private void requireAllowedLength(HttpHeaders headers) {
            headers.firstValueAsLong("Content-Length").ifPresent(length -> {
                if (length > maxResponseBytes) {
                    throw new McpResponseLimitException(maxResponseBytes);
                }
            });
        }
    }

    private static final class BoundedBodySubscriber<T> implements HttpResponse.BodySubscriber<T> {

        private final HttpResponse.BodySubscriber<T> downstream;
        private final long maxBytes;
        private Flow.Subscription subscription;
        private long received;
        private boolean terminated;

        private BoundedBodySubscriber(HttpResponse.BodySubscriber<T> downstream, long maxBytes) {
            this.downstream = downstream;
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<T> getBody() {
            return downstream.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            downstream.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (terminated) {
                return;
            }
            long chunkBytes = buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            received += chunkBytes;
            if (received > maxBytes) {
                terminated = true;
                if (subscription != null) {
                    subscription.cancel();
                }
                downstream.onError(new McpResponseLimitException(maxBytes));
                return;
            }
            downstream.onNext(buffers);
        }

        @Override
        public void onError(Throwable throwable) {
            if (!terminated) {
                terminated = true;
                downstream.onError(throwable);
            }
        }

        @Override
        public void onComplete() {
            if (!terminated) {
                terminated = true;
                downstream.onComplete();
            }
        }
    }
}
