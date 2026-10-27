package cn.review.chat;

import cn.review.common.ApiException;
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
public class ChatWorker {
  private final ChatService service;
  private final ReaderAiClient ai;
  private final AtomicBoolean running = new AtomicBoolean();

  public ChatWorker(ChatService service, ReaderAiClient ai) {
    this.service = service;
    this.ai = ai;
  }

  @Scheduled(fixedDelayString = "${app.jobs.poll-delay-ms:1000}")
  public void tick() {
    try {
      runOne();
    } catch (Exception e) {
      org.slf4j.LoggerFactory.getLogger(ChatWorker.class)
          .warn("Chat worker failed: {}", e.getClass().getSimpleName());
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
                Thread t = new Thread(r, "chat-lease");
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
    } catch (Exception error) {
      if (claim != null) {
        fail(claim, error);
        return true;
      }
      throw new IllegalStateException("Cannot claim chat task", error);
    } finally {
      if (heartbeat != null) heartbeat.shutdownNow();
      running.set(false);
    }
  }

  Claim claim() {
    return service.tx.execute(
        s -> {
          var jobs =
              service.db.queryForList(
                  "SELECT id,owner_id,course_id,target_id FROM async_jobs WHERE type='READER_CHAT'"
                      + " AND status='QUEUED' ORDER BY created_at,id LIMIT 1");
          if (jobs.isEmpty()) return null;
          var j = jobs.get(0);
          String job = (String) j.get("id"),
              owner = (String) j.get("owner_id"),
              course = (String) j.get("course_id"),
              turn = (String) j.get("target_id"),
              token = UUID.randomUUID().toString();
          var row = service.row(owner, turn, true);
          if (!row.job().equals(job) || !row.status().equals("QUEUED")) return null;
          if (service.db.update(
                  "UPDATE async_jobs SET"
                      + " status='RUNNING',attempt_count=attempt_count+1,lease_token=?,lease_until=TIMESTAMPADD(SECOND,90,CURRENT_TIMESTAMP(3)),heartbeat_at=CURRENT_TIMESTAMP(3)"
                      + " WHERE id=? AND status='QUEUED'",
                  token,
                  job)
              == 0) return null;
          service.db.update(
              "UPDATE chat_turns SET status='RUNNING',updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",
              turn);
          service.db.update(
              "INSERT INTO job_attempts(id,job_id,attempt_no,lease_token,started_at,outcome)"
                  + " VALUES(?,?,1,?,CURRENT_TIMESTAMP(3),'RUNNING')",
              UUID.randomUUID().toString(),
              job,
              token);
          return new Claim(job, owner, course, turn, token);
        });
  }

  ChatService.Row fence(Claim claim) {
    var row = service.row(claim.owner(), claim.turn(), true);
    if (!row.job().equals(claim.job()) || !row.status().equals("RUNNING") || cancelled(claim))
      throw new ApiException(409, "CHAT_CANCELLED", "本次回答已停止");
    service.validateSources(row);
    return row;
  }

  boolean cancelled(Claim claim) {
    return service.db.queryForObject(
            "SELECT COUNT(*) FROM async_jobs WHERE id=? AND status='RUNNING' AND lease_token=? AND"
                + " lease_until>CURRENT_TIMESTAMP(3)",
            Integer.class,
            claim.job(),
            claim.token())
        == 0;
  }

  void perform(Claim claim) throws Exception {
    var row = service.tx.execute(s -> fence(claim));
    String image = service.image(claim.owner(), row.sources().get(0));
    // File I/O takes place outside transactions. Recheck deletion/version before the external
    // request.
    service.tx.executeWithoutResult(s -> fence(claim));
    var answer =
        ai.ask(
            claim.owner(),
            claim.course(),
            claim.job(),
            row.question(),
            row.sources(),
            row.history(),
            image,
            () -> cancelled(claim));
    service.tx.executeWithoutResult(
        s -> {
          fence(claim);
          service.db.update(
              "UPDATE chat_turns SET status='SUCCEEDED',answer=?,updated_at=CURRENT_TIMESTAMP(3)"
                  + " WHERE id=?",
              service.encode(answer),
              claim.turn());
          service.db.update(
              "UPDATE chat_sessions SET updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",
              row.session().id());
          service.db.update(
              "UPDATE async_jobs SET"
                  + " status='SUCCEEDED',finished_at=CURRENT_TIMESTAMP(3),lease_until=NULL WHERE"
                  + " id=? AND lease_token=?",
              claim.job(),
              claim.token());
          service.db.update(
              "UPDATE job_attempts SET outcome='SUCCEEDED',finished_at=CURRENT_TIMESTAMP(3) WHERE"
                  + " job_id=? AND lease_token=? AND outcome='RUNNING'",
              claim.job(),
              claim.token());
        });
  }

