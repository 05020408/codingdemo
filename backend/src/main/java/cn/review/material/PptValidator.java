package cn.review.material;

import cn.review.common.ApiException;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import org.springframework.stereotype.Component;

@Component
public class PptValidator {
  public Validated validate(Path file) {
    try {
      if (Files.size(file) == 0) throw invalid();
      try (var zip = new ZipFile(file.toFile())) {
        if (zip.size() > 5000
            || zip.getEntry("ppt/presentation.xml") == null
            || zip.getEntry("[Content_Types].xml") == null) throw invalid();
        long total = 0;
        var entries = zip.entries();
        byte[] buffer = new byte[16384];
        while (entries.hasMoreElements()) {
          var entry = entries.nextElement();
          String name = entry.getName();
          if (name.startsWith("/")
              || name.contains("\\")
              || Arrays.asList(name.split("/")).contains("..")
              || name.toLowerCase(Locale.ROOT).contains("vbaproject")) throw invalid();
          if (entry.isDirectory()) continue;
          long count = 0;
          var crc = new CRC32();
          var relationships = name.endsWith(".rels") ? new java.io.ByteArrayOutputStream() : null;
          try (var input = zip.getInputStream(entry)) {
            int length;
            while ((length = input.read(buffer)) != -1) {
              count += length;
              total += length;
              crc.update(buffer, 0, length);
              if (count > 50L * 1024 * 1024
                  || total > 200L * 1024 * 1024
                  || (count > 1024 * 1024 && count > Math.max(1, entry.getCompressedSize()) * 100))
                throw invalid();
              if (relationships != null) {
                if (count > 1024 * 1024) throw invalid();
                relationships.write(buffer, 0, length);
              }
            }
          }
          if (entry.getCrc() != crc.getValue()) throw invalid();
          if (relationships != null) {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var document =
                factory
                    .newDocumentBuilder()
                    .parse(new java.io.ByteArrayInputStream(relationships.toByteArray()));
            var relations = document.getElementsByTagName("Relationship");
            for (int i = 0; i < relations.getLength(); i++) {
              var relation = (org.w3c.dom.Element) relations.item(i);
              if ("External".equalsIgnoreCase(relation.getAttribute("TargetMode"))
                  && !relation.getAttribute("Type").endsWith("/hyperlink"))
                throw new ApiException(
                    422, "EXTERNAL_CONTENT", "课件包含外部链接资源，请先在 PowerPoint 中嵌入资源后重新上传");
            }
          }
        }
      }
      List<PptTextExtractor.SlideText> slides;
      try (var input = Files.newInputStream(file)) {
        slides = new PptTextExtractor().extract(input);
      }
      if (slides.isEmpty()
          || slides.size() > 300
          || slides.stream().mapToLong(s -> s.text().length()).sum() > 2_000_000
          || slides.stream().anyMatch(s -> s.text().length() > 200_000))
        throw new ApiException(422, "DOCUMENT_LIMIT", "课件需为 1–300 页，且提取文字不能超过处理限制");
      var digest = MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(file)) {
        byte[] bytes = new byte[16384];
        int n;
        while ((n = input.read(bytes)) != -1) digest.update(bytes, 0, n);
      }
      return new Validated(HexFormat.of().formatHex(digest.digest()), slides);
    } catch (ApiException error) {
      throw error;
    } catch (Exception error) {
      throw invalid();
    }
  }

  private ApiException invalid() {
    return new ApiException(422, "INVALID_DOCUMENT", "无法读取此 PPTX，请确认文件完整且未加密；压缩内容也须符合处理限制");
  }

  public record Validated(String sha256, List<PptTextExtractor.SlideText> slides) {}
}
