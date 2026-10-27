package cn.review.job;

import cn.review.common.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class JobService {
  private final JdbcTemplate jdbc;
  private final cn.review.material.MaterialPipeline pipeline;
  private final cn.review.common.IdempotencyService idempotency;

  public JobService(
      JdbcTemplate jdbc,
      cn.review.material.MaterialPipeline pipeline,
      cn.review.common.IdempotencyService idempotency) {
    this.jdbc = jdbc;
    this.pipeline = pipeline;
    this.idempotency = idempotency;
  }

  public JobView enqueueCoursePurge(String owner, String course, long lifecycleVersion) {
    jdbc.update(
        "UPDATE materials SET"
            + " status='DELETING',lifecycle_version=lifecycle_version+1,row_version=row_version+1,deleted_at=CURRENT_TIMESTAMP(3)"
            + " WHERE course_id=? AND status='ACTIVE'",
        course);
    jdbc.update(
        "UPDATE async_jobs SET status='CANCELLED',finished_at=CURRENT_TIMESTAMP(3) WHERE"
            + " course_id=? AND type<>'COURSE_PURGE' AND status IN ('QUEUED','RUNNING')",
        course);
    jdbc.update(
        "UPDATE job_attempts a JOIN async_jobs j ON j.id=a.job_id SET"
            + " a.outcome='CANCELLED',a.finished_at=CURRENT_TIMESTAMP(3) WHERE j.course_id=? AND"
            + " j.status='CANCELLED' AND a.outcome='RUNNING'",
        course);
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO"
            + " async_jobs(id,owner_id,course_id,type,target_id,dedup_key,input_snapshot,run_after)"
            + " VALUES(?,?,?,'COURSE_PURGE',?,?,?,?)",
        id,
        owner,
        course,
        course,
        "COURSE_PURGE:" + course + ":" + lifecycleVersion,
        "{\"lifecycleVersion\":" + lifecycleVersion + "}",
        Timestamp.from(Instant.now()));
    return get(owner, id);
  }

  public JobView get(String owner, String id) {
    var list =
        jdbc.query(
            "SELECT j.* FROM async_jobs j JOIN courses c ON c.id=j.course_id LEFT JOIN materials m"
                + " ON m.id=j.target_id LEFT JOIN focus_items f ON f.id=j.target_id WHERE j.id=?"
                + " AND j.owner_id=? AND (j.type='COURSE_PURGE' OR (c.status='ACTIVE' AND"
                + " (m.status='ACTIVE' OR j.type='MATERIAL_PURGE' OR (j.type='FOCUS_LOCATE' AND"
                + " f.deleted_at IS NULL AND f.id IS NOT NULL))))",
            (rs, n) ->
                new JobView(
                    rs.getString("id"),
                    rs.getString("type"),
                    rs.getString("status"),
                    rs.getString("target_id"),
                    rs.getInt("attempt_count"),
                    rs.getInt("max_attempts"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("finished_at") == null
                        ? null
                        : rs.getTimestamp("finished_at").toInstant(),
                    rs.getObject("progress_total") == null
                        ? null
                        : new Progress(rs.getInt("progress_done"), rs.getInt("progress_total")),
                    rs.getString("error_code") == null
                        ? null
                        : new SafeError(
                            rs.getString("error_code"), rs.getString("error_message"), true)),
            id,
            owner);
    if (list.isEmpty()) throw ApiException.missing();
    return list.get(0);
  }

  public boolean processNext() {
    return pipeline.runOne();
  }

  public java.util.List<JobView> cleanup(String owner) {
    return jdbc
        .queryForList(
            "SELECT j.id FROM async_jobs j JOIN courses c ON c.id=j.course_id WHERE j.owner_id=?"
                + " AND (j.type='COURSE_PURGE' OR (j.type='MATERIAL_PURGE' AND c.status='ACTIVE'))"
                + " AND j.status IN ('QUEUED','RUNNING','FAILED') ORDER BY j.created_at DESC LIMIT"
                + " 20",
            String.class,
            owner)
        .stream()
        .map(id -> get(owner, id))
        .toList();
  }

  public JobView retryCleanup(String owner, String id, String key) {
    JobView job = get(owner, id);
    if (!java.util.List.of("COURSE_PURGE", "MATERIAL_PURGE").contains(job.type()))
      throw new ApiException(400, "INVALID_JOB_TYPE", "请通过课件页面重试处理任务");
    return idempotency.execute(
        owner,
        "RETRY_CLEANUP",
        id,
        key,
        java.util.Map.of("jobId", id),
        202,
        JobView.class,
        () -> {
          // Follow the same course -> material -> job lock order as deletion and publication.
          String course =
              jdbc.queryForObject("SELECT course_id FROM async_jobs WHERE id=?", String.class, id);
          jdbc.queryForList("SELECT id FROM courses WHERE id=? FOR UPDATE", course);
          if (job.type().equals("MATERIAL_PURGE"))
            jdbc.queryForList("SELECT id FROM materials WHERE id=? FOR UPDATE", job.targetId());
          var current = jdbc.queryForMap("SELECT status FROM async_jobs WHERE id=? FOR UPDATE", id);
          if ("FAILED".equals(current.get("status")))
            jdbc.update(
                "UPDATE async_jobs SET"
                    + " status='QUEUED',max_attempts=attempt_count+3,error_code=NULL,error_message=NULL,finished_at=NULL,run_after=CURRENT_TIMESTAMP(3)"
                    + " WHERE id=?",
                id);
          return get(owner, id);
        });
  }

  public record JobView(
      String id,
      String type,
      String status,
      String targetId,
      int attemptCount,
      int maxAttempts,
      Instant createdAt,
      Instant finishedAt,
      Progress progress,
      SafeError error) {}

  public record Progress(int done, int total) {}

  public record SafeError(String code, String message, boolean retryable) {}
}
