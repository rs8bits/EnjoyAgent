package com.enjoy.agent.knowledge.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 文档导入消息的版本化 JSON 编解码器。
 */
@Component
public class KnowledgeIngestMessageCodec {

    private final ObjectMapper objectMapper;

    public KnowledgeIngestMessageCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(KnowledgeIngestMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to encode knowledge ingest message", ex);
        }
    }

    public KnowledgeIngestMessage decode(byte[] payload) {
        try {
            return objectMapper.readValue(payload, KnowledgeIngestMessage.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid knowledge ingest message", ex);
        }
    }
}
