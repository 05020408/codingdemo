package cn.review.chat;

import cn.review.common.*;
import cn.review.material.MaterialService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ChatService {
  final JdbcTemplate db;
  final TransactionTemplate tx;
  final ObjectMapper json;
  final ReaderAiClient ai;
  final MaterialService materials;
  private final IdempotencyService idem;

  public ChatService(
      JdbcTemplate db,
      TransactionTemplate tx,
      ObjectMapper json,
      ReaderAiClient ai,
      MaterialService materials,
      IdempotencyService idem) {
    this.db = db;
    this.tx = tx;
    this.json = json;
    this.ai = ai;
    this.materials = materials;
    this.idem = idem;
  }

  public Capabilities capabilities(String owner, String material) {
    var rows =
        db.queryForList(
            "SELECT m.id FROM materials m JOIN courses c ON c.id=m.course_id WHERE m.id=? AND"
                + " c.owner_id=? AND c.status='ACTIVE' AND m.status='ACTIVE'",
            material,
            owner);
    if (rows.isEmpty()) throw ApiException.missing();
    return new Capabilities(ai.available(), ai.visionAvailable(), ai.remaining(owner), 2000, 100);
  }

  void course(String owner, String course, boolean lock) {
    if (db.queryForList(
            "SELECT id FROM courses WHERE id=? AND owner_id=? AND status='ACTIVE'"
                + (lock ? " FOR UPDATE" : ""),
            course,
            owner)
        .isEmpty()) throw ApiException.missing();
  }

  Session session(String owner, String id, boolean lock) {
    var rows = db.queryForList("SELECT course_id FROM chat_sessions WHERE id=?", id);
    if (rows.isEmpty()) throw ApiException.missing();
    course(owner, (String) rows.get(0).get("course_id"), lock);
    return db.queryForObject(
        "SELECT s.*,m.status AS material_status FROM chat_sessions s JOIN materials m ON"
            + " m.id=s.material_id WHERE s.id=?"
            + (lock ? " FOR UPDATE" : ""),
        (rs, n) ->
            new Session(
                rs.getString("id"),
                rs.getString("course_id"),
                rs.getString("material_id"),
                rs.getString("title"),
                rs.getTimestamp("updated_at").toInstant().toString(),
                rs.getString("material_status").equals("ACTIVE")),
        id);
  }

  public List<Session> sessions(String owner, String course, String material) {
    course(owner, course, false);
    var ids =
        db.queryForList(
            "SELECT id FROM chat_sessions WHERE course_id=?"
                + (material == null ? "" : " AND material_id=?")
                + " ORDER BY updated_at DESC,id LIMIT 50",
            String.class,
            material == null ? new Object[] {course} : new Object[] {course, material});
    return ids.stream().map(id -> session(owner, id, false)).toList();
  }

  public Session create(String owner, String material, String key) {
    capabilities(owner, material);
    var ack =
        idem.execute(
            owner,
            "CREATE_CHAT",
            material,
            key,
            Map.of("materialId", material),
            201,
            Ack.class,
            () -> {
              var row =
                  db.queryForMap(
                      "SELECT course_id,display_name FROM materials WHERE id=?", material);
              course(owner, (String) row.get("course_id"), true);
              capabilities(owner, material);
              String id = UUID.randomUUID().toString();
              db.update(
                  "INSERT INTO chat_sessions(id,course_id,material_id,title) VALUES(?,?,?,?)",
                  id,
                  row.get("course_id"),
                  material,
                  "新的课件答疑");
              return new Ack(id);
            });
    return session(owner, ack.id(), false);
  }

  public Conversation get(String owner, String id) {
    var session = session(owner, id, false);
    var ids =
        db.queryForList(
            "SELECT id FROM chat_turns WHERE session_id=? ORDER BY sequence_no LIMIT 100",
            String.class,
            id);
    return new Conversation(session, ids.stream().map(t -> turn(owner, t)).toList());
  }

  Row row(String owner, String id, boolean lock) {
    var sessions =
        db.queryForList("SELECT session_id FROM chat_turns WHERE id=?", String.class, id);
    if (sessions.isEmpty()) throw ApiException.missing();
    Session session = session(owner, sessions.get(0), lock);
    return db.queryForObject(
        "SELECT * FROM chat_turns WHERE id=?" + (lock ? " FOR UPDATE" : ""),
        (rs, n) ->
            new Row(
                rs.getString("id"),
                session,
                rs.getInt("sequence_no"),
                rs.getString("question"),
                decode(rs.getString("context_snapshot"), new TypeReference<List<Source>>() {}),
                decode(rs.getString("history_snapshot"), new TypeReference<List<History>>() {}),
                rs.getString("status"),
                rs.getString("last_job_id"),
                rs.getString("answer") == null
                    ? null
                    : decode(rs.getString("answer"), new TypeReference<ReaderAiClient.Answer>() {}),
                rs.getString("error_code"),
                rs.getString("error_message")),
        id);
  }

  public Turn turn(String owner, String id) {
    Row row = row(owner, id, false);
    return new Turn(
        row.id(),
        row.session().id(),
        row.sequence(),
        row.question(),
        row.status(),
        row.job(),
        row.answer(),
        row.sources().stream()
            .map(
                s -> {
                  var current =
                      db.queryForList(
                          "SELECT s.text_revision,s.preview_asset_id FROM slides s JOIN materials m"
                              + " ON m.id=s.material_id WHERE s.id=? AND s.deleted_at IS NULL AND"
                              + " m.status='ACTIVE'",
                          s.slideId());
                  boolean available = !current.isEmpty();
                  boolean changed =
                      available
                          && (!Objects.equals(current.get(0).get("text_revision"), s.textRevision())
                              || s.imageAsset() != null
                                  && !Objects.equals(
                                      current.get(0).get("preview_asset_id"), s.imageAsset()));
                  return new SourceView(
                      s.slideId(),
                      s.materialId(),
                      s.materialName(),
                      s.pageNo(),
                      s.imageAsset() != null,
                      s.truncated(),
                      available,
                      changed);
                })
            .toList(),
        row.errorCode(),
        row.errorMessage());
  }

  public Turn send(String owner, String id, Send input, String key) {
    session(owner, id, false);
    String question = ApiException.text(input.question(), "问题", 1, 2000);
    if (input.slideId() == null) throw new ApiException(400, "VALIDATION_FAILED", "请选择提问页面");
    Ack ack =
        idem.execute(
            owner,
            "SEND_CHAT",
            id,
            key,
            new Send(question, input.slideId(), input.useImage(), input.continuePrevious()),
            202,
            Ack.class,
            () -> {
              Session session = session(owner, id, true);
              checkIdle(owner, session.courseId());
              if (!ai.available()) throw new ApiException(503, "AI_DISABLED", "AI 答疑尚未启用，请联系管理员配置");
              if (input.useImage() && !ai.visionAvailable())
                throw new ApiException(400, "VISION_DISABLED", "当前未启用图片答疑，请取消附图后发送");
              int sequence =
                  db.queryForObject(
                      "SELECT COALESCE(MAX(sequence_no),0)+1 FROM chat_turns WHERE session_id=?",
                      Integer.class,
                      id);
              if (sequence > 100) throw new ApiException(409, "CHAT_FULL", "本段对话已满 100 轮，请新建对话");
              var selected = source(owner, session, input.slideId(), input.useImage());
              var sources = new ArrayList<Source>();
              sources.add(selected);
              var history = new ArrayList<History>();
              if (input.continuePrevious()) {
                var previous =
                    db.queryForList(
                        "SELECT id FROM chat_turns WHERE session_id=? AND status='SUCCEEDED' ORDER"
                            + " BY sequence_no DESC LIMIT 4",
                        String.class,
                        id);
                Collections.reverse(previous);
                for (String prior : previous) {
                  Row row = row(owner, prior, false);
                  history.add(
                      new History(
                          shorten(row.question(), 1000),
                          shorten(
                              row.answer().explanation()
                                  + "\n"
                                  + row.answer().supplement()
                                  + "\n"
                                  + row.answer().limitations(),
                              2000)));
                }
                if (!previous.isEmpty()) {
                  Row last = row(owner, previous.get(previous.size() - 1), false);
                  for (Source s : last.sources())
                    if (sources.size() < 3
                        && sources.stream().noneMatch(x -> x.slideId().equals(s.slideId()))) {
                      try {
                        sources.add(source(owner, session, s.slideId(), false));
                      } catch (ApiException ignored) {
                        /* Deleted prior pages aren't sent. */
                      }
                    }
                }
              }
              String turn = UUID.randomUUID().toString(), job = UUID.randomUUID().toString();
              db.update(
                  "INSERT INTO"
                      + " chat_turns(id,session_id,sequence_no,question,context_snapshot,history_snapshot,last_job_id)"
                      + " VALUES(?,?,?,?,?,?,?)",
                  turn,
                  id,
                  sequence,
                  question,
                  encode(sources),
                  encode(history),
                  job);
              queue(owner, session, turn, job);
              db.update(
                  "UPDATE chat_sessions SET title=IF(?=1,?,title),updated_at=CURRENT_TIMESTAMP(3)"
                      + " WHERE id=?",
                  sequence,
                  shorten(question, 80),
                  id);
              return new Ack(turn);
            });
    return turn(owner, ack.id());
  }

  private void checkIdle(String owner, String course) {
    // Course lock serializes submission, cancellation and publication. At most one active call per
    // course.
    if (db.queryForObject(
            "SELECT COUNT(*) FROM chat_turns t JOIN chat_sessions s ON s.id=t.session_id WHERE"
                + " s.course_id=? AND t.status IN ('QUEUED','RUNNING')",
            Integer.class,
            course)
        > 0) throw new ApiException(409, "CHAT_BUSY", "本课程还有回答生成中，请等待或停止后再提问");
  }

  Source source(String owner, Session session, String slide, boolean image) {
    var rows =
        db.queryForList(
            "SELECT s.*,m.display_name FROM slides s JOIN materials m ON m.id=s.material_id WHERE"
                + " s.id=? AND s.material_id=? AND s.deleted_at IS NULL AND m.status='ACTIVE'",
            slide,
            session.materialId());
    if (rows.isEmpty()) throw new ApiException(409, "SOURCE_UNAVAILABLE", "提问页面已不可用，请重新打开课件");
    var row = rows.get(0);
    String text = Objects.toString(row.get("text_content"), "");
    String asset = image ? (String) row.get("preview_asset_id") : null;
    if (image && asset == null) throw new ApiException(409, "IMAGE_NOT_READY", "本页图片尚未就绪，请稍后重试");
    if (text.isBlank() && !image)
      throw new ApiException(409, "PAGE_HAS_NO_TEXT", "本页没有可提取文字，请附带本页图片后提问");
    return new Source(
        slide,
        session.materialId(),
        (String) row.get("display_name"),
        ((Number) row.get("page_no")).intValue(),
        (Long) row.get("text_revision"),
        shorten(text, 12000),
        text.length() > 12000,
        asset);
  }

  public Turn retry(String owner, String id, String key) {
    row(owner, id, false);
    Ack ack =
        idem.execute(
            owner,
            "RETRY_CHAT",
            id,
            key,
            Map.of("turnId", id),
            202,
            Ack.class,
            () -> {
              Row row = row(owner, id, true);
              if (!List.of("FAILED", "CANCELLED").contains(row.status()))
                throw new ApiException(409, "CHAT_NOT_RETRYABLE", "只有失败或停止的回答可以重试");
              checkIdle(owner, row.session().courseId());
              if (!ai.available()) throw new ApiException(503, "AI_DISABLED", "AI 答疑尚未启用");
              if (row.sources().get(0).imageAsset() != null && !ai.visionAvailable())
                throw new ApiException(409, "VISION_DISABLED", "请启用图片答疑后重试，或重新发送文字问题");
              if (db.queryForObject(
                      "SELECT COALESCE(MAX(sequence_no),0) FROM chat_turns WHERE session_id=?",
                      Integer.class,
                      row.session().id())
                  != row.sequence())
                throw new ApiException(409, "CHAT_RETRY_ORDER", "后面已有新问题，请复制原问题重新发送");
              validateSources(row);
              String job = UUID.randomUUID().toString();
              db.update(
                  "UPDATE chat_turns SET"
                      + " status='QUEUED',last_job_id=?,error_code=NULL,error_message=NULL,answer=NULL,updated_at=CURRENT_TIMESTAMP(3)"
                      + " WHERE id=?",
                  job,
                  id);
              queue(owner, row.session(), id, job);
              return new Ack(id);
            });
    return turn(owner, ack.id());
  }

  public Turn cancel(String owner, String id) {
    tx.executeWithoutResult(
        s -> {
          Row row = row(owner, id, true);
          if (!List.of("QUEUED", "RUNNING").contains(row.status())) return;
          db.update(
              "UPDATE chat_turns SET"
                  + " status='CANCELLED',error_code='CHAT_CANCELLED',error_message='已停止；已发出的请求仍可能计费',updated_at=CURRENT_TIMESTAMP(3)"
                  + " WHERE id=?",
              id);
          db.update(
              "UPDATE async_jobs SET"
                  + " status='CANCELLED',finished_at=CURRENT_TIMESTAMP(3),lease_until=NULL WHERE"
                  + " id=? AND status IN ('QUEUED','RUNNING')",
              row.job());
          db.update(
              "UPDATE job_attempts SET outcome='CANCELLED',finished_at=CURRENT_TIMESTAMP(3) WHERE"
                  + " job_id=? AND outcome='RUNNING'",
              row.job());
        });
    return turn(owner, id);
  }

  void queue(String owner, Session session, String turn, String job) {
    db.update(
        "INSERT INTO"
            + " async_jobs(id,owner_id,course_id,type,target_id,dedup_key,input_snapshot,run_after,max_attempts)"
            + " VALUES(?,?,?,'READER_CHAT',?,?,JSON_OBJECT(),CURRENT_TIMESTAMP(3),1)",
        job,
        owner,
        session.courseId(),
        turn,
        "CHAT:" + job);
  }

  void validateSources(Row row) {
    for (Source source : row.sources()) {
      var current =
          db.queryForList(
              "SELECT s.text_revision,s.preview_asset_id FROM slides s JOIN materials m ON"
                  + " m.id=s.material_id WHERE s.id=? AND s.deleted_at IS NULL AND"
                  + " m.status='ACTIVE'",
              source.slideId());
      if (current.isEmpty()) throw new ApiException(409, "SOURCE_UNAVAILABLE", "来源课件已删除，请重新选择页面");
      if (!Objects.equals(current.get(0).get("text_revision"), source.textRevision())
          || source.imageAsset() != null
              && !Objects.equals(current.get(0).get("preview_asset_id"), source.imageAsset()))
        throw new ApiException(409, "SOURCE_CHANGED", "来源页面已更新，请基于当前页重新提问");
    }
  }

  String image(String owner, Source source) throws Exception {
    if (source.imageAsset() == null) return null;
    var asset = materials.file(owner, source.imageAsset());
    if (!asset.mime().equals("image/png") || Files.size(asset.path()) > 8 * 1024 * 1024)
      throw new ApiException(409, "IMAGE_UNAVAILABLE", "本页图片无法发送，请改用文字提问");
    return "data:image/png;base64,"
        + Base64.getEncoder().encodeToString(Files.readAllBytes(asset.path()));
  }

  String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot encode chat", e);
    }
  }

  <T> T decode(String value, TypeReference<T> type) {
    try {
      return json.readValue(value, type);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot decode chat", e);
    }
  }

  static String shorten(String value, int length) {
    int end = Math.min(value.length(), length);
    if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
    return value.substring(0, end);
  }

  public record Source(
      String slideId,
      String materialId,
      String materialName,
      int pageNo,
      Long textRevision,
      String text,
      boolean truncated,
      String imageAsset) {}

  public record History(String question, String answer) {}

  public record SourceView(
      String slideId,
      String materialId,
      String materialName,
      int pageNo,
      boolean imageAttached,
      boolean truncated,
      boolean available,
      boolean changed) {}

  public record Session(
      String id,
      String courseId,
      String materialId,
      String title,
      String updatedAt,
      boolean materialAvailable) {}

  public record Conversation(Session session, List<Turn> turns) {}

  public record Turn(
      String id,
      String sessionId,
      int sequence,
      String question,
      String status,
      String jobId,
      ReaderAiClient.Answer answer,
      List<SourceView> sources,
      String errorCode,
      String errorMessage) {}

  public record Send(String question, String slideId, boolean useImage, boolean continuePrevious) {}

  public record Capabilities(
      boolean aiAvailable,
      boolean imageAvailable,
      int remainingToday,
      int maxQuestionLength,
      int maxTurns) {}

  record Row(
      String id,
      Session session,
      int sequence,
      String question,
      List<Source> sources,
      List<History> history,
      String status,
      String job,
      ReaderAiClient.Answer answer,
      String errorCode,
      String errorMessage) {}

  record Ack(String id) {}
}
