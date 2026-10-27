package cn.review.material;

import cn.review.auth.StudentPrincipal;
import cn.review.common.Api;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class MaterialController {
  private final MaterialService service;

  public MaterialController(MaterialService service) {
    this.service = service;
  }

  @GetMapping("/courses/{course}/materials")
  Api<MaterialService.MaterialPage> list(
      @AuthenticationPrincipal StudentPrincipal user,
      @PathVariable String course,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      HttpServletRequest request) {
    return Api.ok(service.list(user.id(), course, page, size), request);
  }

  @PostMapping(
      value = "/courses/{course}/materials",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ResponseEntity<Api<MaterialService.UploadResult>> upload(
      @AuthenticationPrincipal StudentPrincipal user,
      @PathVariable String course,
      @RequestHeader("Idempotency-Key") String key,
      @RequestPart("file") MultipartFile file,
      HttpServletRequest request)
      throws IOException {
    return ResponseEntity.accepted()
        .body(Api.accepted(service.upload(user.id(), course, key, file), request));
  }

  @GetMapping("/materials/{id}")
  Api<MaterialService.MaterialView> get(
      @AuthenticationPrincipal StudentPrincipal user,
      @PathVariable String id,
      HttpServletRequest request) {
    return Api.ok(service.get(user.id(), id), request);
  }

  @DeleteMapping("/materials/{id}")
  ResponseEntity<Api<MaterialService.DeleteResult>> delete(
      @AuthenticationPrincipal StudentPrincipal user,
      @PathVariable String id,
      @RequestParam long expectedVersion,
      @RequestHeader("Idempotency-Key") String key,
      HttpServletRequest request) {
    return ResponseEntity.accepted()
        .body(Api.accepted(service.delete(user.id(), id, expectedVersion, key), request));
  }

  @PostMapping("/materials/{id}/processing-retries")
  ResponseEntity<Api<MaterialService.DeleteResult>> retry(
      @AuthenticationPrincipal StudentPrincipal user,
      @PathVariable String id,
      @RequestBody MaterialService.Retry body,
      @RequestHeader("Idempotency-Key") String key,
      HttpServletRequest request) {
    return ResponseEntity.accepted()
        .body(Api.accepted(service.retry(user.id(), id, body, key), request));
  }

  @GetMapping("/materials/{id}/slides")
  Api<MaterialService.SlidePage> slides(
      @AuthenticationPrincipal StudentPrincipal user,
      @PathVariable String id,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      HttpServletRequest request) {
    return Api.ok(service.slides(user.id(), id, page, size), request);
  }

  @GetMapping("/slides/{id}")
  Api<MaterialService.SlideView> slide(
      @AuthenticationPrincipal StudentPrincipal user,
      @PathVariable String id,
      HttpServletRequest request) {
    return Api.ok(service.slide(user.id(), id), request);
  }

  @GetMapping("/materials/{id}/reading-progress")
  Api<MaterialService.Progress> progress(
      @AuthenticationPrincipal StudentPrincipal user,
      @PathVariable String id,
      HttpServletRequest request) {
    return Api.ok(service.progress(user.id(), id), request);
  }

  @PutMapping("/materials/{id}/reading-progress")
  Api<MaterialService.Progress> progress(
      @AuthenticationPrincipal StudentPrincipal user,
      @PathVariable String id,
      @RequestBody ProgressInput body,
      HttpServletRequest request) {
    return Api.ok(service.saveProgress(user.id(), id, body.lastPageNo()), request);
  }

  @GetMapping("/assets/{id}/content")
  ResponseEntity<FileSystemResource> content(
      @AuthenticationPrincipal StudentPrincipal user,
      @PathVariable String id,
      @RequestParam(defaultValue = "inline") String disposition)
      throws IOException {
    var asset = service.file(user.id(), id);
    boolean download = "attachment".equals(disposition) || asset.mime().contains("presentation");
    var header =
        (download ? ContentDisposition.attachment() : ContentDisposition.inline())
            .filename(asset.name(), StandardCharsets.UTF_8)
            .build();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(asset.mime()))
        .header(HttpHeaders.CONTENT_DISPOSITION, header.toString())
        .cacheControl(CacheControl.noStore())
        .body(new FileSystemResource(asset.path()));
  }

  public record ProgressInput(Integer lastPageNo) {}
}
