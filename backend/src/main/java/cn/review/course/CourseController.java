package cn.review.course;

import cn.review.auth.StudentPrincipal;
import cn.review.common.Api;
import cn.review.job.JobService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/courses")
public class CourseController {
    private final CourseService courses;
    public CourseController(CourseService courses) { this.courses=courses; }
    @GetMapping
    Api<CourseService.Page> list(@AuthenticationPrincipal StudentPrincipal user,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(defaultValue="")String q,HttpServletRequest request) { return Api.ok(courses.list(user.id(),page,size,q),request); }
    @PostMapping
    ResponseEntity<Api<CourseRow.CourseView>> create(@AuthenticationPrincipal StudentPrincipal user,@RequestHeader("Idempotency-Key")String key,@RequestBody CourseService.Create body,HttpServletRequest request) { return ResponseEntity.status(201).body(Api.ok(courses.create(user.id(),key,body),request)); }
    @GetMapping("/{id}")
    Api<CourseRow.CourseView> get(@PathVariable String id,@AuthenticationPrincipal StudentPrincipal user,HttpServletRequest request) { return Api.ok(courses.get(user.id(),id),request); }
    @PatchMapping("/{id}")
    Api<CourseRow.CourseView> patch(@PathVariable String id,@AuthenticationPrincipal StudentPrincipal user,@RequestBody CourseService.Edit body,HttpServletRequest request) { return Api.ok(courses.patch(user.id(),id,body),request); }
    @DeleteMapping("/{id}")
    ResponseEntity<Api<JobService.JobView>> delete(@PathVariable String id,@RequestParam long expectedVersion,@RequestHeader("Idempotency-Key")String key,@AuthenticationPrincipal StudentPrincipal user,HttpServletRequest request) { return ResponseEntity.accepted().body(Api.accepted(courses.delete(user.id(),id,expectedVersion,key),request)); }
}
