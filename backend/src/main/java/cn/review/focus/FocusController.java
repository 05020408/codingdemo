package cn.review.focus;

import cn.review.auth.StudentPrincipal;
import cn.review.common.Api;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class FocusController {
  private final FocusService service;

  public FocusController(FocusService service) {
    this.service = service;
  }

  @GetMapping("/courses/{course}/focuses")
  Api<?> list(
      @PathVariable String course,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String masteryStatus,
      @RequestParam(required = false) String matchStatus,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest request) {
    return Api.ok(service.list(user.id(), course, page, size, masteryStatus, matchStatus), request);
  }

  @GetMapping("/courses/{course}/focus-capabilities")
  Api<?> capabilities(
      @PathVariable String course,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest request) {
    return Api.ok(service.capabilities(user.id(), course), request);
  }

  @PostMapping("/courses/{course}/focuses")
  ResponseEntity<?> create(
      @PathVariable String course,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody FocusService.Create body,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest request) {
    return ResponseEntity.status(201)
        .body(Api.ok(service.create(user.id(), course, body, key), request));
  }

  @PostMapping("/courses/{course}/focuses/batch")
  ResponseEntity<?> batch(
      @PathVariable String course,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody FocusService.Batch body,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest request) {
    return ResponseEntity.status(201)
        .body(Api.ok(service.batch(user.id(), course, body, key), request));
  }

  @PostMapping("/courses/{course}/focus-split-previews")
  Api<?> split(
      @PathVariable String course,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody FocusService.SplitInput body,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest request) {
    return Api.ok(service.split(user.id(), course, body, key), request);
  }

  @GetMapping("/focuses/{id}")
  Api<?> get(
      @PathVariable String id,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest request) {
    return Api.ok(service.get(user.id(), id), request);
  }

  @PatchMapping("/focuses/{id}")
  Api<?> patch(
      @PathVariable String id,
      @RequestBody FocusService.Edit body,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest request) {
    return Api.ok(service.patch(user.id(), id, body), request);
  }

  @DeleteMapping("/focuses/{id}")
  ResponseEntity<Void> delete(
      @PathVariable String id,
      @RequestParam long expectedVersion,
      @RequestHeader("Idempotency-Key") String key,
      @AuthenticationPrincipal StudentPrincipal user) {
    service.delete(user.id(), id, expectedVersion, key);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/focuses/{id}/locate")
  ResponseEntity<?> locate(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody FocusService.Locate body,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest request) {
    return ResponseEntity.accepted()
        .body(Api.accepted(service.locate(user.id(), id, body, key), request));
  }

  @GetMapping("/focuses/{id}/matches")
  Api<?> matches(
      @PathVariable String id,
      @RequestParam(defaultValue = "false") boolean includeRejected,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest request) {
    return Api.ok(service.matches(user.id(), id, includeRejected), request);
  }

  @PutMapping("/focuses/{id}/matches/{slide}")
  Api<?> decide(
      @PathVariable String id,
      @PathVariable String slide,
      @RequestBody FocusService.Decision body,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest request) {
    return Api.ok(service.decide(user.id(), id, slide, body), request);
  }
}
