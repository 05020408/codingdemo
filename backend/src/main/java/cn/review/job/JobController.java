package cn.review.job;

import cn.review.auth.StudentPrincipal;
import cn.review.common.Api;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class JobController {
  private final JobService jobs;

  public JobController(JobService jobs) {
    this.jobs = jobs;
  }

  @GetMapping("/api/jobs/{id}")
  Api<JobService.JobView> get(
      @PathVariable String id,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest request) {
    return Api.ok(jobs.get(user.id(), id), request);
  }

  @GetMapping("/api/cleanup-jobs")
  Api<java.util.List<JobService.JobView>> cleanup(
      @AuthenticationPrincipal StudentPrincipal user, HttpServletRequest request) {
    return Api.ok(jobs.cleanup(user.id()), request);
  }

  @PostMapping("/api/jobs/{id}/retries")
  org.springframework.http.ResponseEntity<Api<JobService.JobView>> retry(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @AuthenticationPrincipal StudentPrincipal user,
      HttpServletRequest request) {
    return org.springframework.http.ResponseEntity.accepted()
        .body(Api.accepted(jobs.retryCleanup(user.id(), id, key), request));
  }
}
