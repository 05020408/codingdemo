package cn.review.note;

import cn.review.common.ApiException;
import cn.review.course.CourseMapper;
import cn.review.course.CourseRow;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NoteService {
  private static final int MAX_LENGTH = 250000;
  private static final String SOURCE_STATUS = """
      CASE WHEN m.id IS NULL OR m.status<>'ACTIVE' THEN 'DELETED'
           WHEN m.mapping_status='VERIFIED' AND m.pdf_asset_id IS NOT NULL THEN 'AVAILABLE'
           ELSE 'UNAVAILABLE' END AS source_status
      """;
  private final JdbcTemplate db;
  private final CourseMapper courses;

  public NoteService(JdbcTemplate db, CourseMapper courses) {
    this.db = db;
    this.courses = courses;
  }

  public Document get(String owner, String course, String material) {
    requireActive(courses.byOwner(course, owner));
    return document(owner, course, material);
  }

  @Transactional
  public Document save(String owner, String course, String material, Save input) {
    // Use the same lock as course and material deletion before reading or changing the document.
    requireActive(courses.lock(course, owner));
    Document current = document(owner, course, material);
    if ("DELETED".equals(current.sourceStatus())) {
      throw new ApiException(409, "SOURCE_UNAVAILABLE", "课件已删除，已保存的笔记仍可查看和下载");
    }
    if (input.expectedVersion() == null || input.expectedVersion() < 0 || input.markdown() == null
        || input.markdown().length() > Math.max(MAX_LENGTH, current.markdown().length())) {
      throw new ApiException(400, "VALIDATION_FAILED", "笔记内容过长或版本参数不正确");
    }
    // If the previous response was lost, retrying the same content gets the saved version.
    if (current.markdown().equals(input.markdown())) return current;
    if (current.version() != input.expectedVersion()) throw ApiException.conflict();
    if (current.version() == 0) {
      db.update("""
          INSERT INTO material_notes(material_id,course_id,owner_id,material_name,markdown)
          VALUES(?,?,?,?,?)
          """, material, course, owner, current.materialName(), input.markdown());
    } else {
      db.update("""
          UPDATE material_notes SET markdown=?,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP(3)
          WHERE material_id=? AND course_id=? AND owner_id=?
          """, input.markdown(), material, course, owner);
    }
    return document(owner, course, material);
  }

  public Page list(String owner, String course, int page, int size) {
    requireActive(courses.byOwner(course, owner));
    if (page < 1 || page > 1000000 || size < 1 || size > 100) {
      throw new ApiException(400, "VALIDATION_FAILED", "分页参数不正确");
    }
    Long total = db.queryForObject(
        "SELECT COUNT(*) FROM material_notes WHERE course_id=? AND owner_id=?", Long.class, course, owner);
    var items = db.query("""
        SELECT n.material_id,n.material_name,n.updated_at, %s
        FROM material_notes n
        LEFT JOIN materials m ON m.id=n.material_id AND m.course_id=n.course_id
        WHERE n.course_id=? AND n.owner_id=?
        ORDER BY n.updated_at DESC,n.material_id DESC LIMIT ? OFFSET ?
        """.formatted(SOURCE_STATUS), (rs, row) -> new Entry(
            rs.getString("material_id"), rs.getString("material_name"), rs.getString("source_status"),
            rs.getTimestamp("updated_at").toInstant()), course, owner, size, (page - 1) * size);
    requireActive(courses.byOwner(course, owner));
    return new Page(items, page, size, total);
  }

  private Document document(String owner, String course, String material) {
    var rows = db.query("""
        SELECT n.material_id,n.material_name,n.markdown,n.row_version,n.updated_at,
               COALESCE(m.slide_count,0) AS page_count, %s
        FROM material_notes n
        LEFT JOIN materials m ON m.id=n.material_id AND m.course_id=n.course_id
        WHERE n.course_id=? AND n.owner_id=? AND n.material_id=?
        """.formatted(SOURCE_STATUS), (rs, row) -> new Document(
            rs.getString("material_id"), rs.getString("material_name"), rs.getString("markdown"),
            rs.getLong("row_version"), rs.getInt("page_count"), rs.getString("source_status"),
            rs.getTimestamp("updated_at").toInstant()), course, owner, material);
    if (!rows.isEmpty()) return rows.get(0);
    var sources = db.query("""
        SELECT m.id,m.display_name,m.slide_count, %s
        FROM materials m
        WHERE m.id=? AND m.course_id=? AND m.status='ACTIVE'
        """.formatted(SOURCE_STATUS), (rs, row) -> new Document(
            rs.getString("id"), rs.getString("display_name"), "", 0, rs.getInt("slide_count"),
            rs.getString("source_status"), null), material, course);
    if (sources.isEmpty()) throw ApiException.missing();
    return sources.get(0);
  }

  private void requireActive(CourseRow row) {
    if (row == null || !"ACTIVE".equals(row.status())) throw ApiException.missing();
  }

  public record Save(String markdown, Long expectedVersion) {}
  public record Document(String materialId, String materialName, String markdown, long version,
                         int pageCount, String sourceStatus, Instant updatedAt) {}
  public record Entry(String materialId, String materialName, String sourceStatus, Instant updatedAt) {}
  public record Page(List<Entry> items, int page, int size, long total) {}
}
