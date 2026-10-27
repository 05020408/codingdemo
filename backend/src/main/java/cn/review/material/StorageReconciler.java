package cn.review.material;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reclaims crash leftovers only under controlled directories, after a 24 hour grace period. */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class StorageReconciler {
  private final MaterialStorage storage;
  private final JdbcTemplate db;

  public StorageReconciler(MaterialStorage storage, JdbcTemplate db) {
    this.storage = storage;
    this.db = db;
  }

  @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
  public void reconcile() {
    try {
      for (var file : children("incoming")) if (old(file)) storage.remove(storage.key(file));
      for (var job : children("work"))
        for (var attempt : children(storage.key(job))) {
          if (old(attempt)
              && count(
                      "SELECT COUNT(*) FROM async_jobs WHERE id=? AND lease_token=? AND"
                          + " status='RUNNING' AND lease_until>CURRENT_TIMESTAMP(3)",
                      job.getFileName().toString(),
                      attempt.getFileName().toString())
                  == 0) storage.remove(storage.key(attempt));
        }
      for (var course : children("courses"))
        for (var material : children(storage.key(course) + "/materials")) {
          String id = material.getFileName().toString();
          if (old(material)
              && count("SELECT COUNT(*) FROM materials WHERE id=? AND status<>'DELETED'", id)
                  == 0) {
            storage.remove(storage.key(material));
            continue;
          }
          for (var preview : children(storage.key(material) + "/previews"))
            if (old(preview)
                && count(
                        "SELECT COUNT(*) FROM file_assets WHERE material_id=? AND storage_key LIKE"
                            + " ? AND status='READY'",
                        id,
                        storage.key(preview) + "/%")
                    == 0) storage.remove(storage.key(preview));
        }
      for (var key :
          db.queryForList(
              "SELECT storage_key FROM file_assets WHERE status='RETIRED' LIMIT 500",
              String.class)) {
        storage.remove(key);
        db.update(
            "UPDATE file_assets SET status='DELETED' WHERE storage_key=? AND status='RETIRED'",
            key);
      }
    } catch (Exception error) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("Storage reconciliation deferred ({})", error.getClass().getSimpleName());
    }
  }

  private List<Path> children(String key) throws Exception {
    Path path = storage.resolve(key);
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return List.of();
    try (var stream = Files.list(path)) {
      return stream.filter(p -> !Files.isSymbolicLink(p)).toList();
    }
  }

  private boolean old(Path path) throws Exception {
    return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)
        .toInstant()
        .isBefore(Instant.now().minus(Duration.ofHours(24)));
  }

  private long count(String sql, Object... args) {
    return Objects.requireNonNull(db.queryForObject(sql, Long.class, args));
  }
}
