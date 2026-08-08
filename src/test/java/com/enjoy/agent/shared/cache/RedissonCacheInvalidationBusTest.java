package com.enjoy.agent.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.redisson.client.codec.StringCodec;

class RedissonCacheInvalidationBusTest {

    @Test
    void shouldPublishAndDispatchInvalidationMessages() {
        RedissonClient client = mock(RedissonClient.class);
        RTopic topic = mock(RTopic.class);
        when(client.getTopic("enjoy:cache-invalidation:agent-config-v4", StringCodec.INSTANCE))
                .thenReturn(topic);
        when(topic.addListener(eq(String.class), any(MessageListener.class))).thenReturn(17);
        ArgumentCaptor<MessageListener<String>> listenerCaptor = ArgumentCaptor.forClass(MessageListener.class);
        AtomicReference<String> received = new AtomicReference<>();
        RedissonCacheInvalidationBus bus = new RedissonCacheInvalidationBus(client);

        bus.subscribe("agent-config-v4", received::set);
        verify(topic).addListener(eq(String.class), listenerCaptor.capture());
        listenerCaptor.getValue().onMessage("channel", "7:42");
        bus.publish("agent-config-v4", "7:42");

        assertThat(received).hasValue("7:42");
        verify(topic).publish("7:42");

        bus.close();
        verify(topic).removeListener(17);
    }
}
