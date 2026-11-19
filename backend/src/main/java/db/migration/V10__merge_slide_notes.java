package db.migration;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.regex.Pattern;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V10__merge_slide_notes extends BaseJavaMigration {
  private static final Pattern BACKTICKS = Pattern.compile("\\x60+");

  @Override
  public void migrate(Context context) throws Exception {
    var connection = context.getConnection();
    try (var select = connection.prepareStatement("""
             SELECT n.* FROM notes n JOIN courses c ON c.id=n.course_id AND c.status='ACTIVE'
             LEFT JOIN material_notes d ON d.material_id=n.source_material_id
             WHERE d.material_id IS NULL
             ORDER BY n.source_material_id,n.created_at,n.id
             """);
         var insert = connection.prepareStatement("""
             INSERT INTO material_notes(material_id,course_id,owner_id,material_name,markdown,created_at,updated_at)
             VALUES(?,?,?,?,?,?,?)
             """);
         var rows = select.executeQuery()) {
      Document current = null;
      while (rows.next()) {
        String material = rows.getString("source_material_id");
        if (current == null || !current.material.equals(material)) {
          if (current != null) current.insert(insert);
          current = new Document(material, rows.getString("course_id"), rows.getString("owner_id"),
              rows.getString("source_material_name"), rows.getTimestamp("created_at"));
        }
        current.append(rows.getInt("source_page_no"), rows.getString("title"), rows.getString("body"),
            rows.getTimestamp("updated_at"));
      }
      if (current != null) current.insert(insert);
    }
  }

  private static final class Document {
    private final String material, course, owner, name;
    private final Timestamp created;
    private Timestamp updated;
    private final StringBuilder markdown = new StringBuilder();

    Document(String material, String course, String owner, String name, Timestamp created) {
      this.material = material;
      this.course = course;
      this.owner = owner;
      this.name = name;
      this.created = created;
      this.updated = created;
    }

    void append(int page, String title, String body, Timestamp changed) {
      if (changed.after(updated)) updated = changed;
      if (!markdown.isEmpty()) markdown.append("\n\n");
      markdown.append("## [第 ").append(page).append(" 页](#page-").append(page).append(")");
      if (!title.isBlank()) {
        markdown.append(" · ").append(title.replaceAll("\\s+", " ").replaceAll("\\p{Punct}", "\\\\$0"));
      }
      // The old editor saved plain text. A fence keeps it literal, including Markdown markers.
      int longest = 2;
      var markers = BACKTICKS.matcher(body);
      while (markers.find()) longest = Math.max(longest, markers.end() - markers.start());
      String fence = Character.toString(96).repeat(longest + 1);
      markdown.append("\n\n").append(fence).append("text\n").append(body).append('\n').append(fence);
    }

    void insert(PreparedStatement statement) throws Exception {
      statement.setString(1, material);
      statement.setString(2, course);
      statement.setString(3, owner);
      statement.setString(4, name);
      statement.setString(5, markdown.toString());
      statement.setTimestamp(6, created);
      statement.setTimestamp(7, updated);
      statement.executeUpdate();
    }
  }
}
