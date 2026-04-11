package com.enjoy.agent.knowledge.application;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 文档切片器。
 */
@Component
public class DocumentChunker {

    private final KnowledgeProperties knowledgeProperties;

    public DocumentChunker(KnowledgeProperties knowledgeProperties) {
        this.knowledgeProperties = knowledgeProperties;
    }

    /**
     * 按字符长度切分文本，并保留固定 overlap。
     */
    public List<String> chunk(String text) {
        String normalizedText = normalizeText(text);
        int chunkSize = knowledgeProperties.getChunkSize();
        int overlap = Math.min(knowledgeProperties.getChunkOverlap(), chunkSize - 1);

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalizedText.length()) {
            int end = Math.min(start + chunkSize, normalizedText.length());
            String chunk = normalizedText.substring(start, end).trim();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
            if (end == normalizedText.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return chunks;
    }

    /**
     * 先压缩连续空白，避免切片里出现太多无意义换行。
     */
    private String normalizeText(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }
}
