package cn.review.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.jobs.enabled",havingValue="true",matchIfMissing=true)
public class CoursePurgeWorker {
    private static final Logger LOG=LoggerFactory.getLogger(CoursePurgeWorker.class);
    private final JobService jobs;
    public CoursePurgeWorker(JobService jobs) { this.jobs=jobs; }
    @Scheduled(fixedDelay=1000)
    public void tick() {
        try { jobs.processNext(); }
        catch(Exception error) { LOG.warn("Course cleanup transaction rolled back: {}",error.getClass().getSimpleName()); }
    }
}
