package com.enjoy.agent.knowledge.application;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Small chunks recall precisely; bounded neighbors restore the paragraph around each hit. */
@Service
public class KnowledgeEvidenceService {
  private final JdbcTemplate db;

  public KnowledgeEvidenceService(JdbcTemplate db) {
    this.db = db;
  }

  public String context(Long knowledgeBaseId, Long chunkId) {
    var rows =
        db.queryForList(
            """
            SELECT c.document_id,c.chunk_index,c.page_number,c.heading FROM knowledge_chunk c
            JOIN knowledge_document d ON d.id=c.document_id
            WHERE c.id=? AND c.knowledge_base_id=? AND d.status='READY'
            """,
            chunkId,
            knowledgeBaseId);
    if (rows.isEmpty()) return "";
    var source = rows.getFirst();
    int index = ((Number) source.get("chunk_index")).intValue();
    var neighbors =
        db.queryForList(
            """
            SELECT LEFT(content,1600) AS content FROM knowledge_chunk
            WHERE document_id=? AND knowledge_base_id=? AND chunk_index BETWEEN ? AND ? AND id<>?
            ORDER BY chunk_index LIMIT 2
            """,
            source.get("document_id"),
            knowledgeBaseId,
            Math.max(0, index - 1),
            index + 1,
            chunkId);
    StringBuilder result = new StringBuilder();
    if (source.get("page_number") != null)
      result.append("页码：").append(source.get("page_number")).append("\n");
    if (source.get("heading") != null)
      result.append("章节：").append(source.get("heading")).append("\n");
    for (var neighbor : neighbors)
      result.append("相邻段落（同一来源）：\n").append(neighbor.get("content")).append("\n");
    return result.toString();
  }
}
