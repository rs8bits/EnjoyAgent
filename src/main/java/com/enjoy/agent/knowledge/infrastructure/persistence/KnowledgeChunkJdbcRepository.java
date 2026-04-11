package com.enjoy.agent.knowledge.infrastructure.persistence;

import com.enjoy.agent.knowledge.application.KnowledgeChunkRow;
import com.enjoy.agent.knowledge.application.KnowledgeChunkSearchDocument;
import com.enjoy.agent.knowledge.application.RetrievedKnowledgeChunk;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 知识切片 JDBC 仓储，负责 vector 列的写入和检索。
 */
@Repository
public class KnowledgeChunkJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeChunkJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 批量写入知识切片。
     */
    public void batchInsert(Long tenantId, Long knowledgeBaseId, Long documentId, List<KnowledgeChunkRow> rows) {
        String sql = """
                INSERT INTO knowledge_chunk
                    (tenant_id, knowledge_base_id, document_id, chunk_index, content, embedding, created_at, updated_at)
                VALUES
                    (?, ?, ?, ?, ?, CAST(? AS vector), NOW(), NOW())
                """;

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                KnowledgeChunkRow row = rows.get(i);
                ps.setLong(1, tenantId);
                ps.setLong(2, knowledgeBaseId);
                ps.setLong(3, documentId);
                ps.setInt(4, row.chunkIndex());
                ps.setString(5, row.content());
                ps.setString(6, toVectorLiteral(row.embedding()));
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    /**
     * 按向量相似度检索知识切片。
     */
    public List<RetrievedKnowledgeChunk> searchTopK(Long knowledgeBaseId, float[] queryEmbedding, int topK) {
        String vectorLiteral = toVectorLiteral(queryEmbedding);
        String sql = """
                SELECT c.id,
                       c.document_id,
                       d.file_name AS document_name,
                       c.chunk_index,
                       c.content,
                       1 - (c.embedding <=> CAST(? AS vector)) AS score
                FROM knowledge_chunk c
                LEFT JOIN knowledge_document d ON d.id = c.document_id
                WHERE c.knowledge_base_id = ?
                ORDER BY c.embedding <=> CAST(? AS vector)
                LIMIT ?
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new RetrievedKnowledgeChunk(
                        rs.getLong("id"),
                        rs.getLong("document_id"),
                        rs.getString("document_name"),
                        rs.getInt("chunk_index"),
                        rs.getString("content"),
                        rs.getDouble("score"),
                        null,
                        null,
                        null,
                        null,
                        null
                ),
                vectorLiteral,
                knowledgeBaseId,
                vectorLiteral,
                topK
        );
    }

    /**
     * 查询某个文档下的全部切片，用于重建 Elasticsearch 索引。
     */
    public List<KnowledgeChunkSearchDocument> findSearchDocumentsByDocumentId(Long documentId) {
        String sql = """
                SELECT c.id AS chunk_id,
                       c.tenant_id,
                       c.knowledge_base_id,
                       c.document_id,
                       d.file_name AS document_name,
                       c.chunk_index,
                       c.content,
                       c.embedding::text AS embedding_literal,
                       c.created_at,
                       c.updated_at
                FROM knowledge_chunk c
                JOIN knowledge_document d ON d.id = c.document_id
                WHERE c.document_id = ?
                ORDER BY c.chunk_index ASC
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new KnowledgeChunkSearchDocument(
                        rs.getLong("chunk_id"),
                        rs.getLong("tenant_id"),
                        rs.getLong("knowledge_base_id"),
                        rs.getLong("document_id"),
                        rs.getString("document_name"),
                        rs.getInt("chunk_index"),
                        rs.getString("content"),
                        parseVectorLiteral(rs.getString("embedding_literal")),
                        toInstant(rs.getTimestamp("created_at").toInstant()),
                        toInstant(rs.getTimestamp("updated_at").toInstant())
                ),
                documentId
        );
    }

    /**
     * 把 float 数组转成 pgvector 可以识别的字面量。
     */
    private String toVectorLiteral(float[] values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(values[i]);
        }
        builder.append(']');
        return builder.toString();
    }

    private float[] parseVectorLiteral(String literal) {
        if (literal == null || literal.isBlank()) {
            return new float[0];
        }
        String normalized = literal.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            return new float[0];
        }
        String[] parts = normalized.split(",");
        float[] values = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Float.parseFloat(parts[i].trim());
        }
        return values;
    }

    private Instant toInstant(Instant instant) {
        return instant;
    }
}