  void fail(Claim claim, Exception error) {
    String code = error instanceof ApiException a ? a.code() : "CHAT_FAILED";
    String message = error instanceof ApiException a ? a.getMessage() : "回答失败，请稍后重试";
    service.tx.executeWithoutResult(
        s -> {
          service.db.queryForList("SELECT id FROM courses WHERE id=? FOR UPDATE", claim.course());
          int changed =
              service.db.update(
                  "UPDATE async_jobs SET"
                      + " status='FAILED',error_code=?,error_message=?,finished_at=CURRENT_TIMESTAMP(3),lease_until=NULL"
                      + " WHERE id=? AND status='RUNNING' AND lease_token=?",
                  code,
                  message,
                  claim.job(),
                  claim.token());
          if (changed > 0)
            service.db.update(
                "UPDATE chat_turns SET"
                    + " status='FAILED',error_code=?,error_message=?,updated_at=CURRENT_TIMESTAMP(3)"
                    + " WHERE id=? AND last_job_id=? AND status='RUNNING'",
                code,
                message,
                claim.turn(),
                claim.job());
          service.db.update(
              "UPDATE job_attempts SET"
                  + " outcome='FAILED',error_code=?,finished_at=CURRENT_TIMESTAMP(3) WHERE job_id=?"
                  + " AND lease_token=? AND outcome='RUNNING'",
              code,
              claim.job(),
              claim.token());
        });
  }

  public void recover() {
    var jobs =
        service.db.queryForList(
            "SELECT id,course_id,target_id FROM async_jobs WHERE type='READER_CHAT' AND"
                + " status='RUNNING' AND lease_until<CURRENT_TIMESTAMP(3)");
    for (var j : jobs)
      service.tx.executeWithoutResult(
          s -> {
            service.db.queryForList(
                "SELECT id FROM courses WHERE id=? FOR UPDATE", j.get("course_id"));
            int changed =
                service.db.update(
                    "UPDATE async_jobs SET"
                        + " status='FAILED',error_code='WORKER_INTERRUPTED',error_message='回答中断，不会自动重新调用"
                        + " AI',finished_at=CURRENT_TIMESTAMP(3),lease_until=NULL WHERE id=? AND"
                        + " status='RUNNING' AND lease_until<CURRENT_TIMESTAMP(3)",
                    j.get("id"));
            if (changed == 0) return;
            service.db.update(
                "UPDATE chat_turns SET"
                    + " status='FAILED',error_code='WORKER_INTERRUPTED',error_message='回答中断，结果未知；请自行决定是否重试',updated_at=CURRENT_TIMESTAMP(3)"
                    + " WHERE id=? AND last_job_id=? AND status='RUNNING'",
                j.get("target_id"),
                j.get("id"));
            service.db.update(
                "UPDATE ai_calls SET status='UNKNOWN',error_code='WORKER_INTERRUPTED' WHERE"
                    + " job_id=? AND status='SENDING'",
                j.get("id"));
            service.db.update(
                "UPDATE job_attempts SET"
                    + " outcome='INTERRUPTED',error_code='WORKER_INTERRUPTED',finished_at=CURRENT_TIMESTAMP(3)"
                    + " WHERE job_id=? AND outcome='RUNNING'",
                j.get("id"));
          });
    // A process can exit after cancellation but before the HTTP task records its outcome.
    service.db.update(
        "UPDATE ai_calls a JOIN async_jobs j ON j.id=a.job_id SET"
            + " a.status='UNKNOWN',a.error_code='CHAT_CANCELLED' WHERE a.scene='READER_CHAT' AND"
            + " a.status='SENDING' AND j.status='CANCELLED'");
  }

  record Claim(String job, String owner, String course, String turn, String token) {}
}
