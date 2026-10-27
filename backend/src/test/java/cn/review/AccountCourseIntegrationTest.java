package cn.review;

import cn.review.job.JobService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Uses a real dedicated MySQL schema; no in-memory database or containers. */
@SpringBootTest(properties={"app.jobs.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountCourseIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired JobService jobs;
    private static final String PASSWORD="OnlyForTests_2026";
    private String unique() { return "u"+UUID.randomUUID().toString().replace("-","").substring(0,20); }
    private record Client(MockHttpSession session,String header,String token,String username,String userId) {}
    private JsonNode data(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()).get("data"); }
    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder builder,Object value) throws Exception { return builder.contentType("application/json").content(json.writeValueAsBytes(value)); }
    private MockHttpServletRequestBuilder as(Client client,MockHttpServletRequestBuilder builder) { return builder.session(client.session()).header(client.header(),client.token()); }
    private Client anonymous() throws Exception {
        var result=mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var data=data(result);
        return new Client((MockHttpSession)result.getRequest().getSession(),data.get("headerName").asText(),data.get("token").asText(),"","");
    }
    private Client registerAndLogin() throws Exception {
        Client anonymous=anonymous(); String username=unique();
        var registration=mvc.perform(as(anonymous,body(post("/api/auth/register"),Map.of("username",username,"password",PASSWORD,"displayName","测试同学")))).andExpect(status().isCreated()).andReturn();
        String id=data(registration).get("id").asText();
        String previous=anonymous.session().getId();
        mvc.perform(as(anonymous,body(post("/api/auth/login"),Map.of("username",username.toUpperCase(),"password",PASSWORD)))).andExpect(status().isOk());
        assertThat(anonymous.session().getId()).isNotEqualTo(previous);
        var csrf=data(mvc.perform(get("/api/auth/csrf").session(anonymous.session())).andExpect(status().isOk()).andReturn());
        return new Client(anonymous.session(),csrf.get("headerName").asText(),csrf.get("token").asText(),username,id);
    }
    private JsonNode create(Client client,String name,String key) throws Exception {
        return data(mvc.perform(as(client,body(post("/api/courses"),Map.of("name",name,"remark","复习计划"))).header("Idempotency-Key",key)).andExpect(status().isCreated()).andReturn());
    }
    @Test void authenticationAndCsrfBoundaries() throws Exception {
        mvc.perform(get("/api/courses")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(body(post("/api/auth/register"),Map.of("username",unique(),"password",PASSWORD))).andExpect(status().isForbidden());
        Client c=registerAndLogin();
        mvc.perform(get("/api/auth/me").session(c.session())).andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value(c.username())).andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        String hash=db.queryForObject("SELECT password_hash FROM app_users WHERE id=?",String.class,c.userId());
        assertThat(hash).startsWith("{bcrypt}").doesNotContain(PASSWORD);
        mvc.perform(as(c,body(post("/api/auth/register"),Map.of("username",c.username().toUpperCase(),"password",PASSWORD)))).andExpect(status().isConflict());
        Client guest=anonymous();
        mvc.perform(as(guest,body(post("/api/auth/login"),Map.of("username",c.username(),"password","x".repeat(73))))).andExpect(status().isBadRequest());
        mvc.perform(as(guest,body(post("/api/auth/login"),Map.of("username",c.username(),"password","wrong-password")))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/logout").session(c.session())).andExpect(status().isForbidden());
        mvc.perform(as(c,post("/api/auth/logout"))).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }
    @Test void coursesPersistAndArePrivateWithOptimisticUpdates() throws Exception {
        Client owner=registerAndLogin(), other=registerAndLogin();
        var course=create(owner,"软件工程",UUID.randomUUID().toString()); String id=course.get("id").asText();
        mvc.perform(get("/api/courses").session(owner.session())).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(id));
        mvc.perform(get("/api/courses").session(other.session())).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/courses/"+id).session(other.session())).andExpect(status().isNotFound());
        mvc.perform(as(other,body(patch("/api/courses/"+id),Map.of("name","盗改","expectedVersion",1)))).andExpect(status().isNotFound());
        mvc.perform(as(other,delete("/api/courses/"+id).param("expectedVersion","1").header("Idempotency-Key",unique()))).andExpect(status().isNotFound());
        mvc.perform(as(owner,body(patch("/api/courses/"+id),Map.of("name","软件工程复习","remark","","expectedVersion",1)))).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2)).andExpect(jsonPath("$.data.remark").value(""));
        mvc.perform(as(owner,body(patch("/api/courses/"+id),Map.of("name","旧窗口修改","expectedVersion",1)))).andExpect(status().isConflict());
        // A separate login session reads the same database record.
        Client fresh=anonymous();
        mvc.perform(as(fresh,body(post("/api/auth/login"),Map.of("username",owner.username(),"password",PASSWORD)))).andExpect(status().isOk());
        mvc.perform(get("/api/courses/"+id).session(fresh.session())).andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("软件工程复习"));
        assertThat(db.queryForObject("SELECT name FROM courses WHERE id=?",String.class,id)).isEqualTo("软件工程复习");
    }
    @Test void duplicateCreationAndDeletionAreIdempotentAndScrubPersonalText() throws Exception {
        Client c=registerAndLogin(); String key=unique();
        var course=create(c,"待删除课程",key); String id=course.get("id").asText();
        assertThat(create(c,"待删除课程",key).get("id").asText()).isEqualTo(id);
        mvc.perform(as(c,body(post("/api/courses"),Map.of("name","不同内容","remark","复习计划"))).header("Idempotency-Key",key)).andExpect(status().isConflict());
        String deleteKey=unique();
        var deletion=data(mvc.perform(as(c,delete("/api/courses/"+id).param("expectedVersion","1").header("Idempotency-Key",deleteKey))).andExpect(status().isAccepted()).andReturn());
        String job=deletion.get("id").asText();
        mvc.perform(get("/api/courses/"+id).session(c.session())).andExpect(status().isNotFound());
        mvc.perform(get("/api/courses").session(c.session())).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(as(c,delete("/api/courses/"+id).param("expectedVersion","1").header("Idempotency-Key",deleteKey))).andExpect(status().isAccepted()).andExpect(jsonPath("$.data.id").value(job));
        assertThat(jobs.processNext()).isTrue();
        mvc.perform(get("/api/jobs/"+job).session(c.session())).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
        Client other=registerAndLogin();
        mvc.perform(get("/api/jobs/"+job).session(other.session())).andExpect(status().isNotFound());
        assertThat(db.queryForObject("SELECT name FROM courses WHERE id=?",String.class,id)).isEqualTo("[已删除]");
        String saved=db.queryForObject("SELECT response_body FROM idempotency_requests WHERE owner_id=? AND operation='CREATE_COURSE' AND request_key=?",String.class,c.userId(),key);
        assertThat(saved).doesNotContain("待删除课程","复习计划");
        // A delayed create retry must not recreate a course after cleanup.
        mvc.perform(as(c,body(post("/api/courses"),Map.of("name","待删除课程","remark","复习计划"))).header("Idempotency-Key",key)).andExpect(status().isNotFound());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM courses WHERE owner_id=?",Integer.class,c.userId())).isEqualTo(1);
    }
    @Test void invalidFieldsHaveStableClientErrors() throws Exception {
        Client c=registerAndLogin();
        mvc.perform(as(c,body(post("/api/courses"),Map.of("name","   "))).header("Idempotency-Key",unique())).andExpect(status().isBadRequest());
        mvc.perform(as(c,body(post("/api/courses"),Map.of("name","a".repeat(101)))).header("Idempotency-Key",unique())).andExpect(status().isBadRequest());
        mvc.perform(as(c,body(post("/api/courses"),Map.of("name","正常课程")))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/courses").param("page","0").session(c.session())).andExpect(status().isBadRequest());
        mvc.perform(as(c,delete("/api/courses/"+UUID.randomUUID()).header("Idempotency-Key",unique()))).andExpect(status().isBadRequest());
    }
    @Test void concurrentDuplicateSubmissionCreatesOneRecord() throws Exception {
        Client c=registerAndLogin(); String key=unique();
        var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<String> create=()->create(c,"并发创建课程",key).get("id").asText();
            var results=pool.invokeAll(java.util.List.of(create,create));
            assertThat(results.get(0).get()).isEqualTo(results.get(1).get());
            assertThat(db.queryForObject("SELECT COUNT(*) FROM courses WHERE owner_id=?",Integer.class,c.userId())).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
}
