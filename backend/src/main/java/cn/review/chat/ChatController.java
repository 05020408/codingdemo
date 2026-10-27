package cn.review.chat;

import cn.review.auth.StudentPrincipal;
import cn.review.common.Api;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ChatController {
  private final ChatService service;

  public ChatController(ChatService service) {
    this.service = service;
  }

  @GetMapping("/materials/{id}/chat-capabilities")
  Api<?> capabilities(
      @PathVariable String id,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest req) {
    return Api.ok(service.capabilities(user.id(), id), req);
  }

  @GetMapping("/courses/{id}/chat-sessions")
  Api<?> sessions(
      @PathVariable String id,
      @RequestParam(required = false) String materialId,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest req) {
    return Api.ok(service.sessions(user.id(), id, materialId), req);
  }

  @PostMapping("/materials/{id}/chat-sessions")
  ResponseEntity<?> create(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest req) {
    return ResponseEntity.status(201).body(Api.ok(service.create(user.id(), id, key), req));
  }

  @GetMapping("/chat-sessions/{id}")
  Api<?> get(
      @PathVariable String id,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest req) {
    return Api.ok(service.get(user.id(), id), req);
  }

  @PostMapping("/chat-sessions/{id}/turns")
  ResponseEntity<?> send(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody ChatService.Send body,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest req) {
    return ResponseEntity.accepted()
        .body(Api.accepted(service.send(user.id(), id, body, key), req));
  }

  @PostMapping("/chat-turns/{id}/retry")
  ResponseEntity<?> retry(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest req) {
    return ResponseEntity.accepted().body(Api.accepted(service.retry(user.id(), id, key), req));
  }

  @PostMapping("/chat-turns/{id}/cancel")
  Api<?> cancel(
      @PathVariable String id,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest req) {
    return Api.ok(service.cancel(user.id(), id), req);
  }
}
