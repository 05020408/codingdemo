package cn.review.focus;

import cn.review.common.ApiException;
import cn.review.search.DeepSeekReranker;
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
public class FocusWorker {
  private final FocusService service;
  private final DeepSeekReranker ai;
  private final AtomicBoolean running = new AtomicBoolean();

  public FocusWorker(FocusService service, DeepSeekReranker ai) {
    this.service = service;
    this.ai = ai;
  }

  @Scheduled(fixedDelayString = "${app.jobs.poll-delay-ms:1000}")
  public void tick() {
    runOne();
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
                Thread t = new Thread(r, "focus-lease");
                t.setDaemon(true);
                return t;
              });
      heartbeat.scheduleAtFixedRate(
          () -> {
            try {
              service.db.update(
                  "UPDATE async_jobs SET"
                      + " lease_until=TIMESTAMPADD(SECOND,90,CURRENT_TIMESTAMP(3)),heartbeat_at=CURRENT_TIMESTAMP(3)"
                      + " WHERE id=? AND lease_token=? AND status='RUNNING' AND"
                      + " lease_until>CURRENT_TIMESTAMP(3)",
                  active.id(),
                  active.token());
            } catch (Exception ignored) {
            }
          },
          10,
          10,
          TimeUnit.SECONDS);
      perform(claim);
      return true;
    } catch (Exception error) {
      if (claim == null) throw new IllegalStateException("Cannot claim focus task", error);
      fail(claim, error);
      return true;
    } finally {
      if (heartbeat != null) heartbeat.shutdownNow();
      running.set(false);
    }
  }

  public void recover() {
    service.tx.executeWithoutResult(
        s -> {
          var expired =
              service.db.queryForList(
                  "SELECT id,target_id FROM async_jobs WHERE type='FOCUS_LOCATE' AND"
                      + " status='RUNNING' AND lease_until<CURRENT_TIMESTAMP(3)");
          for (var job : expired) {
            int changed =
                service.db.update(
                    "UPDATE async_jobs SET"
                        + " status='FAILED',error_code='WORKER_INTERRUPTED',error_message='定位被中断，请手动重新定位；不会自动重复调用"
                        + " AI',finished_at=CURRENT_TIMESTAMP(3),lease_until=NULL WHERE id=? AND"
                        + " status='RUNNING' AND lease_until<CURRENT_TIMESTAMP(3)",
                    job.get("id"));
            if (changed == 0) continue;
            service.db.update(
                "UPDATE job_attempts SET"
                    + " outcome='INTERRUPTED',error_code='WORKER_INTERRUPTED',finished_at=CURRENT_TIMESTAMP(3)"
                    + " WHERE job_id=? AND outcome='RUNNING'",
                job.get("id"));
            service.db.update(
                "UPDATE ai_calls SET status='UNKNOWN',error_code='WORKER_INTERRUPTED' WHERE"
                    + " job_id=? AND status='SENDING'",
                job.get("id"));
            service.db.update(
                "UPDATE focus_items SET"
                    + " match_status='FAILED',error_code='WORKER_INTERRUPTED',error_message='定位中断，请重新定位'"
                    + " WHERE id=? AND last_locate_job_id=? AND deleted_at IS NULL",
                job.get("target_id"),
                job.get("id"));
          }
        });
  }

  Claim claim() {
    return service.tx.execute(
        s -> {
          var rows =
              service.db.queryForList(
                  "SELECT * FROM async_jobs WHERE type='FOCUS_LOCATE' AND status='QUEUED' AND"
                      + " run_after<=CURRENT_TIMESTAMP(3) ORDER BY created_at,id LIMIT 1");
          if (rows.isEmpty()) return null;
          var row = rows.get(0);
          String owner = (String) row.get("owner_id"),
              focus = (String) row.get("target_id"),
              id = (String) row.get("id");
          service.lock(owner, focus);
          String token = UUID.randomUUID().toString();
          if (service.db.update(
                  "UPDATE async_jobs SET"
                      + " status='RUNNING',attempt_count=attempt_count+1,lease_token=?,lease_until=TIMESTAMPADD(SECOND,90,CURRENT_TIMESTAMP(3)),heartbeat_at=CURRENT_TIMESTAMP(3)"
                      + " WHERE id=? AND status='QUEUED'",
                  token,
                  id)
              == 0) return null;
          service.db.update(
              "INSERT INTO job_attempts(id,job_id,attempt_no,lease_token,started_at,outcome)"
                  + " VALUES(?,?,1,?,CURRENT_TIMESTAMP(3),'RUNNING')",
              UUID.randomUUID().toString(),
              id,
              token);
          return new Claim(
              id,
              owner,
              (String) row.get("course_id"),
              focus,
              token,
              service.decode(
                  String.valueOf(row.get("input_snapshot")),
                  new TypeReference<FocusService.Snapshot>() {}));
        });
  }

  void perform(Claim claim) throws Exception {
    var focus = service.row(claim.owner(), claim.focus(), false);
    if (focus.revision() != claim.snapshot().revision())
      throw new ApiException(409, "SOURCE_CHANGED", "重点内容已修改，请重新定位");
    var candidates =
        service.catalog.search(
            claim.owner(), claim.course(), claim.snapshot().scope(), focus.content());
    var warnings = new ArrayList<String>();
    var capabilities = service.capabilities(claim.owner(), claim.course());
    if (claim.snapshot().scope().size() < capabilities.totalMaterials())
      warnings.add("PARTIAL_INDEX");
    List<DeepSeekReranker.Match> matches =
        candidates.stream()
            .limit(5)
            .map(c -> new DeepSeekReranker.Match(c, c.evidenceText(), "关键词匹配，请结合原文确认相关性"))
            .toList();
    String method = "KEYWORD";
    if (claim.snapshot().useAi() && !candidates.isEmpty()) {
      // Recheck ownership and the lease before exposing any text to the provider.
      service.tx.executeWithoutResult(s -> fence(claim));
      var result = ai.rank(claim.owner(), claim.course(), claim.id(), focus.content(), candidates);
      if (result.warning() == null) {
        matches = result.matches();
        method = "AI_RERANK";
      } else warnings.add(result.warning());
    }
    var finalMatches = matches;
    String finalMethod = method;
    service.tx.executeWithoutResult(
        s -> {
          var current = fence(claim);
          service.db.update(
              "DELETE FROM focus_slide_links WHERE focus_id=? AND decision='CANDIDATE'",
              claim.focus());
          for (var match : finalMatches) {
            if (service.db.queryForObject(
                    "SELECT COUNT(*) FROM focus_slide_links WHERE focus_id=? AND slide_id=?",
                    Integer.class,
                    claim.focus(),
                    match.candidate().slideId())
                == 0)
              service.saveLink(
                  current,
                  match.candidate(),
                  "AUTO",
                  "CANDIDATE",
                  claim.id(),
                  match.quote(),
                  match.reason());
          }
          int links =
              service.db.queryForObject(
                  "SELECT COUNT(*) FROM focus_slide_links WHERE focus_id=? AND"
                      + " decision<>'REJECTED'",
                  Integer.class,
                  claim.focus());
          service.db.update(
              "UPDATE focus_items SET"
                  + " match_status=?,locate_method=?,locate_warnings=?,last_scope=?,error_code=NULL,error_message=NULL,updated_at=CURRENT_TIMESTAMP(3)"
                  + " WHERE id=? AND last_locate_job_id=?",
              links > 0 ? "MATCHED" : "NO_MATCH",
              finalMethod,
              service.encode(warnings),
              service.encode(claim.snapshot().scope()),
              claim.focus(),
              claim.id());
          service.db.update(
              "UPDATE async_jobs SET"
                  + " status='SUCCEEDED',finished_at=CURRENT_TIMESTAMP(3),lease_until=NULL,result_summary=?"
                  + " WHERE id=? AND lease_token=?",
              service.encode(Map.of("count", finalMatches.size(), "method", finalMethod)),
              claim.id(),
              claim.token());
          service.db.update(
              "UPDATE job_attempts SET outcome='SUCCEEDED',finished_at=CURRENT_TIMESTAMP(3) WHERE"
                  + " job_id=? AND lease_token=? AND outcome='RUNNING'",
              claim.id(),
              claim.token());
        });
  }

  FocusService.Row fence(Claim claim) {
    var row = service.lock(claim.owner(), claim.focus());
    var jobs =
        service.db.queryForList(
            "SELECT id FROM async_jobs WHERE id=? AND status='RUNNING' AND lease_token=? AND"
                + " lease_until>CURRENT_TIMESTAMP(3) FOR UPDATE",
            claim.id(),
            claim.token());
    if (jobs.isEmpty()
        || !Objects.equals(row.job(), claim.id())
        || row.revision() != claim.snapshot().revision())
      throw new ApiException(409, "JOB_CANCELLED", "任务已取消，旧结果不会覆盖新内容");
    if (!service.catalog.current(claim.owner(), claim.course(), claim.snapshot().scope()))
      throw new ApiException(409, "SOURCE_CHANGED", "课件内容或检索范围已更新，请重新定位");
    return row;
  }

  void fail(Claim claim, Exception error) {
    String code = error instanceof ApiException api ? api.code() : "LOCATE_FAILED";
    String message = error instanceof ApiException api ? api.getMessage() : "定位失败，请稍后重新定位";
    service.tx.executeWithoutResult(
        s -> {
          service.db.queryForList("SELECT id FROM courses WHERE id=? FOR UPDATE", claim.course());
          service.db.queryForList(
              "SELECT id FROM focus_items WHERE id=? FOR UPDATE", claim.focus());
          int changed =
              service.db.update(
                  "UPDATE async_jobs SET"
                      + " status='FAILED',error_code=?,error_message=?,finished_at=CURRENT_TIMESTAMP(3),lease_until=NULL"
                      + " WHERE id=? AND lease_token=? AND status='RUNNING'",
                  code,
                  message,
                  claim.id(),
                  claim.token());
          service.db.update(
              "UPDATE job_attempts SET"
                  + " outcome='FAILED',error_code=?,finished_at=CURRENT_TIMESTAMP(3) WHERE job_id=?"
                  + " AND lease_token=? AND outcome='RUNNING'",
              code,
              claim.id(),
              claim.token());
          if (changed > 0)
            service.db.update(
                "UPDATE focus_items SET match_status=?,error_code=?,error_message=? WHERE id=? AND"
                    + " last_locate_job_id=? AND deleted_at IS NULL",
                code.equals("SOURCE_CHANGED") ? "NEEDS_REVIEW" : "FAILED",
                code,
                message,
                claim.focus(),
                claim.id());
        });
  }

  record Claim(
      String id,
      String owner,
      String course,
      String focus,
      String token,
      FocusService.Snapshot snapshot) {}
}
