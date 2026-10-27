package cn.review.search;

import cn.review.common.ApiException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SearchCatalog {
  private final JdbcTemplate db;
  private final PageIndex index;

  public SearchCatalog(JdbcTemplate db, PageIndex index) {
    this.db = db;
    this.index = index;
  }

  public void course(String owner, String course, boolean lock) {
    var list =
        db.queryForList(
            "SELECT id FROM courses WHERE id=? AND owner_id=? AND status='ACTIVE'"
                + (lock ? " FOR UPDATE" : ""),
            course,
            owner);
    if (list.isEmpty()) throw ApiException.missing();
  }

  public List<Scope> scope(String owner, String course) {
    course(owner, course, false);
    var all =
        db.query(
            "SELECT m.id,m.lifecycle_version,i.published_revision,i.input_text_revision FROM"
                + " materials m JOIN material_stages i ON i.material_id=m.id AND i.stage='INDEX'"
                + " JOIN material_stages t ON t.material_id=m.id AND t.stage='TEXT' WHERE"
                + " m.course_id=? AND m.status='ACTIVE' AND i.published_revision IS NOT NULL AND"
                + " i.input_text_revision=t.published_revision ORDER BY m.id",
            (rs, n) -> new Scope(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)),
            course);
    try {
      var ready = new ArrayList<Scope>();
      for (var scope : all) if (index.contains(scope.key())) ready.add(scope);
      return ready;
    } catch (Exception e) {
      throw new ApiException(503, "INDEX_UNAVAILABLE", "检索索引暂不可用，请稍后重试");
    }
  }

  public boolean current(String owner, String course, List<Scope> scopes) {
    return scope(owner, course).equals(scopes);
  }

  public List<Candidate> search(String owner, String course, List<Scope> scopes, String query)
      throws Exception {
    course(owner, course, false);
    List<String> ids = index.search(course, scopes.stream().map(Scope::key).toList(), query);
    List<String> terms = index.terms(query);
    var result = new ArrayList<Candidate>();
    for (String id : ids) {
      var rows =
          db.query(
              "SELECT"
                  + " s.id,s.material_id,m.display_name,s.page_no,s.title,s.text_content,s.text_revision,m.mapping_status,m.pdf_asset_id"
                  + " FROM slides s JOIN materials m ON m.id=s.material_id WHERE s.id=? AND"
                  + " m.course_id=? AND m.status='ACTIVE' AND s.deleted_at IS NULL",
              (rs, n) ->
                  new Candidate(
                      rs.getString(1),
                      rs.getString(2),
                      rs.getString(3),
                      rs.getInt(4),
                      rs.getString(5),
                      snippet(rs.getString(6), terms),
                      rs.getLong(7),
                      "VERIFIED".equals(rs.getString(8)) && rs.getString(9) != null),
              id,
              course);
      if (!rows.isEmpty()) {
        var row = rows.get(0);
        if (scopes.stream()
            .anyMatch(
                s ->
                    s.materialId().equals(row.materialId())
                        && s.textRevision() == row.textRevision())) result.add(row);
      }
    }
    return result;
  }

  static String snippet(String text, List<String> terms) {
    if (text == null) return "";
    int first = Integer.MAX_VALUE;
    String lower = text.toLowerCase(Locale.ROOT);
    for (String term : terms) {
      int found = lower.indexOf(term.toLowerCase(Locale.ROOT));
      if (found >= 0) first = Math.min(first, found);
    }
    int start = first == Integer.MAX_VALUE ? 0 : Math.max(0, first - 100);
    return text.substring(start, Math.min(text.length(), start + 800));
  }

  public record Scope(String materialId, long lifecycle, long indexRevision, long textRevision) {
    public String key() {
      return PageIndex.scope(materialId, indexRevision, textRevision);
    }
  }

  public record Candidate(
      String slideId,
      String materialId,
      String materialName,
      int pageNo,
      String title,
      String evidenceText,
      long textRevision,
      boolean previewAvailable) {}
}
