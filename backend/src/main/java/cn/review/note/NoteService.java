package cn.review.note;

import cn.review.common.ApiException;
import cn.review.common.IdempotencyService;
import cn.review.course.CourseMapper;
import cn.review.course.CourseRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class NoteService {
  private final JdbcTemplate db;
  private final CourseMapper courses;
  private final IdempotencyService idempotency;

  public NoteService(JdbcTemplate db, CourseMapper courses, IdempotencyService idempotency) {
    this.db = db;
    this.courses = courses;
    this.idempotency = idempotency;
  }

  public Created create(String owner, String course, Create input, String key) {
    requireActive(courses.byOwner(course, owner));
    var clean = new Create(
        ApiException.text(input.slideId(), "来源页面", 36, 36),
        ApiException.text(input.title(), "笔记标题", 0, 100),
        ApiException.text(input.body(), "笔记正文", 1, 8000));
    var result = idempotency.execute(owner, "CREATE_NOTE", course, key, clean, 201, Created.class, () -> {
      // Course and material deletion take the course lock before changing the source state.
      requireActive(courses.lock(course, owner));
      var sources = db.query("""
          SELECT s.material_id,m.display_name,s.page_no
          FROM slides s JOIN materials m ON m.id=s.material_id
          WHERE s.id=? AND m.course_id=? AND m.status='ACTIVE'
            AND m.mapping_status='VERIFIED' AND s.deleted_at IS NULL AND s.preview_asset_id IS NOT NULL
          """, (rs, row) -> new Source(
              rs.getString("material_id"), rs.getString("display_name"), rs.getInt("page_no")),
          clean.slideId(), course);
      if (sources.isEmpty()) {
        throw new ApiException(404, "SOURCE_UNAVAILABLE", "来源页面已不可用，请检查课件状态");
      }
      Source source = sources.get(0);
      String id = UUID.randomUUID().toString();
      db.update("""
          INSERT INTO notes(id,owner_id,course_id,title,body,source_material_id,source_slide_id,
                            source_material_name,source_page_no)
          VALUES(?,?,?,?,?,?,?,?,?)
          """, id, owner, course, clean.title(), clean.body(), source.materialId(),
          clean.slideId(), source.materialName(), source.pageNo());
      return new Created(id);
    });
    requireActive(courses.byOwner(course, owner));
    return result;
  }

  public Page list(String owner, String course, int page, int size) {
    requireActive(courses.byOwner(course, owner));
    if (page < 1 || page > 1000000 || size < 1 || size > 100) {
      throw new ApiException(400, "VALIDATION_FAILED", "分页参数不正确");
    }
    long total = db.queryForObject(
        "SELECT COUNT(*) FROM notes WHERE course_id=? AND owner_id=?", Long.class, course, owner);
    var items = db.query("""
        SELECT n.*, CASE
          WHEN m.id IS NULL OR m.status<>'ACTIVE' THEN 'DELETED'
          WHEN m.mapping_status='VERIFIED' AND s.id IS NOT NULL
            AND s.deleted_at IS NULL AND s.preview_asset_id IS NOT NULL THEN 'AVAILABLE'
          ELSE 'UNAVAILABLE' END AS source_status
        FROM notes n
        LEFT JOIN materials m ON m.id=n.source_material_id AND m.course_id=n.course_id
        LEFT JOIN slides s ON s.id=n.source_slide_id AND s.material_id=m.id AND s.page_no=n.source_page_no
        WHERE n.course_id=? AND n.owner_id=?
        ORDER BY n.updated_at DESC,n.id DESC LIMIT ? OFFSET ?
        """, (rs, row) -> new View(
            rs.getString("id"), rs.getString("title"), rs.getString("body"),
            rs.getString("source_material_id"), rs.getString("source_material_name"),
            rs.getInt("source_page_no"), rs.getString("source_status"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
        course, owner, size, (page - 1) * size);
    requireActive(courses.byOwner(course, owner));
    return new Page(items, page, size, total);
  }

  private void requireActive(CourseRow row) {
    if (row == null || !"ACTIVE".equals(row.status())) throw ApiException.missing();
  }

  private record Source(String materialId, String materialName, int pageNo) {}

  public record Create(String slideId, String title, String body) {}
  public record Created(String id) {}
  public record View(String id, String title, String body, String materialId, String materialName,
                     int pageNo, String sourceStatus, Instant createdAt, Instant updatedAt) {}
  public record Page(List<View> items, int page, int size, long total) {}
}
