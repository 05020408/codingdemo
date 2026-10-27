package cn.review.job;

import cn.review.common.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobService {
    private final JdbcTemplate jdbc;
    public JobService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public JobView enqueueCoursePurge(String owner,String course,long lifecycleVersion) {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO async_jobs(id,owner_id,course_id,type,target_id,dedup_key,input_snapshot,run_after) VALUES(?,?,?,'COURSE_PURGE',?,?,?,?)",id,owner,course,course,"COURSE_PURGE:"+course+":"+lifecycleVersion,"{\"lifecycleVersion\":"+lifecycleVersion+"}",Timestamp.from(Instant.now()));
        return get(owner,id);
    }
    public JobView get(String owner,String id) {
        var list=jdbc.query("SELECT j.id,j.type,j.status,j.target_id,j.attempt_count,j.max_attempts,j.created_at,j.finished_at FROM async_jobs j JOIN courses c ON c.id=j.course_id WHERE j.id=? AND j.owner_id=? AND (c.status='ACTIVE' OR j.type='COURSE_PURGE')",(rs,n)->new JobView(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5),rs.getInt(6),rs.getTimestamp(7).toInstant(),rs.getTimestamp(8)==null?null:rs.getTimestamp(8).toInstant()),id,owner);
        if(list.isEmpty()) throw ApiException.missing();
        return list.get(0);
    }
    @Transactional
    public boolean processNext() {
        var candidates=jdbc.queryForList("SELECT id,course_id FROM async_jobs WHERE type='COURSE_PURGE' AND status='QUEUED' AND run_after<=CURRENT_TIMESTAMP(3) ORDER BY created_at LIMIT 1");
        if(candidates.isEmpty()) return false;
        String id=String.valueOf(candidates.get(0).get("id"));
        String course=String.valueOf(candidates.get(0).get("course_id"));
        // Lock the lifecycle root before the job, matching the deletion lock order.
        jdbc.queryForList("SELECT id FROM courses WHERE id=? FOR UPDATE",course);
        String lease=UUID.randomUUID().toString();
        int claimed=jdbc.update("UPDATE async_jobs SET status='RUNNING',attempt_count=attempt_count+1,lease_token=?,lease_until=?,heartbeat_at=CURRENT_TIMESTAMP(3) WHERE id=? AND status='QUEUED'",lease,Timestamp.from(Instant.now().plusSeconds(60)),id);
        if(claimed==0) return false;
        String attempt=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO job_attempts(id,job_id,attempt_no,lease_token,started_at,outcome) SELECT ?,id,attempt_count,?,CURRENT_TIMESTAMP(3),'RUNNING' FROM async_jobs WHERE id=?",attempt,lease,id);
        // This increment has only account/course data. Future material cleanup must
        // be added before enabling uploads; no external work runs in this transaction.
        // Retain the key/hash tombstone so a delayed create retry cannot resurrect a deleted course.
        jdbc.update("UPDATE idempotency_requests SET response_body=JSON_OBJECT('id',?) WHERE operation='CREATE_COURSE' AND JSON_UNQUOTE(JSON_EXTRACT(response_body,'$.id'))=?",course,course);
        jdbc.update("UPDATE courses SET status='DELETED',name='[已删除]',remark=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND status='DELETING'",course);
        jdbc.update("UPDATE job_attempts SET outcome='SUCCEEDED',finished_at=CURRENT_TIMESTAMP(3) WHERE id=? AND lease_token=?",attempt,lease);
        jdbc.update("UPDATE async_jobs SET status='SUCCEEDED',result_summary='{}',finished_at=CURRENT_TIMESTAMP(3),lease_until=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND lease_token=?",id,lease);
        return true;
    }
    public record JobView(String id,String type,String status,String targetId,int attemptCount,int maxAttempts,Instant createdAt,Instant finishedAt) {}
}
