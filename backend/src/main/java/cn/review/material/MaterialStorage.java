package cn.review.material;

import cn.review.common.ApiException;
import java.io.*;
import java.nio.file.*;
import java.util.Comparator;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MaterialStorage {
  private final Path root;

  public MaterialStorage(@Value("${app.material.storage-root}") String root) throws IOException {
    this.root = Path.of(root).toAbsolutePath().normalize();
    Files.createDirectories(this.root);
    if (Files.isSymbolicLink(this.root)) throw new IOException("Storage root cannot be a link");
  }

  public Path resolve(String key) throws IOException {
    Path path = root.resolve(key).normalize();
    if (!path.startsWith(root) || path.equals(root)) throw new IOException("Invalid storage path");
    for (Path current = path;
        current != null && current.startsWith(root);
        current = current.getParent()) {
      if (Files.isSymbolicLink(current)) throw new IOException("Storage links are not allowed");
    }
    return path;
  }

  public Path incoming(InputStream input) throws IOException {
    Path file = resolve("incoming/" + UUID.randomUUID() + ".pptx");
    Files.createDirectories(file.getParent());
    try (var output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
      byte[] buffer = new byte[16384];
      long count = 0;
      int length;
      while ((length = input.read(buffer)) != -1) {
        count += length;
        if (count > 20L * 1024 * 1024)
          throw new ApiException(413, "FILE_TOO_LARGE", "课件不能超过 20 MiB");
        output.write(buffer, 0, length);
      }
      return file;
    } catch (Exception error) {
      Files.deleteIfExists(file);
      throw error;
    }
  }

  public void move(Path source, String destination) throws IOException {
    Path target = resolve(destination);
    Files.createDirectories(target.getParent());
    Files.move(source, target);
  }

  public String key(Path file) {
    return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
  }

  public void remove(String key) throws IOException {
    Path target = resolve(key);
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
    try (var files = Files.walk(target)) {
      for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
    }
  }

  public String materialKey(String course, String material) {
    return "courses/" + UUID.fromString(course) + "/materials/" + UUID.fromString(material);
  }

  public String courseKey(String course) {
    return "courses/" + UUID.fromString(course);
  }
}
