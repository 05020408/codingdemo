package cn.review;

import static org.assertj.core.api.Assertions.*;

import cn.review.common.ApiException;
import cn.review.material.*;
import java.nio.file.*;
import java.util.ArrayList;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

class PreviewConverterTest {
  @TempDir Path directory;

  @Test
  @EnabledIfEnvironmentVariable(named = "CONVERTER_PATH", matches = ".+")
  void nativeConverterPreservesHiddenAndBlankPages() throws Exception {
    var source = directory.resolve("sample.pptx");
    Files.write(source, MaterialFixtures.deck(true));
    var slides = new PptTextExtractor().extract(Files.newInputStream(source));
    assertThat(slides.get(1).hidden()).isTrue();
    var progress = new ArrayList<Integer>();
    new PreviewConverter(System.getenv("CONVERTER_PATH"), 120)
        .convert(source, directory.resolve("work"), slides, progress::add);
    assertThat(progress).containsExactly(1, 2, 3, 4);
    for (int i = 1; i <= 4; i++) {
      assertThat(Files.size(directory.resolve("work/page-" + i + ".png"))).isPositive();
      assertThat(Files.size(directory.resolve("work/thumb-" + i + ".png"))).isPositive();
    }
  }

  @Test
  void wrongPageMappingIsRejectedBeforePublishing() throws Exception {
    try (var doc = new PDDocument()) {
      doc.addPage(new PDPage());
      doc.save(directory.resolve("preview.pdf").toFile());
    }
    var slides =
        new PptTextExtractor()
            .extract(new java.io.ByteArrayInputStream(MaterialFixtures.deck(true)));
    assertThatThrownBy(
            () -> new PreviewConverter("", 120).verifyAndRender(directory, slides, i -> {}))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("不一致");
    assertThat(directory.resolve("page-1.png")).doesNotExist();
    Files.delete(directory.resolve("preview.pdf"));
    try (var doc = new PDDocument()) {
      for (int i = 0; i < 4; i++) doc.addPage(new PDPage());
      doc.save(directory.resolve("preview.pdf").toFile());
    }
    assertThatThrownBy(
            () -> new PreviewConverter("", 120).verifyAndRender(directory, slides, i -> {}))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("不一致");
  }
}
