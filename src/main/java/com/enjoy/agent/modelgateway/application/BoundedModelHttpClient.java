package com.enjoy.agent.modelgateway.application;

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

/** Caps synchronous and streaming model responses before framework codecs aggregate them. */
final class BoundedModelHttpClient extends HttpClient {

    private final HttpClient delegate;
    private final long maxResponseBytes;

    BoundedModelHttpClient(HttpClient delegate, long maxResponseBytes) {
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
            return new BoundedSubscriber<>(downstream.apply(responseInfo), maxResponseBytes);
        };
    }

    private void requireAllowedLength(HttpHeaders headers) {
        headers.firstValueAsLong("Content-Length").ifPresent(length -> {
            if (length > maxResponseBytes) {
                throw new ModelResponseLimitException(maxResponseBytes);
            }
        });
    }

    private static final class BoundedSubscriber<T> implements HttpResponse.BodySubscriber<T> {

        private final HttpResponse.BodySubscriber<T> downstream;
        private final long maxBytes;
        private Flow.Subscription subscription;
        private long received;
        private boolean terminated;

        private BoundedSubscriber(HttpResponse.BodySubscriber<T> downstream, long maxBytes) {
            this.downstream = downstream;
            this.maxBytes = maxBytes;
        }

        @Override public CompletionStage<T> getBody() { return downstream.getBody(); }

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
            received += buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if (received > maxBytes) {
                terminated = true;
                if (subscription != null) {
                    subscription.cancel();
                }
                downstream.onError(new ModelResponseLimitException(maxBytes));
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
