package cn.review.summary;

import cn.review.common.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "app.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SummaryWorker {
  private final SummaryService s;
  private final AtomicBoolean running = new AtomicBoolean();

  public SummaryWorker(SummaryService service) {
    s = service;
  }

  @Scheduled(fixedDelayString = "${app.jobs.poll-delay-ms:1000}")
  public void tick() {
    try {
      runOne();
    } catch (Exception e) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("Summary worker: {}", e.getClass().getSimpleName());
    }
  }

  public boolean runOne() {
    if (!running.compareAndSet(false, true)) return false;
    Claim claim = null;
    ScheduledExecutorService heartbeat = null;
    try {
      recover();
      claim = claim();
      if (claim == null) return false;
      Claim active = claim;
      heartbeat =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "summary-heartbeat");
                t.setDaemon(true);
                return t;
              });
      heartbeat.scheduleAtFixedRate(
          () -> {
            try {
              s.db.update(
                  "UPDATE async_jobs SET"
                      + " lease_until=TIMESTAMPADD(SECOND,90,CURRENT_TIMESTAMP(3)),heartbeat_at=CURRENT_TIMESTAMP(3)"
                      + " WHERE id=? AND lease_token=? AND status='RUNNING' AND"
                      + " lease_until>CURRENT_TIMESTAMP(3)",
                  active.job(),
                  active.token());
            } catch (Exception ignored) {
            }
          },
          10,
          10,
          TimeUnit.SECONDS);
      perform(claim);
      return true;
    } catch (Exception e) {
      if (claim == null) throw new IllegalStateException("Cannot claim summary", e);
      fail(claim, e);
      return true;
    } finally {
      if (heartbeat != null) heartbeat.shutdownNow();
      running.set(false);
    }
  }

  Claim claim() {
    return s.tx.execute(
        t -> {
          var jobs =
              s.db.queryForList(
                  "SELECT j.id,j.owner_id,j.course_id,j.target_id,p.version_id,v.material_id FROM"
                      + " async_jobs j JOIN material_summary_parts p ON p.id=j.target_id JOIN"
                      + " material_summary_versions v ON v.id=p.version_id WHERE"
                      + " j.type='MATERIAL_SUMMARY' AND j.status='QUEUED' AND p.last_job_id=j.id"
                      + " ORDER BY j.created_at,j.id LIMIT 1");
          if (jobs.isEmpty()) return null;
          var j = jobs.get(0);
          String owner = (String) j.get("owner_id"), material = (String) j.get("material_id");
          s.material(owner, material, true);
          String job = (String) j.get("id"), token = SummaryService.id();
          if (s.db.update(
                  "UPDATE async_jobs SET"
                      + " status='RUNNING',attempt_count=attempt_count+1,lease_token=?,lease_until=TIMESTAMPADD(SECOND,90,CURRENT_TIMESTAMP(3)),heartbeat_at=CURRENT_TIMESTAMP(3)"
                      + " WHERE id=? AND status='QUEUED'",
                  token,
                  job)
              == 0) return null;
          s.db.update(
              "UPDATE material_summary_parts SET status='RUNNING' WHERE id=?", j.get("target_id"));
          s.db.update(
              "UPDATE material_summary_versions SET"
                  + " status='RUNNING',updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",
              j.get("version_id"));
          s.db.update(
              "INSERT INTO job_attempts(id,job_id,attempt_no,lease_token,started_at,outcome)"
                  + " VALUES(?,?,1,?,CURRENT_TIMESTAMP(3),'RUNNING')",
              SummaryService.id(),
              job,
              token);
          return new Claim(
              job,
              owner,
              (String) j.get("course_id"),
              material,
              (String) j.get("target_id"),
              (String) j.get("version_id"),
              token);
        });
  }

  boolean cancelled(Claim c) {
    return s.db.queryForObject(
            "SELECT COUNT(*) FROM async_jobs j JOIN courses co ON co.id=j.course_id JOIN materials"
                + " m ON m.id=? WHERE j.id=? AND j.status='RUNNING' AND j.lease_token=? AND"
                + " j.lease_until>CURRENT_TIMESTAMP(3) AND co.status='ACTIVE' AND"
                + " m.status='ACTIVE'",
            Integer.class,
            c.material(),
            c.job(),
            c.token())
        == 0;
  }

  Map<String, Object> fence(Claim c) {
    var version = s.row(c.owner(), c.version(), true);
    if (cancelled(c)
        || !"RUNNING".equals(version.get("status"))
        || s.db.queryForObject(
                "SELECT COUNT(*) FROM material_summary_parts WHERE id=? AND last_job_id=? AND"
                    + " status='RUNNING'",
                Integer.class,
                c.part(),
                c.job())
            == 0) throw new ApiException(409, "SUMMARY_CANCELLED", "摘要任务已停止，旧结果不会发布");
    s.verifySource(c.owner(), version);
    return version;
  }

  void perform(Claim c) {
    var version = s.tx.execute(t -> fence(c));
    var part = s.db.queryForMap("SELECT * FROM material_summary_parts WHERE id=?", c.part());
    boolean fin = "FINAL".equals(part.get("kind"));
    List<SummaryContent.Page> pages =
        s.decode(version.get("source_snapshot"), new TypeReference<>() {});
    List<SummaryContent.Page> selected =
        s.decode(part.get("input_snapshot"), new TypeReference<>() {});
    Object input;
    Set<String> allowed = new HashSet<>();
    if (fin && selected.isEmpty()) {
      var parts =
          s.db.queryForList(
              "SELECT result FROM material_summary_parts WHERE version_id=? AND kind='PART' AND"
                  + " status='SUCCEEDED' ORDER BY position_no",
              c.version());
      int expected =
          s.db.queryForObject(
              "SELECT COUNT(*) FROM material_summary_parts WHERE version_id=? AND kind='PART'",
              Integer.class,
              c.version());
      if (parts.size() != expected || expected == 0)
        throw new ApiException(409, "SUMMARY_INCOMPLETE", "分段尚未全部完成");
      var docs = new ArrayList<SummaryContent.Document>();
      for (var p : parts) {
        SummaryContent.Document doc = s.decode(p.get("result"), new TypeReference<>() {});
        docs.add(doc);
        doc.sections().forEach(sec -> allowed.addAll(sec.slideIds()));
      }
      input = Map.of("parts", docs, "instruction", "综合所有部分重新组织文章，消除重复和衔接断裂，保留各主题关键条件与原始来源");
    } else {
      selected.forEach(p -> allowed.add(p.slideId()));
      input = Map.of("pages", selected);
    }
    String prompt =
        SummaryContent.PROMPT
            + (fin
                ? " 本次为整份课件最终摘要，统一成文，JSON总长度不超过9000字符。"
                : " 本次为内部局部整理，压缩保留核心内容、来源及限制，JSON总长度不超过3000字符。");
    var doc =
        s.ai.complete(
            c.owner(),
            c.course(),
            c.job(),
            fin ? "SUMMARY_FINAL" : "SUMMARY_PART",
            prompt,
            input,
            fin ? 5500 : 1800,
            () -> cancelled(c),
            text -> SummaryContent.validate(s.json, text, allowed, fin ? 9000 : 3000));
    String markdown =
        fin
            ? SummaryContent.markdown(
                doc, pages, c.material(), ((Number) version.get("empty_pages")).intValue())
            : null;
    s.tx.executeWithoutResult(
        t -> {
          fence(c);
          s.db.update(
              "UPDATE material_summary_parts SET"
                  + " status='SUCCEEDED',result=?,input_snapshot=JSON_ARRAY() WHERE id=?",
              s.encode(doc),
              c.part());
          s.db.update(
              "UPDATE async_jobs SET"
                  + " status='SUCCEEDED',finished_at=CURRENT_TIMESTAMP(3),lease_until=NULL WHERE"
                  + " id=?",
              c.job());
          s.db.update(
              "UPDATE job_attempts SET outcome='SUCCEEDED',finished_at=CURRENT_TIMESTAMP(3) WHERE"
                  + " job_id=? AND lease_token=? AND outcome='RUNNING'",
              c.job(),
              c.token());
          if (fin) {
            s.db.update(
                "UPDATE material_summary_versions SET"
                    + " status='SUCCEEDED',markdown=?,error_code=NULL,error_message=NULL,updated_at=CURRENT_TIMESTAMP(3)"
                    + " WHERE id=?",
                markdown,
                c.version());
            s.db.update(
                "UPDATE material_summaries SET current_version_id=?,row_version=row_version+1 WHERE"
                    + " material_id=? AND row_version=?",
                c.version(),
                c.material(),
                version.get("base_head_version"));
          } else s.queueNext(c.owner(), c.course(), c.version());
        });
  }

  void fail(Claim c, Exception e) {
    String code = e instanceof ApiException a ? a.code() : "SUMMARY_FAILED";
    String message = e instanceof ApiException a ? a.getMessage() : "摘要处理失败，请主动重试，已完成进度保留";
    s.tx.executeWithoutResult(
        t -> {
          s.db.queryForList("SELECT id FROM courses WHERE id=? FOR UPDATE", c.course());
          int changed =
              s.db.update(
                  "UPDATE async_jobs SET"
                      + " status='FAILED',error_code=?,error_message=?,finished_at=CURRENT_TIMESTAMP(3),lease_until=NULL"
                      + " WHERE id=? AND status='RUNNING' AND lease_token=?"
                      + (code.equals("WORKER_INTERRUPTED")
                          ? " AND lease_until<CURRENT_TIMESTAMP(3)"
                          : ""),
                  code,
                  message,
                  c.job(),
                  c.token());
          if (changed > 0) {
            s.db.update(
                "UPDATE material_summary_parts SET status='FAILED' WHERE id=? AND last_job_id=?",
                c.part(),
                c.job());
            s.db.update(
                "UPDATE material_summary_versions SET"
                    + " status='FAILED',error_code=?,error_message=?,updated_at=CURRENT_TIMESTAMP(3)"
                    + " WHERE id=? AND status='RUNNING'",
                code,
                message,
                c.version());
          }
          s.db.update(
              "UPDATE job_attempts SET"
                  + " outcome='FAILED',error_code=?,finished_at=CURRENT_TIMESTAMP(3) WHERE job_id=?"
                  + " AND lease_token=? AND outcome='RUNNING'",
              code,
              c.job(),
              c.token());
        });
  }

  public void recover() {
    var jobs =
        s.db.queryForList(
            "SELECT"
                + " j.id,j.owner_id,j.course_id,j.target_id,j.lease_token,p.version_id,v.material_id"
                + " FROM async_jobs j JOIN material_summary_parts p ON p.last_job_id=j.id JOIN"
                + " material_summary_versions v ON v.id=p.version_id WHERE"
                + " j.type='MATERIAL_SUMMARY' AND j.status='RUNNING' AND"
                + " j.lease_until<CURRENT_TIMESTAMP(3)");
    for (var j : jobs)
      fail(
          new Claim(
              (String) j.get("id"),
              (String) j.get("owner_id"),
              (String) j.get("course_id"),
              (String) j.get("material_id"),
              (String) j.get("target_id"),
              (String) j.get("version_id"),
              (String) j.get("lease_token")),
          new ApiException(409, "WORKER_INTERRUPTED", "任务中断，结果可能未知；不会自动重复调用，请主动恢复"));
    s.db.update(
        "UPDATE ai_calls a JOIN async_jobs j ON j.id=a.job_id SET"
            + " a.status='UNKNOWN',a.error_code='WORKER_INTERRUPTED' WHERE"
            + " j.type='MATERIAL_SUMMARY' AND j.status IN ('FAILED','CANCELLED') AND"
            + " a.status='SENDING'");
  }

  record Claim(
      String job,
      String owner,
      String course,
      String material,
      String part,
      String version,
      String token) {}
}
