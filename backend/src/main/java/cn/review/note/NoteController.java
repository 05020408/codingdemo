package cn.review.note;

import cn.review.auth.StudentPrincipal;
import cn.review.common.Api;
import jakarta.servlet.http.HttpServletRequest;
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

  @PostMapping
  ResponseEntity<Api<NoteService.Created>> create(
      @PathVariable String course,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody NoteService.Create input,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest request) {
    return ResponseEntity.status(201).body(Api.ok(notes.create(user.id(), course, input, key), request));
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
