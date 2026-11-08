package cn.review.summary;

import cn.review.chat.ReaderAiClient;
import cn.review.common.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SummaryService {
  final JdbcTemplate db;
  final TransactionTemplate tx;
  final ObjectMapper json;
  final ReaderAiClient ai;
  final IdempotencyService idem;

  public SummaryService(
      JdbcTemplate db,
      TransactionTemplate tx,
      ObjectMapper json,
      ReaderAiClient ai,
      IdempotencyService idem) {
    this.db = db;
    this.tx = tx;
    this.json = json;
    this.ai = ai;
    this.idem = idem;
  }

  static String id() {
    return UUID.randomUUID().toString();
  }

  Map<String, Object> material(String owner, String material, boolean lock) {
    var ids =
        db.queryForList(
            "SELECT c.id FROM courses c JOIN materials m ON m.course_id=c.id WHERE m.id=? AND"
                + " c.owner_id=? AND c.status='ACTIVE' AND m.status='ACTIVE'",
            String.class,
            material,
            owner);
    if (ids.isEmpty()) throw ApiException.missing();
    if (lock)
      db.queryForList(
          "SELECT id FROM courses WHERE id=? AND status='ACTIVE' FOR UPDATE", ids.get(0));
    var rows =
        db.queryForList(
            "SELECT * FROM materials WHERE id=? AND status='ACTIVE'" + (lock ? " FOR UPDATE" : ""),
            material);
    if (rows.isEmpty()) throw ApiException.missing();
    // Recheck the course after acquiring its lock.
    if (db.queryForObject(
            "SELECT COUNT(*) FROM courses WHERE id=? AND owner_id=? AND status='ACTIVE'",
            Integer.class,
            ids.get(0),
            owner)
        == 0) throw ApiException.missing();
    return rows.get(0);
  }

  List<SummaryContent.Page> pages(String material) {
    return db.query(
        "SELECT id,page_no,text_revision,is_hidden,text_content FROM slides WHERE material_id=? AND"
            + " deleted_at IS NULL ORDER BY page_no",
        (rs, n) ->
            new SummaryContent.Page(
                rs.getString(1),
                rs.getInt(2),
                (Long) rs.getObject(3),
                rs.getBoolean(4),
                Objects.toString(rs.getString(5), "")),
        material);
  }

  String fingerprint(Map<String, Object> material, List<SummaryContent.Page> pages) {
    return SummaryContent.fingerprint(json, List.of(material.get("lifecycle_version"), pages));
  }

  Map<String, Object> head(String material, boolean create) {
    if (create) db.update("INSERT IGNORE INTO material_summaries(material_id) VALUES(?)", material);
    var rows =
        db.queryForList(
            "SELECT * FROM material_summaries WHERE material_id=?" + (create ? " FOR UPDATE" : ""),
            material);
    return rows.isEmpty() ? Map.of("row_version", 1L) : rows.get(0);
  }

  void expected(Map<String, Object> head, long expected) {
    if (((Number) head.get("row_version")).longValue() != expected)
      throw new ApiException(409, "VERSION_CONFLICT", "摘要已被更新，请刷新后再操作；编辑内容仍可保留");
  }

  Map<String, Object> row(String owner, String version, boolean lock) {
    var ids =
        db.queryForList(
            "SELECT material_id FROM material_summary_versions WHERE id=?", String.class, version);
    if (ids.isEmpty()) throw ApiException.missing();
    material(owner, ids.get(0), lock);
    return db.queryForMap(
        "SELECT * FROM material_summary_versions WHERE id=?" + (lock ? " FOR UPDATE" : ""),
        version);
  }

  Version view(
      Map<String, Object> row, Map<String, Object> material, List<SummaryContent.Page> pages) {
    String version = (String) row.get("id");
    int total =
        db.queryForObject(
            "SELECT COUNT(*) FROM material_summary_parts WHERE version_id=?",
            Integer.class,
            version);
    int done =
        db.queryForObject(
            "SELECT COUNT(*) FROM material_summary_parts WHERE version_id=? AND status='SUCCEEDED'",
            Integer.class,
            version);
    return new Version(
        version,
        (String) row.get("material_id"),
        ((Number) row.get("version_no")).intValue(),
        (String) row.get("origin"),
        (String) row.get("status"),
        (String) row.get("markdown"),
        !row.get("source_fingerprint").equals(fingerprint(material, pages)),
        ((Number) row.get("total_pages")).intValue(),
        ((Number) row.get("empty_pages")).intValue(),
        done,
        total,
        (String) row.get("error_code"),
        (String) row.get("error_message"),
        row.get("created_at").toString());
  }

  public Version version(String owner, String id) {
    var r = row(owner, id, false);
    var m = material(owner, (String) r.get("material_id"), false);
    return view(r, m, pages((String) r.get("material_id")));
  }

  public State state(String owner, String material) {
    var m = material(owner, material, false);
    var pages = pages(material);
    var head = head(material, false);
    int requests = 0;
    String blocked = "";
    try {
      ready(material, pages);
      var chunks = SummaryContent.split(pages);
      requests = chunks.size() == 1 ? 1 : chunks.size() + 1;
    } catch (ApiException e) {
      blocked = e.getMessage();
    }
    var versions =
        db
            .queryForList(
                "SELECT * FROM material_summary_versions WHERE material_id=? ORDER BY version_no"
                    + " DESC LIMIT 50",
                material)
            .stream()
            .map(r -> view(r, m, pages))
            .toList();
    Version current =
        head.get("current_version_id") == null
            ? null
            : version(owner, (String) head.get("current_version_id"));
    return new State(
        material,
        (String) m.get("course_id"),
        (String) m.get("display_name"),
        ((Number) head.get("row_version")).longValue(),
        current,
        versions,
        requests,
        ai.remaining(owner),
        ai.available(),
        blocked);
  }

  void ready(String material, List<SummaryContent.Page> pages) {
    if (db.queryForObject(
                "SELECT COUNT(*) FROM material_stages WHERE material_id=? AND stage='TEXT' AND"
                    + " published_revision IS NOT NULL",
                Integer.class,
                material)
            == 0
        || pages.isEmpty()
        || pages.stream().anyMatch(p -> p.revision() == null))
      throw new ApiException(409, "SUMMARY_TEXT_NOT_READY", "请等待课件文字提取完成后生成摘要");
  }

  public Version generate(String owner, String material, String key) {
    material(owner, material, false);
    var ack =
        idem.execute(
            owner,
            "GENERATE_SUMMARY",
            material,
            key,
            Map.of("materialId", material),
            202,
            Ack.class,
            () -> {
              var m = material(owner, material, true);
              var head = head(material, true);
              var active =
                  db.queryForList(
                      "SELECT id FROM material_summary_versions WHERE material_id=? AND status IN"
                          + " ('QUEUED','RUNNING')",
                      String.class,
                      material);
              if (!active.isEmpty()) return new Ack(active.get(0));
              if (!ai.available()) throw new ApiException(503, "AI_DISABLED", "请先开启AI功能");
              var pages = pages(material);
              ready(material, pages);
              var chunks = SummaryContent.split(pages);
              int requests = chunks.size() == 1 ? 1 : chunks.size() + 1;
              if (ai.remaining(owner) < requests)
                throw new ApiException(
                    429, "AI_QUOTA_EXCEEDED", "本课件预计需要" + requests + "次请求，当前额度不足，请调整额度或明日再试");
              String version = id();
              int number = nextNumber(material);
              int empty = (int) pages.stream().filter(p -> p.text().isBlank()).count();
              db.update(
                  "INSERT INTO"
                      + " material_summary_versions(id,material_id,version_no,origin,status,base_head_version,source_fingerprint,source_snapshot,total_pages,empty_pages,model)"
                      + " VALUES(?,?,?,'AI','QUEUED',?,?,?,?,?,?)",
                  version,
                  material,
                  number,
                  head.get("row_version"),
                  fingerprint(m, pages),
                  encode(pages),
                  pages.size(),
                  empty,
                  ai.model());
              int pos = 0;
              for (var chunk : chunks)
                db.update(
                    "INSERT INTO"
                        + " material_summary_parts(id,version_id,position_no,kind,input_snapshot)"
                        + " VALUES(?,?,?,?,?)",
                    id(),
                    version,
                    pos++,
                    chunks.size() == 1 ? "FINAL" : "PART",
                    encode(chunk));
              if (chunks.size() > 1)
                db.update(
                    "INSERT INTO"
                        + " material_summary_parts(id,version_id,position_no,kind,input_snapshot)"
                        + " VALUES(?,?,?,'FINAL',JSON_ARRAY())",
                    id(),
                    version,
                    pos);
              queueNext(owner, (String) m.get("course_id"), version);
              return new Ack(version);
            });
    return version(owner, ack.id());
  }

  int nextNumber(String material) {
    return db.queryForObject(
        "SELECT COALESCE(MAX(version_no),0)+1 FROM material_summary_versions WHERE material_id=?",
        Integer.class,
        material);
  }

  void queueNext(String owner, String course, String version) {
    var rows =
        db.queryForList(
            "SELECT id FROM material_summary_parts WHERE version_id=? AND status IN"
                + " ('WAITING','FAILED','CANCELLED') ORDER BY position_no LIMIT 1",
            String.class,
            version);
    if (rows.isEmpty()) return;
    String part = rows.get(0), job = id();
    db.update(
        "UPDATE material_summary_parts SET status='QUEUED',last_job_id=? WHERE id=?", job, part);
    db.update(
        "INSERT INTO"
            + " async_jobs(id,owner_id,course_id,type,target_id,dedup_key,input_snapshot,run_after,max_attempts)"
            + " VALUES(?,?,?,'MATERIAL_SUMMARY',?,?,JSON_OBJECT(),CURRENT_TIMESTAMP(3),1)",
        job,
        owner,
        course,
        part,
        "SUMMARY:" + job);
  }

  public Version retry(String owner, String version, String key) {
    row(owner, version, false);
    var ack =
        idem.execute(
            owner,
            "RETRY_SUMMARY",
            version,
            key,
            Map.of("id", version),
            202,
            Ack.class,
            () -> {
              var r = row(owner, version, true);
              String material = (String) r.get("material_id");
              if (!List.of("FAILED", "CANCELLED").contains(r.get("status")))
                throw new ApiException(409, "SUMMARY_NOT_RETRYABLE", "只有失败或已停止的摘要可以恢复");
              if (db.queryForObject(
                      "SELECT COUNT(*) FROM material_summary_versions WHERE material_id=? AND"
                          + " status IN ('QUEUED','RUNNING')",
                      Integer.class,
                      material)
                  > 0) throw new ApiException(409, "SUMMARY_BUSY", "本课件还有摘要生成中");
              verifySource(owner, r);
              if (!ai.available() || ai.remaining(owner) < 1)
                throw new ApiException(429, "AI_QUOTA_EXCEEDED", "AI未启用或今日额度不足，已完成进度保留");
              db.update(
                  "UPDATE material_summary_versions SET"
                      + " status='QUEUED',error_code=NULL,error_message=NULL,updated_at=CURRENT_TIMESTAMP(3)"
                      + " WHERE id=?",
                  version);
              queueNext(owner, (String) material(owner, material, false).get("course_id"), version);
              return new Ack(version);
            });
    return version(owner, ack.id());
  }

  public Version cancel(String owner, String version) {
    tx.executeWithoutResult(
        s -> {
          var r = row(owner, version, true);
          if (!List.of("QUEUED", "RUNNING").contains(r.get("status"))) return;
          db.update(
              "UPDATE async_jobs j JOIN material_summary_parts p ON p.last_job_id=j.id SET"
                  + " j.status='CANCELLED',j.finished_at=CURRENT_TIMESTAMP(3),j.lease_until=NULL"
                  + " WHERE p.version_id=? AND j.status IN ('QUEUED','RUNNING')",
              version);
          db.update(
              "UPDATE job_attempts a JOIN async_jobs j ON j.id=a.job_id JOIN material_summary_parts"
                  + " p ON p.last_job_id=j.id SET"
                  + " a.outcome='CANCELLED',a.finished_at=CURRENT_TIMESTAMP(3) WHERE p.version_id=?"
                  + " AND a.outcome='RUNNING'",
              version);
          db.update(
              "UPDATE material_summary_parts SET status='CANCELLED' WHERE version_id=? AND status"
                  + " IN ('QUEUED','RUNNING')",
              version);
          db.update(
              "UPDATE material_summary_versions SET"
                  + " status='CANCELLED',error_code='SUMMARY_CANCELLED',error_message='已停止，已发送的请求仍可能计费',updated_at=CURRENT_TIMESTAMP(3)"
                  + " WHERE id=?",
              version);
        });
    return version(owner, version);
  }

  void verifySource(String owner, Map<String, Object> row) {
    String material = (String) row.get("material_id");
    var m = material(owner, material, false);
    if (!row.get("source_fingerprint").equals(fingerprint(m, pages(material))))
      throw new ApiException(409, "SOURCE_CHANGED", "课件文字已变化，请重新生成摘要");
  }

  public Version edit(String owner, String material, Edit input, String key) {
    String markdown = SummaryContent.editableMarkdown(input.markdown());
    material(owner, material, false);
    var ack =
        idem.execute(
            owner,
            "EDIT_SUMMARY",
            material,
            key,
            input,
            201,
            Ack.class,
            () -> {
              material(owner, material, true);
              var head = head(material, true);
              expected(head, input.expectedVersion());
              var base = row(owner, input.baseVersionId(), false);
              if (!material.equals(base.get("material_id"))
                  || !"SUCCEEDED".equals(base.get("status"))) throw ApiException.missing();
              String version = id();
              db.update(
                  "INSERT INTO"
                      + " material_summary_versions(id,material_id,version_no,origin,status,base_head_version,source_fingerprint,source_snapshot,total_pages,empty_pages,markdown)"
                      + " VALUES(?,?,?,'USER','SUCCEEDED',?,?,?,?,?,?)",
                  version,
                  material,
                  nextNumber(material),
                  head.get("row_version"),
                  base.get("source_fingerprint"),
                  base.get("source_snapshot"),
                  base.get("total_pages"),
                  base.get("empty_pages"),
                  markdown);
              db.update(
                  "UPDATE material_summaries SET current_version_id=?,row_version=row_version+1"
                      + " WHERE material_id=?",
                  version,
                  material);
              return new Ack(version);
            });
    return version(owner, ack.id());
  }

  public State adopt(String owner, String material, Adopt input, String key) {
    material(owner, material, false);
    idem.execute(
        owner,
        "ADOPT_SUMMARY",
        material,
        key,
        input,
        200,
        Ack.class,
        () -> {
          material(owner, material, true);
          var head = head(material, true);
          expected(head, input.expectedVersion());
          var r = row(owner, input.versionId(), false);
          if (!material.equals(r.get("material_id")) || !"SUCCEEDED".equals(r.get("status")))
            throw ApiException.missing();
          db.update(
              "UPDATE material_summaries SET current_version_id=?,row_version=row_version+1 WHERE"
                  + " material_id=?",
              input.versionId(),
              material);
          return new Ack(input.versionId());
        });
    return state(owner, material);
  }

  public Context context(String owner, String material, String version) {
    var r = row(owner, version, false);
    if (!material.equals(r.get("material_id")) || !"SUCCEEDED".equals(r.get("status")))
      throw ApiException.missing();
    var v = version(owner, version);
    return new Context(version, v.number(), v.origin(), v.markdown(), v.stale());
  }

  String encode(Object o) {
    try {
      return json.writeValueAsString(o);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  <T> T decode(Object value, TypeReference<T> type) {
    try {
      return json.readValue(value.toString(), type);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public record Context(
      String versionId, int number, String origin, String markdown, boolean stale) {}

  public record Edit(String baseVersionId, long expectedVersion, String markdown) {}

  public record Adopt(String versionId, long expectedVersion) {}

  public record Version(
      String id,
      String materialId,
      int number,
      String origin,
      String status,
      String markdown,
      boolean stale,
      int totalPages,
      int emptyPages,
      int completedSteps,
      int totalSteps,
      String errorCode,
      String errorMessage,
      String createdAt) {}

  public record State(
      String materialId,
      String courseId,
      String materialName,
      long version,
      Version current,
      List<Version> versions,
      int estimatedRequests,
      int remainingToday,
      boolean aiAvailable,
      String blockedReason) {}

  record Ack(String id) {}
}
