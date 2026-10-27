package cn.review.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public IdempotencyService(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    @Transactional
    public <T> T execute(String owner,String operation,String resource,String key,Object input,int responseStatus,Class<T> type,Supplier<T> action) {
        if(key==null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw new ApiException(400,"VALIDATION_FAILED","请提供有效的请求标识");
        try {
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(input)));
            String id=UUID.randomUUID().toString();
            int inserted=jdbc.update("INSERT IGNORE INTO idempotency_requests(id,owner_id,operation,resource_id,request_key,request_hash,status,expires_at) VALUES(?,?,?,?,?,?,'PROCESSING',?)",id,owner,operation,resource,key,hash,Timestamp.from(Instant.now().plus(1,ChronoUnit.DAYS)));
            if(inserted==0) {
                var rows=jdbc.queryForList("SELECT request_hash,status,response_body FROM idempotency_requests WHERE owner_id=? AND operation=? AND resource_id=? AND request_key=? FOR UPDATE",owner,operation,resource,key);
                if(rows.isEmpty()) throw new ApiException(409,"REQUEST_IN_PROGRESS","请求正在处理，请稍后重试");
                var row=rows.get(0);
                if(!hash.equals(row.get("request_hash"))) throw new ApiException(409,"IDEMPOTENCY_CONFLICT","相同请求标识不能用于不同内容");
                if(!"COMPLETED".equals(row.get("status"))) throw new ApiException(409,"REQUEST_IN_PROGRESS","请求正在处理，请稍后重试");
                return json.readValue(String.valueOf(row.get("response_body")),type);
            }
            T result=action.get();
            jdbc.update("UPDATE idempotency_requests SET status='COMPLETED',response_status=?,response_body=? WHERE id=?",responseStatus,json.writeValueAsString(result),id);
            return result;
        } catch(ApiException error) { throw error; }
        catch(RuntimeException error) { throw error; }
        catch(Exception error) { throw new IllegalStateException("Could not process idempotent request",error); }
    }
}
