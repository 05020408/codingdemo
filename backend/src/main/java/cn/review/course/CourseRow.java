package cn.review.course;

import java.time.Instant;

public record CourseRow(String id,String ownerId,String name,String remark,String status,long lifecycleVersion,long rowVersion,Instant createdAt,Instant updatedAt) {
    public CourseView view() { return new CourseView(id,name,remark,status,rowVersion,createdAt,updatedAt); }
    public record CourseView(String id,String name,String remark,String status,long version,Instant createdAt,Instant updatedAt) {}
}
