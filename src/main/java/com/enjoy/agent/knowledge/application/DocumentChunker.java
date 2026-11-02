package com.enjoy.agent.knowledge.application;

import com.enjoy.agent.shared.exception.ApiException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
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

        List<Integer> boundaries = semanticBoundaries(normalizedText);
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalizedText.length()) {
            int end = Math.min(start + chunkSize, normalizedText.length());
            if (end < normalizedText.length()) {
                int boundary = -1;
                for (int candidate : boundaries) {
                    if (candidate > end) break;
                    if (candidate >= start + chunkSize / 2) boundary = candidate;
                }
                if (boundary > start) end = boundary;
                // Keep UTF-16 surrogate pairs intact at the hard fallback boundary.
                if (end > start && Character.isHighSurrogate(normalizedText.charAt(end - 1))) end--;
            }
            String chunk = normalizedText.substring(start, end).trim();
            if (!chunk.isBlank()) {
                if (chunks.size() >= knowledgeProperties.getMaxChunksPerDocument()) {
                    throw new ApiException(
                            "DOCUMENT_CHUNK_LIMIT_EXCEEDED",
                            "Document produces more chunks than allowed",
                            HttpStatus.PAYLOAD_TOO_LARGE
                    );
                }
                chunks.add(chunk);
            }
            if (end == normalizedText.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
            if (start < normalizedText.length() && Character.isLowSurrogate(normalizedText.charAt(start))) start++;
        }
        return chunks;
    }

    /**
     * 先压缩连续空白，避免切片里出现太多无意义换行。
     */
    private List<Integer> semanticBoundaries(String text) {
        List<Integer> boundaries = new ArrayList<>();
        boolean fenced = false;
        int offset = 0;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.startsWith("```") || line.startsWith("~~~")) fenced = !fenced;
            offset = Math.min(text.length(), offset + lines[i].length() + 1);
            boolean tableContinues = line.contains("|") && i + 1 < lines.length && lines[i + 1].contains("|");
            if (!fenced && !tableContinues) boundaries.add(offset);
        }
        return boundaries;
    }

    public record ChunkLocation(Integer sourceStart, Integer sourceEnd, Integer pageNumber, String heading) {}

    public List<ChunkLocation> locate(String text, List<String> chunks) {
        String normalized = normalizeText(text);
        List<ChunkLocation> locations = new ArrayList<>();
        int cursor = 0;
        for (String chunk : chunks) {
            int start = normalized.indexOf(chunk, cursor);
            if (start < 0) throw new IllegalArgumentException("Chunk is not in source text");
            String prefix = normalized.substring(0, start);
            int page = 1 + (int) prefix.chars().filter(c -> c == '\f').count();
            var matcher = java.util.regex.Pattern.compile("(?m)^#{1,6} +(.+)$").matcher(normalized.substring(0, Math.min(normalized.length(), start + chunk.length())));
            String heading = null;
            while (matcher.find()) { if (matcher.start() > start && heading != null) break; heading = matcher.group(1).strip(); }
            locations.add(new ChunkLocation(start, start + chunk.length(), normalized.indexOf('\f') >= 0 ? page : null,
                    heading == null ? null : heading.substring(0, Math.min(heading.length(), 500))));
            cursor = start + 1;
        }
        return locations;
    }

    private String normalizeText(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }
}
