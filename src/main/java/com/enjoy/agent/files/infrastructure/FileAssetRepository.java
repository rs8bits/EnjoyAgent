package com.enjoy.agent.files.infrastructure;

import com.enjoy.agent.files.application.FileAsset;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FileAssetRepository {
  private final JdbcTemplate db;
  private static final String SELECT =
      """
      SELECT f.*,a.task_id,a.step_key FROM file_asset f
      LEFT JOIN task_artifact a ON a.file_id=f.id
      """;

  public FileAssetRepository(JdbcTemplate db) {
    this.db = db;
  }

  public void lockOwner(long tenant, long user) {
    db.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "files:" + tenant + ":" + user);
  }

  public long storedBytes(long tenant, long user) {
    return db.queryForObject(
        "SELECT COALESCE(SUM(size_bytes),0) FROM file_asset WHERE tenant_id=? AND user_id=? AND"
            + " status IN ('UPLOADING','READY')",
        Long.class,
        tenant,
        user);
  }

  public long count(long tenant, long user) {
    return db.queryForObject(
        "SELECT COUNT(*) FROM file_asset WHERE tenant_id=? AND user_id=? AND status IN"
            + " ('UPLOADING','READY')",
        Long.class,
        tenant,
        user);
  }

  public void insert(
      UUID id,
      long tenant,
      long user,
      String name,
      String type,
      long size,
      String hash,
      String bucket,
      String key,
      String kind) {
    db.update(
        """
INSERT INTO file_asset(id,tenant_id,user_id,original_name,media_type,size_bytes,sha256,bucket,object_key,kind,status)
VALUES(?,?,?,?,?,?,?,?,?,?,'UPLOADING') ON CONFLICT(id) DO UPDATE SET status='UPLOADING',updated_at=NOW() WHERE file_asset.status<>'READY'
""",
        id,
        tenant,
        user,
        name,
        type,
        size,
        hash,
        bucket,
        key,
        kind);
  }

  public Map<String, Object> reservation(UUID id, long tenant, long user) {
    var rows =
        db.queryForList(
            "SELECT status,size_bytes,sha256 FROM file_asset WHERE id=? AND tenant_id=? AND"
                + " user_id=?",
            id,
            tenant,
            user);
    return rows.isEmpty() ? Map.of() : rows.getFirst();
  }

  public List<Map<String, Object>> abandonedUploads() {
    return db.queryForList(
        "SELECT id,bucket,object_key FROM file_asset WHERE status='FAILED' OR (status='UPLOADING'"
            + " AND updated_at<NOW()-INTERVAL '1 hour') ORDER BY created_at LIMIT 50");
  }

  public void status(UUID id, String status) {
    db.update("UPDATE file_asset SET status=?,updated_at=NOW() WHERE id=?", status, id);
  }

  public Optional<FileAsset> find(UUID id, long tenant, long user) {
    return db
        .query(
            SELECT + " WHERE f.id=? AND f.tenant_id=? AND f.user_id=? AND f.status='READY'",
            this::map,
            id,
            tenant,
            user)
        .stream()
        .findFirst();
  }

  public List<FileAsset> list(long tenant, long user, int limit, int offset) {
    return db.query(
        SELECT
            + " WHERE f.tenant_id=? AND f.user_id=? AND f.status='READY' ORDER BY f.created_at"
            + " DESC,f.id LIMIT ? OFFSET ?",
        this::map,
        tenant,
        user,
        limit,
        offset);
  }

  public long readyCount(long tenant, long user) {
    return db.queryForObject(
        "SELECT COUNT(*) FROM file_asset WHERE tenant_id=? AND user_id=? AND status='READY'",
        Long.class,
        tenant,
        user);
  }

  public List<FileAsset> artifacts(UUID task, long tenant, long user) {
    return db.query(
        SELECT
            + " WHERE a.task_id=? AND f.tenant_id=? AND f.user_id=? AND f.status='READY' ORDER BY"
            + " f.original_name",
        this::map,
        task,
        tenant,
        user);
  }

  public void artifact(UUID file, UUID task, UUID execution, String step, UUID output) {
    db.update(
        "INSERT INTO task_artifact(file_id,task_id,execution_id,step_key,output_id)"
            + " VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
        file,
        task,
        execution,
        step,
        output);
    db.update(
        "INSERT INTO chat_message_file(message_id,file_id,kind) SELECT chat_message_id,?,'ARTIFACT'"
            + " FROM sandbox_task WHERE id=? AND chat_message_id IS NOT NULL ON CONFLICT DO"
            + " NOTHING",
        file,
        task);
  }

  private FileAsset map(ResultSet rs, int row) throws SQLException {
    return new FileAsset(
        rs.getObject("id", UUID.class),
        rs.getString("original_name"),
        rs.getString("media_type"),
        rs.getLong("size_bytes"),
        rs.getString("sha256"),
        rs.getString("kind"),
        rs.getString("status"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getObject("task_id", UUID.class),
        rs.getString("step_key"),
        rs.getString("bucket"),
        rs.getString("object_key"));
  }
}
