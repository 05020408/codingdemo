package cn.review.material;

import cn.review.common.ApiException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PreviewConverter {
  private final String executable;
  private final int timeoutSeconds;

  public PreviewConverter(
      @Value("${app.material.converter:}") String executable,
      @Value("${app.material.convert-timeout-seconds:120}") int timeoutSeconds) {
    this.executable = executable;
    this.timeoutSeconds = timeoutSeconds;
  }

  public void convert(
      Path source, Path work, List<PptTextExtractor.SlideText> slides, IntConsumer progress)
      throws Exception {
    if (executable.isBlank() || !Files.isRegularFile(Path.of(executable)))
      throw new ApiException(503, "CONVERTER_NOT_CONFIGURED", "预览转换服务尚未配置，配置后可重试；原件仍可下载");
    Files.createDirectories(work);
    String filter =
        "pdf:impress_pdf_Export:{\"ExportHiddenSlides\":{\"type\":\"boolean\",\"value\":\"true\"},\"ExportNotesPages\":{\"type\":\"boolean\",\"value\":\"false\"}}";
    if (System.getProperty("os.name").startsWith("Windows")) filter = filter.replace("\"", "\\\"");
    // No shell: each argument is separate, including untrusted input's server-generated path.
    Process process =
        new ProcessBuilder(
                executable,
                "-env:UserInstallation=" + work.resolve("profile").toUri(),
                "--headless",
                "--convert-to",
                filter,
                "--outdir",
                work.toString(),
                source.toString())
            .redirectErrorStream(true)
            .redirectOutput(work.resolve("converter.log").toFile())
            .start();
    try {
      if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS))
        throw new ApiException(504, "CONVERSION_TIMEOUT", "课件转换超时，请稍后重试或拆分较大的课件");
      if (process.exitValue() != 0)
        throw new ApiException(422, "CONVERSION_FAILED", "课件转换失败，请检查课件后重试");
    } finally {
      if (process.isAlive()) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
      }
    }
    Path pdf = work.resolve(source.getFileName().toString().replaceFirst("(?i)\\.pptx$", ".pdf"));
    if (!Files.isRegularFile(pdf) || Files.size(pdf) > 200L * 1024 * 1024)
      throw new ApiException(422, "CONVERSION_FAILED", "未生成可用 PDF，或结果超过处理限制");
    if (!pdf.getFileName().toString().equals("preview.pdf"))
      Files.move(pdf, work.resolve("preview.pdf"));
    verifyAndRender(work, slides, progress);
  }

  public void verifyAndRender(
      Path work, List<PptTextExtractor.SlideText> slides, IntConsumer progress) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(300);
    long total = 0;
    try (var document = Loader.loadPDF(work.resolve("preview.pdf").toFile())) {
      if (document.getNumberOfPages() != slides.size()) throw mapping();
      var stripper = new PDFTextStripper();
      var renderer = new PDFRenderer(document);
      renderer.setSubsamplingAllowed(true);
      for (int i = 0; i < slides.size(); i++) {
        if (System.nanoTime() > deadline)
          throw new ApiException(504, "RENDER_TIMEOUT", "页面渲染超时，请稍后重试");
        stripper.setStartPage(i + 1);
        stripper.setEndPage(i + 1);
        String extracted = stripper.getText(document).replaceAll("\\s+", "");
        String first =
            slides
                .get(i)
                .text()
                .lines()
                .filter(s -> !s.isBlank())
                .findFirst()
                .orElse("")
                .replaceAll("\\s+", "");
        String anchor = first.substring(0, Math.min(12, first.length()));
        if (!anchor.isEmpty() && !extracted.contains(anchor)) throw mapping();
        var box = document.getPage(i).getCropBox();
        double width = box.getWidth(), height = box.getHeight();
        if (!Double.isFinite(width)
            || !Double.isFinite(height)
            || width < 1
            || height < 1
            || width > 20000
            || height > 20000) throw mapping();
        float scale = (float) Math.min(1600 / width, 1200 / height);
        var image = renderer.renderImage(i, scale);
        Path page = work.resolve("page-" + (i + 1) + ".png");
        ImageIO.write(image, "PNG", page.toFile());
        double small = Math.min(240.0 / image.getWidth(), 180.0 / image.getHeight());
        var thumbnail =
            new java.awt.image.BufferedImage(
                Math.max(1, (int) (image.getWidth() * small)),
                Math.max(1, (int) (image.getHeight() * small)),
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = thumbnail.createGraphics();
        graphics.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(image, 0, 0, thumbnail.getWidth(), thumbnail.getHeight(), null);
        graphics.dispose();
        Path thumb = work.resolve("thumb-" + (i + 1) + ".png");
        ImageIO.write(thumbnail, "PNG", thumb.toFile());
        image.flush();
        thumbnail.flush();
        total += Files.size(page) + Files.size(thumb);
        if (total > 200L * 1024 * 1024)
          throw new ApiException(422, "RENDER_LIMIT", "预览结果过大，请拆分课件后重试");
        progress.accept(i + 1);
      }
    }
  }

  private ApiException mapping() {
    return new ApiException(422, "PAGE_MAPPING_FAILED", "预览页数或页面文字与原课件不一致，本次结果未发布，请检查原件后重试");
  }
}
