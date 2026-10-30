package cn.review.course;

import cn.review.common.ApiException;
import cn.review.common.IdempotencyService;
import cn.review.job.JobService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourseService {
    private final CourseMapper courses;
    private final IdempotencyService idempotency;
    private final JobService jobs;
    public CourseService(CourseMapper courses,IdempotencyService idempotency,JobService jobs) { this.courses=courses; this.idempotency=idempotency; this.jobs=jobs; }
    public Page list(String owner,int page,int size) {
        return list(owner,page,size,"");
    }
    public Page list(String owner,int page,int size,String query) {
        if(page<1 || size<1 || size>100 || page>1000000) throw new ApiException(400,"VALIDATION_FAILED","分页参数不正确");
        String q=ApiException.text(query,"搜索内容",0,100);
        return new Page(courses.list(owner,(page-1)*size,size,q).stream().map(CourseRow::view).toList(),page,size,courses.count(owner,q));
    }
    public CourseRow.CourseView get(String owner,String id) {
        CourseRow row=courses.byOwner(id,owner);
        if(row==null || !"ACTIVE".equals(row.status())) throw ApiException.missing();
        return row.view();
    }
    public CourseRow.CourseView create(String owner,String key,Create input) {
        String name=ApiException.text(input.name(),"课程名称",1,100);
        String remark=ApiException.text(input.remark(),"课程备注",0,1000);
        var result=idempotency.execute(owner,"CREATE_COURSE",owner,key,new Create(name,remark),201,CourseRow.CourseView.class,()->{
            String id=UUID.randomUUID().toString(); courses.insert(id,owner,name,remark.isEmpty()?null:remark); return get(owner,id);
        });
        get(owner,result.id());
        return result;
    }
    @Transactional
    public CourseRow.CourseView patch(String owner,String id,Edit input) {
        CourseRow old=courses.lock(id,owner);
        if(old==null || !"ACTIVE".equals(old.status())) throw ApiException.missing();
        if(input.expectedVersion()==null || input.expectedVersion()!=old.rowVersion()) throw ApiException.conflict();
        String name=input.name()==null?old.name():ApiException.text(input.name(),"课程名称",1,100);
        String remark=input.remark()==null?old.remark():ApiException.text(input.remark(),"课程备注",0,1000);
        if(courses.edit(id,owner,name,remark,input.expectedVersion())!=1) throw ApiException.conflict();
        return get(owner,id);
    }
    public JobService.JobView delete(String owner,String id,long expectedVersion,String key) {
        CourseRow visible=courses.byOwner(id,owner);
        if(visible==null) throw ApiException.missing();
        return idempotency.execute(owner,"DELETE_COURSE",id,key,Map.of("expectedVersion",expectedVersion),202,JobService.JobView.class,()->{
            CourseRow row=courses.lock(id,owner);
            if(row==null || !"ACTIVE".equals(row.status())) throw ApiException.missing();
            if(row.rowVersion()!=expectedVersion) throw ApiException.conflict();
            if(courses.markDeleting(id,owner,expectedVersion)!=1) throw ApiException.conflict();
            return jobs.enqueueCoursePurge(owner,id,row.lifecycleVersion()+1);
        });
    }
    public record Create(String name,String remark) {}
    public record Edit(String name,String remark,Long expectedVersion) {}
    public record Page(List<CourseRow.CourseView> items,int page,int size,long total) {}
}
