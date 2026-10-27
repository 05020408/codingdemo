package cn.review;

import static org.assertj.core.api.Assertions.*;

import cn.review.common.ApiException;
import cn.review.material.*;
import java.nio.file.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MaterialValidationTest {
  @TempDir Path directory;

  @Test
  void traversalAndExternalRelationshipsAreRejected() throws Exception {
    Path unsafe = directory.resolve("unsafe.pptx");
    try (var out = new ZipOutputStream(Files.newOutputStream(unsafe))) {
      for (String name :
          new String[] {"ppt/presentation.xml", "[Content_Types].xml", "../outside"}) {
        out.putNextEntry(new ZipEntry(name));
        out.write("x".getBytes());
        out.closeEntry();
      }
    }
    assertThatThrownBy(() -> new PptValidator().validate(unsafe)).isInstanceOf(ApiException.class);
    Path external = directory.resolve("external.pptx");
    try (var out = new ZipOutputStream(Files.newOutputStream(external))) {
      for (String name :
          new String[] {
            "ppt/presentation.xml", "[Content_Types].xml", "ppt/_rels/presentation.xml.rels"
          }) {
        out.putNextEntry(new ZipEntry(name));
        out.write(
            (name.endsWith(".rels")
                    ? "<Relationships><Relationship TargetMode=\"External\" Type=\"x/image\""
                          + " Target=\"https://example.invalid/pixel\"/></Relationships>"
                    : "x")
                .getBytes());
        out.closeEntry();
      }
    }
    assertThatThrownBy(() -> new PptValidator().validate(external))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("外部链接资源");
  }

  @Test
  void storageRejectsPathsOutsideItsRoot() throws Exception {
    var storage = new MaterialStorage(directory.resolve("storage").toString());
    assertThatThrownBy(() -> storage.resolve("../outside")).isInstanceOf(java.io.IOException.class);
    assertThatThrownBy(() -> storage.remove("..")).isInstanceOf(java.io.IOException.class);
    assertThat(directory).exists();
  }
}
