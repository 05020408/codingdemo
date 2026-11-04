package cn.review.summary;

import cn.review.auth.StudentPrincipal;
import cn.review.common.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class SummaryController {
  private final SummaryService service;

  public SummaryController(SummaryService service) {
    this.service = service;
  }

  @GetMapping("/materials/{id}/summary")
  Api<?> state(
      @PathVariable String id,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest req) {
    return Api.ok(service.state(user.id(), id), req);
  }

  @PostMapping("/materials/{id}/summary-generations")
  ResponseEntity<?> generate(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest req) {
    return ResponseEntity.accepted().body(Api.accepted(service.generate(user.id(), id, key), req));
  }

  @PostMapping("/summary-generations/{id}/retry")
  ResponseEntity<?> retry(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest req) {
    return ResponseEntity.accepted().body(Api.accepted(service.retry(user.id(), id, key), req));
  }

  @PostMapping("/summary-generations/{id}/cancel")
  Api<?> cancel(
      @PathVariable String id,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest req) {
    return Api.ok(service.cancel(user.id(), id), req);
  }

  @GetMapping("/summary-versions/{id}")
  Api<?> version(
      @PathVariable String id,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest req) {
    return Api.ok(service.version(user.id(), id), req);
  }

  @PostMapping("/materials/{id}/summary-versions")
  ResponseEntity<?> edit(
      @PathVariable String id,
      @RequestBody SummaryService.Edit input,
      @RequestHeader("Idempotency-Key") String key,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest req) {
    return ResponseEntity.status(201).body(Api.ok(service.edit(user.id(), id, input, key), req));
  }

  @PutMapping("/materials/{id}/summary/current")
  Api<?> adopt(
      @PathVariable String id,
      @RequestBody SummaryService.Adopt input,
      @RequestHeader("Idempotency-Key") String key,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest req) {
    return Api.ok(service.adopt(user.id(), id, input, key), req);
  }

  @GetMapping("/summary-versions/{id}/download")
  ResponseEntity<byte[]> download(
      @PathVariable String id, @AuthenticationPrincipal StudentPrincipal user) {
    var v = service.version(user.id(), id);
    if (!"SUCCEEDED".equals(v.status()))
      throw new ApiException(409, "SUMMARY_NOT_READY", "摘要尚未生成成功");
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename("课件核心摘要-v" + v.number() + ".md", StandardCharsets.UTF_8)
                .build()
                .toString())
        .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
        .body(v.markdown().getBytes(StandardCharsets.UTF_8));
  }
}
