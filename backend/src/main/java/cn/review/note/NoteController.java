package cn.review.note;

import cn.review.auth.StudentPrincipal;
import cn.review.common.Api;
import cn.review.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/courses/{course}/notes")
public class NoteController {
  private final NoteService notes;

  public NoteController(NoteService notes) {
    this.notes = notes;
  }

  @PutMapping("/{material}")
  Api<NoteService.Document> save(
      @PathVariable String course,
      @PathVariable String material,
      @RequestBody NoteService.Save input,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest request) {
    return Api.ok(notes.save(user.id(), course, material, input), request);
  }

  @GetMapping("/{material}")
  Api<NoteService.Document> get(
      @PathVariable String course, @PathVariable String material,
      @AuthenticationPrincipal StudentPrincipal user, HttpServletRequest request) {
    return Api.ok(notes.get(user.id(), course, material), request);
  }

  @GetMapping("/{material}/download")
  ResponseEntity<byte[]> download(
      @PathVariable String course, @PathVariable String material,
      @AuthenticationPrincipal StudentPrincipal user) {
    var note = notes.get(user.id(), course, material);
    if (note.version() == 0) throw ApiException.missing();
    String name = note.materialName().replaceFirst("(?i)\\.pptx$", "")
        .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
            .filename((name.isBlank() ? "课件笔记" : name) + ".md", StandardCharsets.UTF_8)
            .build().toString())
        .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
        .body(note.markdown().getBytes(StandardCharsets.UTF_8));
  }

  @GetMapping
  Api<NoteService.Page> list(
      @PathVariable String course,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest request) {
    return Api.ok(notes.list(user.id(), course, page, size), request);
  }
}
