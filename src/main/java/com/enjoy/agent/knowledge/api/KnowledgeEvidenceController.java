package com.enjoy.agent.knowledge.api;

import com.enjoy.agent.shared.api.ApiResponse;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.CurrentUserContext;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Exact evidence plus bounded neighboring paragraphs, filtered by tenant and READY document. */
@RestController
@RequestMapping("/api/knowledge/chunks")
public class KnowledgeEvidenceController {
  private final JdbcTemplate db;

  public KnowledgeEvidenceController(JdbcTemplate db) {
    this.db = db;
  }

  @GetMapping("/{id}")
  public ApiResponse<Map<String, Object>> evidence(@PathVariable Long id) {
    Long tenant = CurrentUserContext.requireCurrentUser().tenantId();
    var rows =
        db.queryForList(
            """
SELECT c.id,c.document_id,c.knowledge_base_id,c.chunk_index,c.content,c.source_start,c.source_end,c.page_number,c.heading,d.file_name
FROM knowledge_chunk c JOIN knowledge_document d ON c.document_id=d.id
WHERE c.id=? AND c.tenant_id=? AND d.tenant_id=? AND d.status='READY'
""",
            id,
            tenant,
            tenant);
    if (rows.isEmpty())
      throw new ApiException("KNOWLEDGE_SOURCE_NOT_FOUND", "来源已删除或无权访问", HttpStatus.NOT_FOUND);
    var evidence = new LinkedHashMap<>(rows.getFirst());
    int index = ((Number) evidence.get("chunk_index")).intValue();
    evidence.put(
        "paragraphs",
        db.queryForList(
            """
SELECT id,chunk_index,LEFT(content,4000) AS content FROM knowledge_chunk
WHERE document_id=? AND tenant_id=? AND chunk_index BETWEEN ? AND ? ORDER BY chunk_index
""",
            evidence.get("document_id"),
            tenant,
            Math.max(0, index - 1),
            index + 1));
    return ApiResponse.success(evidence);
  }
}
