package cn.review.focus;

import cn.review.common.*;
import cn.review.search.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FocusService {
  final JdbcTemplate db;
  final TransactionTemplate tx;
  final SearchCatalog catalog;
  final ObjectMapper json;
  private final IdempotencyService idem;
  private final DeepSeekReranker ai;

  public FocusService(
      JdbcTemplate db,
      TransactionTemplate tx,
      SearchCatalog catalog,
      ObjectMapper json,
      IdempotencyService idem,
      DeepSeekReranker ai) {
    this.db = db;
    this.tx = tx;
    this.catalog = catalog;
    this.json = json;
    this.idem = idem;
    this.ai = ai;
  }

  String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  <T> T decode(String value, TypeReference<T> type) {
    try {
      return json.readValue(value, type);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored snapshot", e);
    }
  }

  Row row(String owner, String id, boolean deleted) {
    var rows =
        db.query(
            "SELECT f.* FROM focus_items f JOIN courses c ON c.id=f.course_id WHERE f.id=? AND"
                + " c.owner_id=? AND c.status='ACTIVE'"
                + (deleted ? "" : " AND f.deleted_at IS NULL"),
            (rs, n) ->
                new Row(
                    rs.getString("id"),
                    rs.getString("course_id"),
                    rs.getString("content"),
                    rs.getString("original_input"),
                    rs.getLong("content_revision"),
                    rs.getString("mastery_status"),
                    rs.getString("match_status"),
                    rs.getLong("row_version"),
                    rs.getString("last_locate_job_id"),
                    rs.getString("locate_method"),
                    rs.getString("locate_warnings"),
                    rs.getString("last_scope"),
                    rs.getString("error_code"),
                    rs.getString("error_message"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()),
            id,
            owner);
    if (rows.isEmpty()) throw ApiException.missing();
    return rows.get(0);
  }

  Row lock(String owner, String id) {
    var first = row(owner, id, false);
    catalog.course(owner, first.courseId(), true);
    db.queryForList("SELECT id FROM focus_items WHERE id=? FOR UPDATE", id);
    return row(owner, id, false);
  }

  public View get(String owner, String id) {
    return view(owner, row(owner, id, false));
  }

  View view(String owner, Row row) {
    String status = row.matchStatus();
    if (!List.of("RUNNING", "NEVER", "FAILED").contains(status)
        && row.scope() != null
        && !catalog.current(owner, row.courseId(), scopes(row.scope()))) status = "NEEDS_REVIEW";
    return new View(
        row.id(),
        row.courseId(),
        row.content(),
        row.originalInput(),
        row.revision(),
        row.masteryStatus(),
        status,
        row.version(),
        row.job(),
        row.method(),
        row.warnings() == null
            ? List.of()
            : decode(row.warnings(), new TypeReference<List<String>>() {}),
        row.errorCode(),
        row.errorMessage(),
        row.createdAt(),
        row.updatedAt());
  }

  List<SearchCatalog.Scope> scopes(String value) {
    return decode(value, new TypeReference<List<SearchCatalog.Scope>>() {});
  }

  public Page list(String owner, String course, int page, int size, String mastery, String match) {
    catalog.course(owner, course, false);
    if (page < 1 || page > 1000000 || size < 1 || size > 100)
      throw new ApiException(400, "VALIDATION_FAILED", "分页参数不正确");
    var all =
        db
            .queryForList(
                "SELECT id FROM focus_items WHERE course_id=? AND deleted_at IS NULL ORDER BY"
                    + " created_at DESC,id DESC",
                String.class,
                course)
            .stream()
            .map(id -> get(owner, id))
            .filter(f -> mastery == null || mastery.equals(f.masteryStatus()))
            .filter(f -> match == null || match.equals(f.matchStatus()))
            .toList();
    return new Page(
        all.stream().skip((long) (page - 1) * size).limit(size).toList(), page, size, all.size());
  }

  public Capabilities capabilities(String owner, String course) {
    catalog.course(owner, course, false);
    return new Capabilities(
        ai.available(),
        catalog.scope(owner, course).size(),
        db.queryForObject(
            "SELECT COUNT(*) FROM materials WHERE course_id=? AND status='ACTIVE'",
            Integer.class,
            course),
        50);
  }

  public View create(String owner, String course, Create input, String key) {
    catalog.course(owner, course, false);
    String content = ApiException.text(input.content(), "重点内容", 1, 2000);
    String original = original(input.originalInput());
    Id result =
        idem.execute(
            owner,
            "CREATE_FOCUS",
            course,
            key,
            input,
            201,
            Id.class,
            () -> {
              catalog.course(owner, course, true);
              return new Id(insert(course, content, original));
            });
    return get(owner, result.id());
  }

  private String original(String text) {
    return text == null ? null : ApiException.text(text, "原始输入", 0, 10000);
  }

  private String insert(String course, String content, String original) {
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO focus_items(id,course_id,content,original_input) VALUES(?,?,?,?)",
        id,
        course,
        content,
        original);
    return id;
  }

  public List<View> batch(String owner, String course, Batch input, String key) {
    catalog.course(owner, course, false);
    if (input.items() == null || input.items().isEmpty() || input.items().size() > 50)
      throw new ApiException(400, "VALIDATION_FAILED", "一次可添加1到50条重点");
    var texts = input.items().stream().map(t -> ApiException.text(t, "重点内容", 1, 2000)).toList();
    String original = original(input.originalInput());
    Ids result =
        idem.execute(
            owner,
            "BATCH_FOCUS",
            course,
            key,
            input,
            201,
            Ids.class,
            () -> {
              catalog.course(owner, course, true);
              return new Ids(texts.stream().map(t -> insert(course, t, original)).toList());
            });
    return result.ids().stream().map(id -> get(owner, id)).toList();
  }

  public Split split(String owner, String course, SplitInput input, String key) {
    catalog.course(owner, course, false);
    String text = ApiException.text(input.text(), "重点清单", 1, 10000);
    var items =
        Arrays.stream(text.split("[\\r\\n]+"))
            .map(String::strip)
            .filter(t -> !t.isBlank())
            .map(t -> t.replaceFirst("^(?:[-*•]\\s*|\\d+[.、)）]\\s*)", ""))
            .toList();
    if (items.size() > 50 || items.stream().anyMatch(t -> t.codePointCount(0, t.length()) > 2000))
      throw new ApiException(400, "VALIDATION_FAILED", "最多50条，每条不超过2000字，请调整后再预览");
    idem.execute(owner, "SPLIT_FOCUS", course, key, input, 200, Id.class, () -> new Id(course));
    return new Split(items, List.of("已按换行和列表编号拆分，请确认后保存；尚未调用 AI"));
  }

  public View patch(String owner, String id, Edit input) {
    return tx.execute(
        s -> {
          var row = lock(owner, id);
          version(row, input.expectedVersion());
          if (input.content() == null && input.masteryStatus() == null)
            throw new ApiException(400, "VALIDATION_FAILED", "请填写重点内容或掌握状态");
          String content =
              input.content() == null
                  ? row.content()
                  : ApiException.text(input.content(), "重点内容", 1, 2000);
          String mastery =
              input.masteryStatus() == null ? row.masteryStatus() : input.masteryStatus();
          if (!List.of("NOT_STARTED", "IN_PROGRESS", "UNFAMILIAR", "MASTERED").contains(mastery))
            throw new ApiException(400, "VALIDATION_FAILED", "掌握状态不正确");
          boolean changed = !content.equals(row.content());
          if (changed) {
            cancel(id);
            db.update(
                "DELETE FROM focus_slide_links WHERE focus_id=? AND decision='CANDIDATE'", id);
          }
          db.update(
              "UPDATE focus_items SET"
                  + " content=?,mastery_status=?,content_revision=content_revision+?,match_status=IF(?,'NEEDS_REVIEW',match_status),last_locate_job_id=IF(?,NULL,last_locate_job_id),error_code=IF(?,NULL,error_code),error_message=IF(?,NULL,error_message),row_version=row_version+1,updated_at=CURRENT_TIMESTAMP(3)"
                  + " WHERE id=?",
              content,
              mastery,
              changed ? 1 : 0,
              changed,
              changed,
              changed,
              changed,
              id);
          return get(owner, id);
        });
  }

  void version(Row row, Long expected) {
    if (expected == null || row.version() != expected) throw ApiException.conflict();
  }

  void cancel(String id) {
    db.update(
        "UPDATE async_jobs SET status='CANCELLED',finished_at=CURRENT_TIMESTAMP(3),lease_until=NULL"
            + " WHERE target_id=? AND type='FOCUS_LOCATE' AND status IN ('QUEUED','RUNNING')",
        id);
    db.update(
        "UPDATE job_attempts a JOIN async_jobs j ON j.id=a.job_id SET"
            + " a.outcome='CANCELLED',a.finished_at=CURRENT_TIMESTAMP(3) WHERE j.target_id=? AND"
            + " j.status='CANCELLED' AND a.outcome='RUNNING'",
        id);
  }

  public void delete(String owner, String id, long expected, String key) {
    row(owner, id, true);
    idem.execute(
        owner,
        "DELETE_FOCUS",
        id,
        key,
        Map.of("expectedVersion", expected),
        204,
        Id.class,
        () -> {
          var row = lock(owner, id);
          version(row, expected);
          cancel(id);
          db.update("DELETE FROM focus_slide_links WHERE focus_id=?", id);
          db.update(
              "UPDATE focus_items SET"
                  + " content='',original_input=NULL,last_scope=NULL,locate_warnings=NULL,error_code=NULL,error_message=NULL,deleted_at=CURRENT_TIMESTAMP(3),row_version=row_version+1"
                  + " WHERE id=?",
              id);
          return new Id(id);
        });
  }

  public LocateResult locate(String owner, String id, Locate input, String key) {
    row(owner, id, false);
    return idem.execute(
        owner,
        "LOCATE_FOCUS",
        id,
        key,
        input,
        202,
        LocateResult.class,
        () -> {
          var row = lock(owner, id);
          version(row, input.expectedVersion());
          if (row.job() != null) {
            var existing =
                db.queryForList(
                    "SELECT id,status FROM async_jobs WHERE id=? AND status IN"
                        + " ('QUEUED','RUNNING')",
                    row.job());
            if (!existing.isEmpty())
              return new LocateResult(row.job(), String.valueOf(existing.get(0).get("status")));
          }
          var scopes = catalog.scope(owner, row.courseId());
          if (scopes.isEmpty())
            throw new ApiException(409, "PROCESSING_NOT_READY", "还没有可检索课件，请先上传并等待文字提取和索引完成");
          String job = UUID.randomUUID().toString();
          db.update(
              "INSERT INTO"
                  + " async_jobs(id,owner_id,course_id,type,target_id,dedup_key,input_snapshot,max_attempts,run_after)"
                  + " VALUES(?,?,?,'FOCUS_LOCATE',?,?,?,1,CURRENT_TIMESTAMP(3))",
              job,
              owner,
              row.courseId(),
              id,
              "FOCUS_LOCATE:" + job,
              encode(new Snapshot(row.revision(), scopes, Boolean.TRUE.equals(input.useAi()))));
          db.update(
              "UPDATE focus_items SET"
                  + " match_status='RUNNING',last_locate_job_id=?,error_code=NULL,error_message=NULL,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP(3)"
                  + " WHERE id=?",
              job,
              id);
          return new LocateResult(job, "QUEUED");
        });
  }

  public Matches matches(String owner, String id, boolean rejected) {
    var row = row(owner, id, false);
    var links =
        db
            .queryForList(
                "SELECT id FROM focus_slide_links WHERE focus_id=?"
                    + (rejected ? "" : " AND decision<>'REJECTED'")
                    + " ORDER BY CASE decision WHEN 'CONFIRMED' THEN 0 WHEN 'CANDIDATE' THEN 1 ELSE"
                    + " 2 END,sort_order,created_at,id",
                String.class,
                id)
            .stream()
            .map(link -> link(row, link))
            .toList();
    var focus = view(owner, row);
    return new Matches(focus.matchStatus(), links, focus.warnings(), focus.version());
  }

  Link link(Row focus, String id) {
    var row =
        db.queryForMap("SELECT * FROM focus_slide_links WHERE id=? AND focus_id=?", id, focus.id());
    var snap = decode(String.valueOf(row.get("source_snapshot")), new TypeReference<Source>() {});
    var source =
        db.queryForList(
            "SELECT s.text_revision,m.status,m.mapping_status,m.pdf_asset_id FROM slides s JOIN"
                + " materials m ON m.id=s.material_id WHERE s.id=? AND s.deleted_at IS NULL AND"
                + " m.status='ACTIVE'",
            row.get("slide_id"));
    boolean available = !source.isEmpty();
    boolean stale =
        ((Number) row.get("matched_focus_revision")).longValue() != focus.revision()
            || !available
            || row.get("matched_text_revision") == null
            || available && source.get(0).get("text_revision") == null
            || available
                && !Objects.equals(
                    ((Number) row.get("matched_text_revision")).longValue(),
                    ((Number) source.get(0).get("text_revision")).longValue());
    return new Link(
        (String) row.get("id"),
        (String) row.get("slide_id"),
        snap.materialId(),
        snap.materialName(),
        snap.pageNo(),
        snap.title(),
        (String) row.get("origin"),
        (String) row.get("decision"),
        stale,
        available,
        available
            && "VERIFIED".equals(source.get(0).get("mapping_status"))
            && source.get(0).get("pdf_asset_id") != null,
        (String) row.get("evidence_text"),
        (String) row.get("reason"),
        ((Number) row.get("row_version")).longValue());
  }

  public LinkResult decide(String owner, String id, String slide, Decision input) {
    return tx.execute(
        s -> {
          var focus = lock(owner, id);
          version(focus, input.expectedFocusVersion());
          if (!List.of("CONFIRMED", "REJECTED")
              .contains(Optional.ofNullable(input.decision()).orElse("")))
            throw new ApiException(400, "VALIDATION_FAILED", "请选择确认或排除");
          var existing =
              db.queryForList(
                  "SELECT * FROM focus_slide_links WHERE focus_id=? AND slide_id=? FOR UPDATE",
                  id,
                  slide);
          String linkId;
          if (existing.isEmpty()) {
            if (input.expectedLinkVersion() != null || !input.decision().equals("CONFIRMED"))
              throw ApiException.conflict();
            var pages =
                db.query(
                    "SELECT s.*,m.display_name FROM slides s JOIN materials m ON m.id=s.material_id"
                        + " WHERE s.id=? AND m.course_id=? AND m.status='ACTIVE' AND s.deleted_at"
                        + " IS NULL",
                    (rs, n) ->
                        new SearchCatalog.Candidate(
                            rs.getString("id"),
                            rs.getString("material_id"),
                            rs.getString("display_name"),
                            rs.getInt("page_no"),
                            rs.getString("title"),
                            Optional.ofNullable(rs.getString("text_content")).orElse(""),
                            rs.getLong("text_revision"),
                            false),
                    slide,
                    focus.courseId());
            if (pages.isEmpty()) throw ApiException.missing();
            linkId =
                saveLink(
                    focus,
                    pages.get(0),
                    "MANUAL",
                    "CONFIRMED",
                    null,
                    pages
                        .get(0)
                        .evidenceText()
                        .substring(0, Math.min(800, pages.get(0).evidenceText().length())),
                    "手动关联");
          } else {
            var old = existing.get(0);
            if (input.expectedLinkVersion() == null
                || input.expectedLinkVersion() != ((Number) old.get("row_version")).longValue())
              throw ApiException.conflict();
            linkId = (String) old.get("id");
            Link prior = link(focus, linkId);
            if (input.decision().equals("CONFIRMED") && !prior.sourceAvailable())
              throw new ApiException(409, "SOURCE_UNAVAILABLE", "课件已删除，无法确认该位置");
            Long text =
                input.decision().equals("CONFIRMED")
                    ? db.queryForObject(
                        "SELECT text_revision FROM slides WHERE id=?", Long.class, slide)
                    : (Long) null;
            if (input.decision().equals("CONFIRMED")) {
              String currentText =
                  db.queryForObject(
                      "SELECT text_content FROM slides WHERE id=?", String.class, slide);
              String evidence = (String) old.get("evidence_text");
              if (currentText != null && (evidence == null || !currentText.contains(evidence)))
                db.update(
                    "UPDATE focus_slide_links SET evidence_text=?,reason='人工重新核对当前页面' WHERE id=?",
                    currentText.substring(0, Math.min(800, currentText.length())),
                    linkId);
            }
            db.update(
                "UPDATE focus_slide_links SET"
                    + " decision=?,matched_focus_revision=?,matched_text_revision=COALESCE(?,matched_text_revision),row_version=row_version+1,updated_at=CURRENT_TIMESTAMP(3)"
                    + " WHERE id=?",
                input.decision(),
                focus.revision(),
                text,
                linkId);
          }
          db.update(
              "UPDATE focus_items SET row_version=row_version+1,updated_at=CURRENT_TIMESTAMP(3)"
                  + " WHERE id=?",
              id);
          return new LinkResult(link(row(owner, id, false), linkId), focus.version() + 1);
        });
  }

  String saveLink(
      Row focus,
      SearchCatalog.Candidate page,
      String origin,
      String decision,
      String job,
      String evidence,
      String reason) {
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO"
            + " focus_slide_links(id,focus_id,slide_id,origin,decision,matched_focus_revision,matched_text_revision,locate_job_id,evidence_text,reason,source_snapshot,sort_order)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        focus.id(),
        page.slideId(),
        origin,
        decision,
        focus.revision(),
        page.textRevision(),
        job,
        evidence,
        reason,
        encode(new Source(page.materialId(), page.materialName(), page.pageNo(), page.title())),
        db.queryForObject(
            "SELECT COALESCE(MAX(sort_order),0)+1 FROM focus_slide_links WHERE focus_id=?",
            Integer.class,
            focus.id()));
    return id;
  }

  public record Create(String content, String originalInput) {}

  public record Batch(List<String> items, String originalInput) {}

  public record SplitInput(String text) {}

  public record Split(List<String> items, List<String> warnings) {}

  public record Edit(String content, String masteryStatus, Long expectedVersion) {}

  public record Locate(Long expectedVersion, Boolean useAi) {}

  public record Decision(String decision, Long expectedFocusVersion, Long expectedLinkVersion) {}

  public record Id(String id) {}

  public record Ids(List<String> ids) {}

  public record LocateResult(String jobId, String status) {}

  public record Capabilities(
      boolean aiAvailable, int readyMaterials, int totalMaterials, int maxBatchSize) {}

  public record Snapshot(long revision, List<SearchCatalog.Scope> scope, boolean useAi) {}

  record Row(
      String id,
      String courseId,
      String content,
      String originalInput,
      long revision,
      String masteryStatus,
      String matchStatus,
      long version,
      String job,
      String method,
      String warnings,
      String scope,
      String errorCode,
      String errorMessage,
      Instant createdAt,
      Instant updatedAt) {}

  public record View(
      String id,
      String courseId,
      String content,
      String originalInput,
      long contentRevision,
      String masteryStatus,
      String matchStatus,
      long version,
      String lastLocateJobId,
      String method,
      List<String> warnings,
      String errorCode,
      String errorMessage,
      Instant createdAt,
      Instant updatedAt) {}

  public record Page(List<View> items, int page, int size, long total) {}

  public record Source(String materialId, String materialName, int pageNo, String title) {}

  public record Link(
      String linkId,
      String slideId,
      String materialId,
      String materialName,
      int pageNo,
      String title,
      String origin,
      String decision,
      boolean needsReview,
      boolean sourceAvailable,
      boolean previewAvailable,
      String evidenceText,
      String reason,
      long version) {}

  public record LinkResult(Link link, long focusVersion) {}

  public record Matches(
      String matchStatus, List<Link> items, List<String> warnings, long focusVersion) {}
}
