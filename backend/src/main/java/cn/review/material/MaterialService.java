package cn.review.material;

import cn.review.common.*;
import java.io.IOException;
import java.nio.file.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MaterialService {
  final JdbcTemplate db;
  final TransactionTemplate tx;
  final MaterialStorage storage;
  private final IdempotencyService idempotency;
  private final PptValidator validator;
  private final cn.review.search.SearchCatalog catalog;

  public MaterialService(
      JdbcTemplate db,
      TransactionTemplate tx,
      MaterialStorage storage,
      IdempotencyService idempotency,
      PptValidator validator,
      cn.review.search.SearchCatalog catalog) {
    this.db = db;
    this.tx = tx;
    this.storage = storage;
    this.idempotency = idempotency;
    this.validator = validator;
    this.catalog = catalog;
  }

  void course(String owner, String id, boolean lock) {
    var rows =
        db.queryForList(
            "SELECT status FROM courses WHERE id=? AND owner_id=?" + (lock ? " FOR UPDATE" : ""),
            id,
            owner);
    if (rows.isEmpty() || !"ACTIVE".equals(rows.get(0).get("status"))) throw ApiException.missing();
  }

  MaterialRow row(String owner, String id, boolean allowDeleted) {
    var rows =
        db.query(
            "SELECT m.*,c.owner_id FROM materials m JOIN courses c ON c.id=m.course_id WHERE m.id=?"
                + " AND c.owner_id=? AND c.status='ACTIVE'"
                + (allowDeleted ? "" : " AND m.status='ACTIVE'"),
            this::readRow,
            id,
            owner);
    if (rows.isEmpty()) throw ApiException.missing();
    return rows.get(0);
  }

  MaterialRow lock(String owner, String id, boolean allowDeleted) {
    var initial = row(owner, id, allowDeleted);
    course(owner, initial.courseId(), true);
    db.queryForList("SELECT id FROM materials WHERE id=? FOR UPDATE", id);
    return row(owner, id, allowDeleted);
  }

  private MaterialRow readRow(ResultSet rs, int index) throws SQLException {
    return new MaterialRow(
        rs.getString("id"),
        rs.getString("course_id"),
        rs.getString("owner_id"),
        rs.getString("display_name"),
        rs.getLong("size_bytes"),
        rs.getInt("slide_count"),
        rs.getLong("row_version"),
        rs.getLong("lifecycle_version"),
        rs.getString("status"),
        rs.getString("mapping_status"),
        rs.getString("original_asset_id"),
        rs.getString("pdf_asset_id"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  public MaterialPage list(String owner, String course, int page, int size) {
    course(owner, course, false);
    pagination(page, size);
    var ids =
        db.queryForList(
            "SELECT id FROM materials WHERE course_id=? AND status='ACTIVE' ORDER BY created_at"
                + " DESC,id DESC LIMIT ? OFFSET ?",
            String.class,
            course,
            size,
            (page - 1) * size);
    return new MaterialPage(
        ids.stream().map(id -> get(owner, id)).toList(),
        page,
        size,
        db.queryForObject(
            "SELECT COUNT(*) FROM materials WHERE course_id=? AND status='ACTIVE'",
            Long.class,
            course));
  }

  public MaterialView get(String owner, String id) {
    MaterialRow row = row(owner, id, false);
    Map<String, StageView> stages = new LinkedHashMap<>();
    db.query(
        "SELECT * FROM material_stages WHERE material_id=? ORDER BY stage",
        rs -> {
          String stage = rs.getString("stage"), code = rs.getString("error_code");
          Long published = rs.getObject("published_revision", Long.class);
          stages.put(
              stage,
              new StageView(
                  rs.getString("status"),
                  published != null,
                  published,
                  rs.getLong("requested_revision"),
                  rs.getString("job_id"),
                  code == null ? null : new SafeError(code, rs.getString("error_message"), true)));
        },
        id);
    return new MaterialView(
        id,
        row.courseId(),
        row.name(),
        row.size(),
        1,
        row.pages(),
        row.version(),
        row.mapping(),
        stages,
        new Capabilities(
            true,
            "VERIFIED".equals(row.mapping()) && row.pdfAsset() != null,
            catalog.scope(owner, row.courseId()).stream().anyMatch(s -> s.materialId().equals(id))),
        url(row.originalAsset()),
        url(row.pdfAsset()),
        row.createdAt(),
        row.updatedAt());
  }

  public UploadResult upload(String owner, String course, String key, MultipartFile file)
      throws IOException {
    course(owner, course, false);
    String raw = Optional.ofNullable(file.getOriginalFilename()).orElse("").replace('\\', '/');
    String name = raw.substring(raw.lastIndexOf('/') + 1).strip().replaceAll("[\\p{Cntrl}]", "");
    name = ApiException.text(name, "文件名", 1, 200);
    if (!name.toLowerCase(Locale.ROOT).endsWith(".pptx"))
      throw new ApiException(415, "UNSUPPORTED_FILE_TYPE", "当前支持 .pptx 课件，请先转换旧版 .ppt 文件");
    if (file.getSize() > 20L * 1024 * 1024)
      throw new ApiException(413, "FILE_TOO_LARGE", "课件不能超过 20 MiB");
    Path incoming;
    try (var input = file.getInputStream()) {
      incoming = storage.incoming(input);
    }
    String[] moved = {null};
    try {
      var validated = validator.validate(incoming);
      String displayName = name;
      var result =
          idempotency.execute(
              owner,
              "UPLOAD_MATERIAL",
              course,
              key,
              Map.of("name", name, "sha256", validated.sha256(), "size", Files.size(incoming)),
              202,
              UploadResult.class,
              () -> {
                course(owner, course, true);
                String id = UUID.randomUUID().toString(),
                    directory = storage.materialKey(course, id);
                moved[0] = directory;
                try {
                  storage.move(incoming, directory + "/original.pptx");
                } catch (IOException error) {
                  throw new ApiException(503, "STORAGE_UNAVAILABLE", "原件保存失败，请稍后重试");
                }
                db.update(
                    "INSERT INTO materials(id,course_id,display_name,size_bytes,sha256,slide_count)"
                        + " VALUES(?,?,?,?,?,?)",
                    id,
                    course,
                    displayName,
                    file.getSize(),
                    validated.sha256(),
                    validated.slides().size());
                String original =
                    asset(
                        id,
                        "ORIGINAL",
                        directory + "/original.pptx",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation");
                db.update("UPDATE materials SET original_asset_id=? WHERE id=?", original, id);
                for (var slide : validated.slides())
                  db.update(
                      "INSERT INTO slides(id,material_id,page_no,source_slide_id,is_hidden,title)"
                          + " VALUES(?,?,?,?,?,?)",
                      UUID.randomUUID().toString(),
                      id,
                      slide.pageNumber(),
                      slide.sourceSlideId(),
                      slide.hidden(),
                      slide.title());
                List<JobRef> jobs = new ArrayList<>();
                for (String stage : List.of("TEXT", "PREVIEW")) {
                  String job =
                      enqueue(
                          owner,
                          course,
                          id,
                          stage.equals("TEXT") ? "TEXT_EXTRACT" : "PREVIEW_RENDER",
                          1,
                          1);
                  db.update(
                      "INSERT INTO material_stages(material_id,stage,status,job_id)"
                          + " VALUES(?,?,'QUEUED',?)",
                      id,
                      stage,
                      job);
                  jobs.add(
                      new JobRef(job, stage.equals("TEXT") ? "TEXT_EXTRACT" : "PREVIEW_RENDER"));
                }
                for (String stage : List.of("INDEX", "QUESTION_GENERATION"))
                  db.update(
                      "INSERT INTO material_stages(material_id,stage,status)"
                          + " VALUES(?,?,'NOT_APPLICABLE')",
                      id,
                      stage);
                return new UploadResult(id, "/api/materials/" + id, jobs);
              });
      row(owner, result.materialId(), false);
      return result;
    } catch (RuntimeException error) {
      if (moved[0] != null) storage.remove(moved[0]);
      throw error;
    } finally {
      Files.deleteIfExists(incoming);
    }
  }

  String enqueue(
      String owner, String course, String target, String type, long lifecycle, long revision) {
    String id = UUID.randomUUID().toString();
    String snapshot = "{\"lifecycleVersion\":" + lifecycle + ",\"revision\":" + revision + "}";
    db.update(
        "INSERT INTO"
            + " async_jobs(id,owner_id,course_id,type,target_id,dedup_key,input_snapshot,run_after)"
            + " VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP(3))",
        id,
        owner,
        course,
        type,
        target,
        type + ":" + target + ":" + lifecycle + ":" + revision,
        snapshot);
    return id;
  }

  String asset(String material, String kind, String key, String mime) {
    try {
      String id = UUID.randomUUID().toString();
      long size = Files.size(storage.resolve(key));
      db.update(
          "INSERT INTO file_assets(id,material_id,kind,storage_key,media_type,size_bytes)"
              + " VALUES(?,?,?,?,?,?)",
          id,
          material,
          kind,
          key,
          mime,
          size);
      return id;
    } catch (IOException error) {
      throw new ApiException(503, "STORAGE_UNAVAILABLE", "文件暂时不可用，请稍后重试");
    }
  }

  public DeleteResult delete(String owner, String id, long expectedVersion, String key) {
    row(owner, id, true);
    return idempotency.execute(
        owner,
        "DELETE_MATERIAL",
        id,
        key,
        Map.of("expectedVersion", expectedVersion),
        202,
        DeleteResult.class,
        () -> {
          var material = lock(owner, id, false);
          if (material.version() != expectedVersion) throw ApiException.conflict();
          db.update(
              "UPDATE materials SET"
                  + " status='DELETING',lifecycle_version=lifecycle_version+1,row_version=row_version+1,deleted_at=CURRENT_TIMESTAMP(3)"
                  + " WHERE id=?",
              id);
          cancel(id);
          return new DeleteResult(
              enqueue(
                  owner, material.courseId(), id, "MATERIAL_PURGE", material.lifecycle() + 1, 1),
              "QUEUED");
        });
  }

  void cancel(String id) {
    db.update(
        "UPDATE async_jobs j JOIN chat_turns t ON t.last_job_id=j.id JOIN chat_sessions s ON"
            + " s.id=t.session_id SET j.status='CANCELLED',j.finished_at=CURRENT_TIMESTAMP(3) WHERE"
            + " s.material_id=? AND j.type='READER_CHAT' AND j.status IN ('QUEUED','RUNNING')",
        id);
    db.update(
        "UPDATE job_attempts a JOIN async_jobs j ON j.id=a.job_id JOIN chat_turns t ON"
            + " t.id=j.target_id JOIN chat_sessions s ON s.id=t.session_id SET"
            + " a.outcome='CANCELLED',a.finished_at=CURRENT_TIMESTAMP(3) WHERE s.material_id=? AND"
            + " j.status='CANCELLED' AND a.outcome='RUNNING'",
        id);
    db.update(
        "UPDATE chat_turns t JOIN chat_sessions s ON s.id=t.session_id SET"
            + " t.status='CANCELLED',t.error_code='SOURCE_UNAVAILABLE',t.error_message='来源课件已删除，回答已停止',t.updated_at=CURRENT_TIMESTAMP(3)"
            + " WHERE s.material_id=? AND t.status IN ('QUEUED','RUNNING')",
        id);
    for (int page = 0; page < 3; page++) {
      db.update(
          "UPDATE chat_turns t JOIN chat_sessions s ON s.id=t.session_id SET"
              + " t.context_snapshot=JSON_SET(t.context_snapshot,?,'') WHERE s.material_id=? AND"
              + " JSON_LENGTH(t.context_snapshot)>?",
          "$[" + page + "].text",
          id,
          page);
    }
    db.update(
        "UPDATE chat_turns t JOIN chat_sessions s ON s.id=t.session_id SET"
            + " t.history_snapshot=JSON_ARRAY() WHERE s.material_id=?",
        id);
    db.update(
        "UPDATE async_jobs SET status='CANCELLED',finished_at=CURRENT_TIMESTAMP(3) WHERE"
            + " target_id=? AND type IN ('TEXT_EXTRACT','PREVIEW_RENDER','INDEX_BUILD') AND status"
            + " IN ('QUEUED','RUNNING')",
        id);
    db.update(
        "UPDATE job_attempts a JOIN async_jobs j ON j.id=a.job_id SET"
            + " a.outcome='CANCELLED',a.finished_at=CURRENT_TIMESTAMP(3) WHERE j.target_id=? AND"
            + " j.status='CANCELLED' AND a.outcome='RUNNING'",
        id);
    db.update(
        "UPDATE material_stages SET status='CANCELLED' WHERE material_id=? AND status IN"
            + " ('QUEUED','RUNNING')",
        id);
  }

  public DeleteResult retry(String owner, String id, Retry input, String key) {
    row(owner, id, false);
    if (input.stage() == null || !List.of("TEXT", "PREVIEW", "INDEX").contains(input.stage()))
      throw new ApiException(400, "VALIDATION_FAILED", "可重试文字提取、预览生成或搜索索引");
    return idempotency.execute(
        owner,
        "RETRY_" + input.stage(),
        id,
        key,
        input,
        202,
        DeleteResult.class,
        () -> {
          var material = lock(owner, id, false);
          if (input.expectedVersion() == null || input.expectedVersion() != material.version())
            throw ApiException.conflict();
          var stage =
              db.queryForMap(
                  "SELECT * FROM material_stages WHERE material_id=? AND stage=?",
                  id,
                  input.stage());
          if (List.of("QUEUED", "RUNNING").contains(stage.get("status")))
            return new DeleteResult(
                (String) stage.get("job_id"), String.valueOf(stage.get("status")));
          long revision = ((Number) stage.get("requested_revision")).longValue() + 1;
          Long textRevision =
              db.queryForObject(
                  "SELECT published_revision FROM material_stages WHERE material_id=? AND"
                      + " stage='TEXT'",
                  Long.class,
                  id);
          if (input.stage().equals("INDEX") && textRevision == null)
            throw new ApiException(409, "PROCESSING_NOT_READY", "请等待文字提取完成后再建立索引");
          String job =
              enqueue(
                  owner,
                  material.courseId(),
                  id,
                  input.stage().equals("TEXT")
                      ? "TEXT_EXTRACT"
                      : input.stage().equals("INDEX") ? "INDEX_BUILD" : "PREVIEW_RENDER",
                  material.lifecycle(),
                  revision);
          if (input.stage().equals("INDEX"))
            db.update(
                "UPDATE async_jobs SET input_snapshot=JSON_SET(input_snapshot,'$.textRevision',?)"
                    + " WHERE id=?",
                textRevision,
                job);
          db.update(
              "UPDATE material_stages SET"
                  + " status='QUEUED',requested_revision=?,job_id=?,error_code=NULL,error_message=NULL"
                  + " WHERE material_id=? AND stage=?",
              revision,
              job,
              id,
              input.stage());
          db.update(
              "UPDATE materials SET row_version=row_version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE"
                  + " id=?",
              id);
          return new DeleteResult(job, "QUEUED");
        });
  }

  public SlidePage slides(String owner, String id, int page, int size) {
    var material = row(owner, id, false);
    pagination(page, size);
    var list =
        db.query(
            "SELECT * FROM slides WHERE material_id=? ORDER BY page_no LIMIT ? OFFSET ?",
            this::slideView,
            id,
            size,
            (page - 1) * size);
    return new SlidePage(list, page, size, material.pages());
  }

  private SlideView slideView(ResultSet rs, int index) throws SQLException {
    String preview = rs.getString("preview_asset_id"), thumb = rs.getString("thumbnail_asset_id");
    return new SlideView(
        rs.getString("id"),
        rs.getString("material_id"),
        rs.getInt("page_no"),
        rs.getBoolean("is_hidden"),
        rs.getString("title"),
        rs.getString("text_content"),
        rs.getObject("text_revision", Long.class),
        preview != null,
        url(preview),
        url(thumb));
  }

  public SlideView slide(String owner, String id) {
    var list =
        db.query(
            "SELECT s.* FROM slides s JOIN materials m ON m.id=s.material_id JOIN courses c ON"
                + " c.id=m.course_id WHERE s.id=? AND c.owner_id=? AND c.status='ACTIVE' AND"
                + " m.status='ACTIVE'",
            this::slideView,
            id,
            owner);
    if (list.isEmpty()) throw ApiException.missing();
    return list.get(0);
  }

  public Progress progress(String owner, String id) {
    row(owner, id, false);
    var list =
        db.query(
            "SELECT last_page_no,updated_at FROM reading_progress WHERE owner_id=? AND"
                + " material_id=?",
            (rs, n) -> new Progress(rs.getInt(1), rs.getTimestamp(2).toInstant()),
            owner,
            id);
    return list.isEmpty() ? new Progress(null, null) : list.get(0);
  }

  public Progress saveProgress(String owner, String id, Integer page) {
    return tx.execute(
        status -> {
          var material = lock(owner, id, false);
          if (page == null || page < 1 || page > material.pages())
            throw new ApiException(400, "INVALID_PAGE", "页码超出课件范围");
          db.update(
              "INSERT INTO reading_progress(owner_id,material_id,last_page_no) VALUES(?,?,?) ON"
                  + " DUPLICATE KEY UPDATE"
                  + " last_page_no=VALUES(last_page_no),updated_at=CURRENT_TIMESTAMP(3)",
              owner,
              id,
              page);
          return progress(owner, id);
        });
  }

  public AssetFile file(String owner, String id) throws IOException {
    var list =
        db.query(
            "SELECT a.storage_key,a.media_type,a.kind,m.display_name FROM file_assets a JOIN"
                + " materials m ON m.id=a.material_id JOIN courses c ON c.id=m.course_id WHERE"
                + " a.id=? AND c.owner_id=? AND c.status='ACTIVE' AND m.status='ACTIVE' AND"
                + " a.status='READY'",
            (rs, n) ->
                new AssetFile(
                    storagePath(rs.getString(1)),
                    rs.getString(2),
                    "ORIGINAL".equals(rs.getString(3))
                        ? rs.getString(4)
                        : "PDF".equals(rs.getString(3)) ? "课件预览.pdf" : "课件页面.png"),
            id,
            owner);
    if (list.isEmpty() || !Files.isRegularFile(list.get(0).path())) throw ApiException.missing();
    return list.get(0);
  }

  private Path storagePath(String key) {
    try {
      return storage.resolve(key);
    } catch (IOException error) {
      throw ApiException.missing();
    }
  }

  static String url(String asset) {
    return asset == null ? null : "/api/assets/" + asset + "/content";
  }

  static void pagination(int page, int size) {
    if (page < 1 || page > 1000000 || size < 1 || size > 100)
      throw new ApiException(400, "VALIDATION_FAILED", "分页参数不正确");
  }

  public record MaterialRow(
      String id,
      String courseId,
      String owner,
      String name,
      long size,
      int pages,
      long version,
      long lifecycle,
      String status,
      String mapping,
      String originalAsset,
      String pdfAsset,
      Instant createdAt,
      Instant updatedAt) {}

  public record MaterialView(
      String id,
      String courseId,
      String displayName,
      long sizeBytes,
      long contentVersion,
      int slideCount,
      long version,
      String mappingStatus,
      Map<String, StageView> stages,
      Capabilities capabilities,
      String originalDownloadUrl,
      String pdfUrl,
      Instant createdAt,
      Instant updatedAt) {}

  public record StageView(
      String status,
      boolean available,
      Long publishedRevision,
      long requestedRevision,
      String jobId,
      SafeError error) {}

  public record SafeError(String code, String message, boolean retryable) {}

  public record Capabilities(boolean canDownload, boolean canReadPreview, boolean canLocate) {}

  public record MaterialPage(List<MaterialView> items, int page, int size, long total) {}

  public record JobRef(String jobId, String type) {}

  public record UploadResult(String materialId, String statusUrl, List<JobRef> jobs) {}

  public record DeleteResult(String id, String status) {}

  public record Retry(String stage, Long expectedVersion) {}

  public record SlideView(
      String id,
      String materialId,
      int pageNo,
      boolean isHidden,
      String title,
      String text,
      Long textRevision,
      boolean previewAvailable,
      String previewUrl,
      String thumbnailUrl) {}

  public record SlidePage(List<SlideView> items, int page, int size, long total) {}

  public record Progress(Integer lastPageNo, Instant updatedAt) {}

  public record AssetFile(Path path, String mime, String name) {}
}
