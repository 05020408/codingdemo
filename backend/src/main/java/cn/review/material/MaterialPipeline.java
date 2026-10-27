package cn.review.material;

import cn.review.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable single-worker pipeline. External work never runs inside a database transaction. */
@Service
public class MaterialPipeline {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final MaterialService materials;
  private final MaterialStorage storage;
  private final PreviewConverter converter;
  private final ObjectMapper json;
  private final cn.review.search.PageIndex index;
  private final AtomicBoolean running = new AtomicBoolean();

  public MaterialPipeline(
      JdbcTemplate db,
      TransactionTemplate tx,
      MaterialService materials,
      MaterialStorage storage,
      PreviewConverter converter,
      ObjectMapper json,
      cn.review.search.PageIndex index) {
    this.db = db;
    this.tx = tx;
    this.materials = materials;
    this.storage = storage;
    this.converter = converter;
    this.json = json;
    this.index = index;
  }

  public boolean runOne() {
    if (!running.compareAndSet(false, true)) return false;
    Claim claim = null;
    ScheduledExecutorService heartbeat = null;
    try {
      recover();
      ensureIndexes();
      claim = claim();
      if (claim == null) return false;
      Claim current = claim;
      heartbeat =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread thread = new Thread(r, "material-lease");
                thread.setDaemon(true);
                return thread;
              });
      heartbeat.scheduleAtFixedRate(
          () -> {
            try {
              db.update(
                  "UPDATE async_jobs SET"
                      + " lease_until=TIMESTAMPADD(SECOND,90,CURRENT_TIMESTAMP(3)),heartbeat_at=CURRENT_TIMESTAMP(3)"
                      + " WHERE id=? AND lease_token=? AND status='RUNNING' AND"
                      + " lease_until>CURRENT_TIMESTAMP(3)",
                  current.id(),
                  current.token());
            } catch (Exception ignored) {
              /* Publication still requires a live lease. */
            }
          },
          10,
          10,
          TimeUnit.SECONDS);
      execute(claim);
      return true;
    } catch (Exception error) {
      if (claim == null) throw new IllegalStateException("Could not claim pipeline work", error);
      fail(claim, error);
      return true;
    } finally {
      if (heartbeat != null) heartbeat.shutdownNow();
      if (claim != null)
        try {
          storage.remove(workKey(claim));
        } catch (Exception ignored) {
        }
      running.set(false);
    }
  }

  public void recover() {
    tx.executeWithoutResult(
        status -> {
          db.update(
              "UPDATE job_attempts a JOIN async_jobs j ON j.id=a.job_id SET"
                  + " a.outcome='INTERRUPTED',a.finished_at=CURRENT_TIMESTAMP(3),a.error_code='WORKER_INTERRUPTED'"
                  + " WHERE a.outcome='RUNNING' AND j.status='RUNNING' AND j.type<>'FOCUS_LOCATE'"
                  + " AND j.lease_until<CURRENT_TIMESTAMP(3)");
          db.update(
              "UPDATE async_jobs SET"
                  + " status=IF(attempt_count<max_attempts,'QUEUED','FAILED'),lease_token=NULL,lease_until=NULL,error_code='WORKER_INTERRUPTED',error_message='处理被中断，等待恢复或重试',run_after=CURRENT_TIMESTAMP(3)"
                  + " WHERE status='RUNNING' AND type<>'FOCUS_LOCATE' AND"
                  + " lease_until<CURRENT_TIMESTAMP(3)");
          db.update(
              "UPDATE material_stages s JOIN async_jobs j ON j.id=s.job_id SET"
                  + " s.status=j.status,s.error_code=IF(j.status='FAILED',j.error_code,NULL),s.error_message=IF(j.status='FAILED',j.error_message,NULL)"
                  + " WHERE s.status='RUNNING' AND j.status IN ('QUEUED','FAILED')");
        });
  }

  private Claim claim() {
    return tx.execute(
        status -> {
          var candidates =
              db.queryForList(
                  "SELECT * FROM async_jobs WHERE status='QUEUED' AND type IN"
                      + " ('TEXT_EXTRACT','PREVIEW_RENDER','INDEX_BUILD','MATERIAL_PURGE','COURSE_PURGE')"
                      + " AND run_after<=CURRENT_TIMESTAMP(3) ORDER BY CASE WHEN type LIKE '%PURGE'"
                      + " THEN 0 ELSE 1 END,created_at,id LIMIT 1");
          if (candidates.isEmpty()) return null;
          var row = candidates.get(0);
          String id = (String) row.get("id"),
              course = (String) row.get("course_id"),
              target = (String) row.get("target_id"),
              type = (String) row.get("type"),
              owner = (String) row.get("owner_id");
          db.queryForList("SELECT id FROM courses WHERE id=? FOR UPDATE", course);
          if (!type.equals("COURSE_PURGE"))
            db.queryForList("SELECT id FROM materials WHERE id=? FOR UPDATE", target);
          String token = UUID.randomUUID().toString();
          if (db.update(
                  "UPDATE async_jobs SET"
                      + " status='RUNNING',attempt_count=attempt_count+1,lease_token=?,lease_until=TIMESTAMPADD(SECOND,90,CURRENT_TIMESTAMP(3)),heartbeat_at=CURRENT_TIMESTAMP(3),error_code=NULL,error_message=NULL"
                      + " WHERE id=? AND status='QUEUED'",
                  token,
                  id)
              == 0) return null;
          db.update(
              "INSERT INTO job_attempts(id,job_id,attempt_no,lease_token,started_at,outcome) SELECT"
                  + " ?,id,attempt_count,?,CURRENT_TIMESTAMP(3),'RUNNING' FROM async_jobs WHERE"
                  + " id=?",
              UUID.randomUUID().toString(),
              token,
              id);
          String stage = stage(type);
          if (stage != null)
            db.update(
                "UPDATE material_stages SET status='RUNNING',error_code=NULL,error_message=NULL"
                    + " WHERE material_id=? AND stage=? AND job_id=?",
                target,
                stage,
                id);
          try {
            var input = json.readTree(String.valueOf(row.get("input_snapshot")));
            return new Claim(
                id,
                course,
                target,
                owner,
                type,
                token,
                input.path("lifecycleVersion").asLong(),
                input.path("revision").asLong(1),
                input.path("textRevision").asLong());
          } catch (Exception error) {
            throw new IllegalStateException("Invalid saved job snapshot", error);
          }
        });
  }

  private void execute(Claim claim) throws Exception {
    if (claim.type().equals("COURSE_PURGE") || claim.type().equals("MATERIAL_PURGE")) {
      purge(claim);
      return;
    }
    var material = materials.row(claim.owner(), claim.target(), false);
    if (claim.type().equals("INDEX_BUILD")) {
      var pages =
          db.query(
              "SELECT id,text_content FROM slides WHERE material_id=? AND text_revision=? AND"
                  + " deleted_at IS NULL ORDER BY page_no",
              (rs, n) ->
                  new cn.review.search.PageIndex.Page(
                      rs.getString(1), Optional.ofNullable(rs.getString(2)).orElse("")),
              claim.target(),
              claim.textRevision());
      if (pages.size() != material.pages())
        throw new ApiException(409, "SOURCE_CHANGED", "课件文字已更新，请重新建立索引");
      index.build(claim.course(), claim.target(), claim.revision(), claim.textRevision(), pages);
      tx.executeWithoutResult(
          status -> {
            fence(claim);
            Long text =
                db.queryForObject(
                    "SELECT published_revision FROM material_stages WHERE material_id=? AND"
                        + " stage='TEXT'",
                    Long.class,
                    claim.target());
            if (text == null || text != claim.textRevision())
              throw new ApiException(409, "SOURCE_CHANGED", "课件文字已更新，请重试索引");
            db.update(
                "UPDATE material_stages SET"
                    + " status='SUCCEEDED',published_revision=?,input_text_revision=?,error_code=NULL,error_message=NULL"
                    + " WHERE material_id=? AND stage='INDEX' AND job_id=?",
                claim.revision(),
                claim.textRevision(),
                claim.target(),
                claim.id());
            success(claim);
          });
      return;
    }
    var original = materials.file(claim.owner(), material.originalAsset()).path();
    List<PptTextExtractor.SlideText> slides;
    try (var input = Files.newInputStream(original)) {
      slides = new PptTextExtractor().extract(input);
    }
    if (slides.size() != material.pages())
      throw new ApiException(422, "PAGE_MAPPING_FAILED", "原件页数发生变化，无法继续处理");
    if (claim.type().equals("TEXT_EXTRACT")) {
      if (slides.stream().allMatch(s -> s.text().isBlank()))
        throw new ApiException(422, "NO_EXTRACTABLE_TEXT", "课件没有可提取文字；图片课件仍可在预览完成后阅读");
      tx.executeWithoutResult(
          status -> {
            fence(claim);
            for (var slide : slides) {
              if (db.update(
                      "UPDATE slides SET title=?,text_content=?,text_revision=? WHERE material_id=?"
                          + " AND page_no=? AND source_slide_id=?",
                      slide.title(),
                      slide.text(),
                      claim.revision(),
                      claim.target(),
                      slide.pageNumber(),
                      slide.sourceSlideId())
                  != 1) throw new ApiException(422, "PAGE_MAPPING_FAILED", "原始页面标识无法对应");
            }
            db.update(
                "UPDATE material_stages SET"
                    + " status='SUCCEEDED',published_revision=?,error_code=NULL,error_message=NULL"
                    + " WHERE material_id=? AND stage='TEXT' AND job_id=?",
                claim.revision(),
                claim.target(),
                claim.id());
            queueIndex(claim.owner(), claim.course(), claim.target(), claim.revision());
            success(claim);
          });
      return;
    }
    Path work = storage.resolve(workKey(claim));
    Files.createDirectories(work);
    converter.convert(
        original,
        work,
        slides,
        done -> {
          int changed =
              db.update(
                  "UPDATE async_jobs SET progress_done=?,progress_total=? WHERE id=? AND"
                      + " lease_token=? AND status='RUNNING' AND lease_until>CURRENT_TIMESTAMP(3)",
                  done,
                  slides.size(),
                  claim.id(),
                  claim.token());
          if (changed == 0) throw new ApiException(409, "JOB_CANCELLED", "任务已取消或处理资格已失效");
        });
    // Only the actual PDF and images become assets; logs/profile stay in private work space.
    Path publish = work.resolve("publish");
    Files.createDirectories(publish);
    Files.move(work.resolve("preview.pdf"), publish.resolve("preview.pdf"));
    for (var slide : slides)
      for (String prefix : List.of("page-", "thumb-"))
        Files.move(
            work.resolve(prefix + slide.pageNumber() + ".png"),
            publish.resolve(prefix + slide.pageNumber() + ".png"));
    String destination =
        storage.materialKey(claim.course(), claim.target()) + "/previews/" + claim.token();
    try {
      tx.executeWithoutResult(
          status -> {
            fence(claim);
            try {
              storage.move(publish, destination);
            } catch (Exception error) {
              throw new ApiException(503, "STORAGE_UNAVAILABLE", "预览保存失败，请稍后重试");
            }
            db.update(
                "UPDATE file_assets SET status='RETIRED' WHERE material_id=? AND kind<>'ORIGINAL'"
                    + " AND status='READY'",
                claim.target());
            String pdf =
                materials.asset(
                    claim.target(), "PDF", destination + "/preview.pdf", "application/pdf");
            for (var slide : slides) {
              String preview =
                  materials.asset(
                      claim.target(),
                      "PAGE",
                      destination + "/page-" + slide.pageNumber() + ".png",
                      "image/png");
              String thumbnail =
                  materials.asset(
                      claim.target(),
                      "THUMBNAIL",
                      destination + "/thumb-" + slide.pageNumber() + ".png",
                      "image/png");
              db.update(
                  "UPDATE slides SET preview_asset_id=?,thumbnail_asset_id=? WHERE material_id=?"
                      + " AND page_no=?",
                  preview,
                  thumbnail,
                  claim.target(),
                  slide.pageNumber());
            }
            db.update(
                "UPDATE materials SET"
                    + " mapping_status='VERIFIED',pdf_asset_id=?,updated_at=CURRENT_TIMESTAMP(3)"
                    + " WHERE id=?",
                pdf,
                claim.target());
            db.update(
                "UPDATE material_stages SET"
                    + " status='SUCCEEDED',published_revision=?,error_code=NULL,error_message=NULL"
                    + " WHERE material_id=? AND stage='PREVIEW' AND job_id=?",
                claim.revision(),
                claim.target(),
                claim.id());
            success(claim);
          });
    } catch (Exception error) {
      storage.remove(destination);
      throw error;
    }
    // Old successful preview assets are no longer served; remove their files after commit.
    for (var key :
        db.queryForList(
            "SELECT storage_key FROM file_assets WHERE material_id=? AND status='RETIRED'",
            String.class,
            claim.target())) {
      try {
        storage.remove(key);
        db.update(
            "UPDATE file_assets SET status='DELETED' WHERE storage_key=? AND status='RETIRED'",
            key);
      } catch (Exception ignored) {
      }
    }
  }

  private void fence(Claim claim) {
    var courses =
        db.queryForList(
            "SELECT status,lifecycle_version FROM courses WHERE id=? AND owner_id=? FOR UPDATE",
            claim.course(),
            claim.owner());
    if (courses.isEmpty() || !"ACTIVE".equals(courses.get(0).get("status")))
      throw new ApiException(409, "JOB_CANCELLED", "课程已删除，任务已取消");
    var material =
        db.queryForList(
            "SELECT status,lifecycle_version FROM materials WHERE id=? FOR UPDATE", claim.target());
    if (material.isEmpty()
        || !"ACTIVE".equals(material.get(0).get("status"))
        || ((Number) material.get(0).get("lifecycle_version")).longValue() != claim.lifecycle())
      throw new ApiException(409, "JOB_CANCELLED", "课件已删除，任务已取消");
    live(claim);
    Integer current =
        db.queryForObject(
            "SELECT COUNT(*) FROM material_stages WHERE material_id=? AND stage=? AND job_id=? AND"
                + " requested_revision=?",
            Integer.class,
            claim.target(),
            stage(claim.type()),
            claim.id(),
            claim.revision());
    if (current == null || current != 1)
      throw new ApiException(409, "JOB_CANCELLED", "旧任务不能覆盖新的处理结果");
  }

  private void live(Claim claim) {
    var live =
        db.queryForList(
            "SELECT id FROM async_jobs WHERE id=? AND status='RUNNING' AND lease_token=? AND"
                + " lease_until>CURRENT_TIMESTAMP(3) FOR UPDATE",
            claim.id(),
            claim.token());
    if (live.isEmpty()) throw new ApiException(409, "JOB_CANCELLED", "处理任务已失效");
  }

  private void success(Claim claim) {
    db.update(
        "UPDATE async_jobs SET"
            + " status='SUCCEEDED',finished_at=CURRENT_TIMESTAMP(3),result_summary='{}',lease_until=NULL,updated_at=CURRENT_TIMESTAMP(3)"
            + " WHERE id=? AND lease_token=?",
        claim.id(),
        claim.token());
    db.update(
        "UPDATE job_attempts SET outcome='SUCCEEDED',finished_at=CURRENT_TIMESTAMP(3) WHERE"
            + " job_id=? AND lease_token=? AND outcome='RUNNING'",
        claim.id(),
        claim.token());
  }

  private void fail(Claim claim, Exception error) {
    String code = error instanceof ApiException api ? api.code() : "PROCESSING_FAILED";
    String message = error instanceof ApiException api ? api.getMessage() : "课件处理失败，请稍后重试；原件仍可下载";
    tx.executeWithoutResult(
        status -> {
          db.queryForList("SELECT id FROM courses WHERE id=? FOR UPDATE", claim.course());
          if (!claim.type().equals("COURSE_PURGE"))
            db.queryForList("SELECT id FROM materials WHERE id=? FOR UPDATE", claim.target());
          boolean cleanup = claim.type().endsWith("PURGE");
          int changed =
              db.update(
                  "UPDATE async_jobs SET status=IF(? AND"
                      + " attempt_count<max_attempts,'QUEUED','FAILED'),error_code=?,error_message=?,finished_at=CURRENT_TIMESTAMP(3),lease_until=NULL,run_after=TIMESTAMPADD(SECOND,5,CURRENT_TIMESTAMP(3))"
                      + " WHERE id=? AND lease_token=? AND status='RUNNING'",
                  cleanup,
                  code,
                  message,
                  claim.id(),
                  claim.token());
          db.update(
              "UPDATE job_attempts SET"
                  + " outcome='FAILED',error_code=?,finished_at=CURRENT_TIMESTAMP(3) WHERE job_id=?"
                  + " AND lease_token=? AND outcome='RUNNING'",
              code,
              claim.id(),
              claim.token());
          if (changed == 0) return;
          if (stage(claim.type()) != null) {
            db.update(
                "UPDATE material_stages SET status='FAILED',error_code=?,error_message=? WHERE"
                    + " material_id=? AND stage=? AND job_id=?",
                code,
                message,
                claim.target(),
                stage(claim.type()),
                claim.id());
            if (claim.type().equals("PREVIEW_RENDER"))
              db.update(
                  "UPDATE materials SET mapping_status=IF(pdf_asset_id IS"
                      + " NULL,'FAILED',mapping_status) WHERE id=? AND status='ACTIVE'",
                  claim.target());
          }
        });
  }

  private void purge(Claim claim) throws Exception {
    // Logical deletion already denies all reads. A cancelled older worker can only write
    // its own work directory; fencing stops it publishing back into this directory.
    if (claim.type().equals("COURSE_PURGE")) {
      index.removeCourse(claim.course());
      storage.remove(storage.courseKey(claim.course()));
    } else {
      index.removeMaterial(claim.target());
      storage.remove(storage.materialKey(claim.course(), claim.target()));
    }
    tx.executeWithoutResult(
        status -> {
          db.queryForList("SELECT id FROM courses WHERE id=? FOR UPDATE", claim.course());
          live(claim);
          if (claim.type().equals("COURSE_PURGE")) {
            db.update(
                "DELETE l FROM focus_slide_links l JOIN focus_items f ON f.id=l.focus_id WHERE"
                    + " f.course_id=?",
                claim.course());
            db.update("DELETE FROM focus_items WHERE course_id=?", claim.course());
          }
          List<String> ids =
              claim.type().equals("COURSE_PURGE")
                  ? db.queryForList(
                      "SELECT id FROM materials WHERE course_id=?", String.class, claim.course())
                  : List.of(claim.target());
          for (String id : ids) {
            db.queryForList("SELECT id FROM materials WHERE id=? FOR UPDATE", id);
            materials.cancel(id);
            db.update("DELETE FROM reading_progress WHERE material_id=?", id);
            db.update(
                "DELETE s FROM slides s LEFT JOIN focus_slide_links l ON l.slide_id=s.id WHERE"
                    + " s.material_id=? AND l.id IS NULL",
                id);
            db.update(
                "UPDATE slides SET"
                    + " title=NULL,text_content=NULL,preview_asset_id=NULL,thumbnail_asset_id=NULL,deleted_at=CURRENT_TIMESTAMP(3)"
                    + " WHERE material_id=?",
                id);
            db.update("UPDATE file_assets SET status='DELETED' WHERE material_id=?", id);
            db.update(
                "UPDATE materials SET"
                    + " status='DELETED',display_name='[已删除]',sha256=REPEAT('0',64),original_asset_id=NULL,pdf_asset_id=NULL,mapping_status='FAILED',updated_at=CURRENT_TIMESTAMP(3)"
                    + " WHERE id=?",
                id);
            db.update(
                "UPDATE idempotency_requests SET response_body=JSON_OBJECT('materialId',?) WHERE"
                    + " operation='UPLOAD_MATERIAL' AND"
                    + " JSON_UNQUOTE(JSON_EXTRACT(response_body,'$.materialId'))=?",
                id,
                id);
          }
          if (claim.type().equals("COURSE_PURGE")) {
            db.update(
                "UPDATE idempotency_requests SET response_body=JSON_OBJECT('id',?) WHERE"
                    + " operation='CREATE_COURSE' AND"
                    + " JSON_UNQUOTE(JSON_EXTRACT(response_body,'$.id'))=?",
                claim.course(),
                claim.course());
            db.update(
                "UPDATE courses SET"
                    + " status='DELETED',name='[已删除]',remark=NULL,updated_at=CURRENT_TIMESTAMP(3)"
                    + " WHERE id=? AND status='DELETING'",
                claim.course());
          }
          success(claim);
        });
  }

  private String stage(String type) {
    return type.equals("TEXT_EXTRACT")
        ? "TEXT"
        : type.equals("PREVIEW_RENDER") ? "PREVIEW" : type.equals("INDEX_BUILD") ? "INDEX" : null;
  }

  private String workKey(Claim claim) {
    return "work/" + claim.id() + "/" + claim.token();
  }

  public void ensureIndexes() {
    var rows =
        db.queryForList(
            "SELECT m.id,m.course_id,c.owner_id,t.published_revision,i.published_revision AS"
                + " indexed,i.input_text_revision FROM materials m JOIN courses c ON"
                + " c.id=m.course_id JOIN material_stages t ON t.material_id=m.id AND"
                + " t.stage='TEXT' JOIN material_stages i ON i.material_id=m.id AND i.stage='INDEX'"
                + " WHERE m.status='ACTIVE' AND c.status='ACTIVE' AND t.published_revision IS NOT"
                + " NULL AND i.status IN ('NOT_APPLICABLE','NOT_STARTED','SUCCEEDED')");
    for (var row : rows) {
      String id = (String) row.get("id");
      long text = ((Number) row.get("published_revision")).longValue();
      boolean exists = false;
      try {
        if (row.get("indexed") != null
            && row.get("input_text_revision") != null
            && ((Number) row.get("input_text_revision")).longValue() == text)
          exists =
              index.contains(
                  cn.review.search.PageIndex.scope(
                      id, ((Number) row.get("indexed")).longValue(), text));
      } catch (Exception ignored) {
      }
      if (exists) continue;
      tx.executeWithoutResult(
          status -> {
            try {
              var material = materials.lock((String) row.get("owner_id"), id, false);
              var stage =
                  db.queryForMap(
                      "SELECT status FROM material_stages WHERE material_id=? AND stage='INDEX'",
                      id);
              if (List.of("QUEUED", "RUNNING").contains(stage.get("status"))) return;
              long latest =
                  db.queryForObject(
                      "SELECT published_revision FROM material_stages WHERE material_id=? AND"
                          + " stage='TEXT'",
                      Long.class,
                      id);
              queueIndex(material.owner(), material.courseId(), id, latest);
            } catch (ApiException ignored) {
            }
          });
    }
  }

  private void queueIndex(String owner, String course, String id, long textRevision) {
    db.update(
        "UPDATE async_jobs SET status='CANCELLED',finished_at=CURRENT_TIMESTAMP(3) WHERE"
            + " target_id=? AND type='INDEX_BUILD' AND status IN ('QUEUED','RUNNING')",
        id);
    db.update(
        "UPDATE job_attempts a JOIN async_jobs j ON j.id=a.job_id SET"
            + " a.outcome='CANCELLED',a.finished_at=CURRENT_TIMESTAMP(3) WHERE j.target_id=? AND"
            + " j.type='INDEX_BUILD' AND j.status='CANCELLED' AND a.outcome='RUNNING'",
        id);
    long revision =
        db.queryForObject(
                "SELECT requested_revision FROM material_stages WHERE material_id=? AND"
                    + " stage='INDEX'",
                Long.class,
                id)
            + 1;
    long lifecycle =
        db.queryForObject("SELECT lifecycle_version FROM materials WHERE id=?", Long.class, id);
    String job = materials.enqueue(owner, course, id, "INDEX_BUILD", lifecycle, revision);
    db.update(
        "UPDATE async_jobs SET input_snapshot=JSON_SET(input_snapshot,'$.textRevision',?) WHERE"
            + " id=?",
        textRevision,
        job);
    db.update(
        "UPDATE material_stages SET"
            + " status='QUEUED',requested_revision=?,job_id=?,error_code=NULL,error_message=NULL"
            + " WHERE material_id=? AND stage='INDEX'",
        revision,
        job,
        id);
  }

  record Claim(
      String id,
      String course,
      String target,
      String owner,
      String type,
      String token,
      long lifecycle,
      long revision,
      long textRevision) {}
}
