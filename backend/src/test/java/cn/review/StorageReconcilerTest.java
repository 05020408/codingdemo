package cn.review;

import static org.assertj.core.api.Assertions.*;

import cn.review.material.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class StorageReconcilerTest {
  @TempDir Path directory;

  @Test
  void gracePeriodReferencesAndLiveLeaseProtectFiles() throws Exception {
    var storage = new MaterialStorage(directory.toString());
    var db =
        new JdbcTemplate() {
          @Override
          public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            boolean keep =
                sql.contains("async_jobs") && args[0].equals("live-job")
                    || sql.contains("FROM materials") && args[0].equals("kept")
                    || sql.contains("FROM file_assets") && args[1].toString().contains("/ready/");
            return type.cast(keep ? 1L : 0L);
          }

          @Override
          public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
            return List.of();
          }

          @Override
          public <T> List<T> queryForList(String sql, Class<T> type) {
            return List.of();
          }
        };
    Path oldIncoming = file(storage, "incoming/old.pptx"),
        youngIncoming = file(storage, "incoming/young.pptx"),
        live = file(storage, "work/live-job/token/out"),
        dead = file(storage, "work/dead-job/token/out"),
        orphan = file(storage, "courses/c/materials/orphan/original.pptx"),
        ready = file(storage, "courses/c/materials/kept/previews/ready/page.png"),
        unreferenced = file(storage, "courses/c/materials/kept/previews/orphan/page.png");
    var old = FileTime.from(Instant.now().minus(Duration.ofHours(48)));
    for (Path path :
        List.of(
            oldIncoming,
            live.getParent(),
            dead.getParent(),
            orphan.getParent(),
            ready.getParent(),
            unreferenced.getParent())) Files.setLastModifiedTime(path, old);
    new StorageReconciler(storage, db).reconcile();
    assertThat(oldIncoming).doesNotExist();
    assertThat(youngIncoming).exists();
    assertThat(live).exists();
    assertThat(dead).doesNotExist();
    assertThat(orphan).doesNotExist();
    assertThat(ready).exists();
    assertThat(unreferenced).doesNotExist();
  }

  private Path file(MaterialStorage storage, String key) throws Exception {
    Path p = storage.resolve(key);
    Files.createDirectories(p.getParent());
    Files.writeString(p, "sample");
    return p;
  }
}
