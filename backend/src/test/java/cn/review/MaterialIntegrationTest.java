package cn.review;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.review.common.ApiException;
import cn.review.material.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(
    properties = {
      "app.jobs.enabled=false",
      "app.material.storage-root=${java.io.tmpdir}/zhijian-material-integration"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MaterialIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired MaterialPipeline pipeline;
  @Autowired MaterialStorage storage;
  @MockitoBean PreviewConverter converter;

  record Client(MockHttpSession session, String header, String token) {}

  String key() {
    return UUID.randomUUID().toString();
  }

  JsonNode data(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsByteArray()).path("data");
  }

  MockHttpServletRequestBuilder as(Client c, MockHttpServletRequestBuilder req) {
    return req.session(c.session()).header(c.header(), c.token());
  }

  MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder req, Object body)
      throws Exception {
    return req.contentType("application/json").content(json.writeValueAsBytes(body));
  }

  Client login() throws Exception {
    var res = mvc.perform(get("/api/auth/csrf")).andReturn();
    var csrf = data(res);
    var c =
        new Client(
            (MockHttpSession) res.getRequest().getSession(),
            csrf.path("headerName").asText(),
            csrf.path("token").asText());
    String name = "m" + key().replace("-", "").substring(0, 20), pw = "MaterialTests_2026";
    mvc.perform(
            as(
                c,
                body(
                    post("/api/auth/register"),
                    Map.of("username", name, "password", pw, "displayName", "课件测试"))))
        .andExpect(status().isCreated());
    mvc.perform(as(c, body(post("/api/auth/login"), Map.of("username", name, "password", pw))))
        .andExpect(status().isOk());
    csrf = data(mvc.perform(get("/api/auth/csrf").session(c.session())).andReturn());
    return new Client(c.session(), csrf.path("headerName").asText(), csrf.path("token").asText());
  }

  String course(Client c) throws Exception {
    return data(mvc.perform(
                as(c, body(post("/api/courses"), Map.of("name", "课件测试课程")))
                    .header("Idempotency-Key", key()))
            .andExpect(status().isCreated())
            .andReturn())
        .path("id")
        .asText();
  }

  JsonNode upload(Client c, String course, byte[] file, String key) throws Exception {
    return data(
        mvc.perform(
                multipart("/api/courses/" + course + "/materials")
                    .file(
                        new MockMultipartFile("file", "样例.pptx", "application/octet-stream", file))
                    .session(c.session())
                    .header(c.header(), c.token())
                    .header("Idempotency-Key", key))
            .andExpect(status().isAccepted())
            .andReturn());
  }

  JsonNode meta(Client c, String id) throws Exception {
    return data(
        mvc.perform(get("/api/materials/" + id).session(c.session()))
            .andExpect(status().isOk())
            .andReturn());
  }

  JsonNode slides(Client c, String id) throws Exception {
    return data(mvc.perform(get("/api/materials/" + id + "/slides").session(c.session()))
            .andExpect(status().isOk())
            .andReturn())
        .path("items");
  }

  void drain() {
    for (int i = 0; i < 100 && pipeline.runOne(); i++) {}
  }

  void previewSuccess() throws Exception {
    doAnswer(
            inv -> {
              Path work = inv.getArgument(1);
              List<PptTextExtractor.SlideText> slides = inv.getArgument(2);
              java.util.function.IntConsumer progress = inv.getArgument(3);
              artifacts(work, slides);
              for (var s : slides) progress.accept(s.pageNumber());
              return null;
            })
        .when(converter)
        .convert(any(), any(), anyList(), any());
  }

  void artifacts(Path work, List<PptTextExtractor.SlideText> slides) throws Exception {
    Files.createDirectories(work);
    Files.writeString(
        work.resolve("preview.pdf"), "fixture-only PDF; real conversion has a separate test");
    for (var s : slides) {
      Files.write(work.resolve("page-" + s.pageNumber() + ".png"), new byte[] {1});
      Files.write(work.resolve("thumb-" + s.pageNumber() + ".png"), new byte[] {1});
    }
  }

  JsonNode retry(Client c, String id, long version, String stage, String key) throws Exception {
    return data(
        mvc.perform(
                as(
                        c,
                        body(
                            post("/api/materials/" + id + "/processing-retries"),
                            Map.of("stage", stage, "expectedVersion", version)))
                    .header("Idempotency-Key", key))
            .andExpect(status().isAccepted())
            .andReturn());
  }

  @Test
  void uploadReadProgressPrivacyAndPurge() throws Exception {
    previewSuccess();
    Client c = login(), other = login();
    String course = course(c), uploadKey = key();
    byte[] file = MaterialFixtures.deck(true);
    var result = upload(c, course, file, uploadKey);
    String id = result.path("materialId").asText();
    assertThat(upload(c, course, file, uploadKey).path("materialId").asText()).isEqualTo(id);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM materials WHERE course_id=?", Integer.class, course))
        .isEqualTo(1);
    assertThat(meta(c, id).at("/capabilities/canReadPreview").asBoolean()).isFalse();
    var stable = slides(c, id);
    drain();
    var ready = meta(c, id);
    assertThat(ready.at("/stages/TEXT/status").asText()).isEqualTo("SUCCEEDED");
    assertThat(ready.at("/stages/PREVIEW/status").asText()).isEqualTo("SUCCEEDED");
    var pages = slides(c, id);
    assertThat(pages.size()).isEqualTo(4);
    assertThat(pages.get(1).path("isHidden").asBoolean()).isTrue();
    assertThat(pages.get(2).path("text").asText()).isBlank();
    assertThat(pages.get(0).path("id")).isEqualTo(stable.get(0).path("id"));
    for (String url :
        List.of(
            "/api/materials/" + id,
            "/api/materials/" + id + "/slides",
            "/api/slides/" + pages.get(0).path("id").asText(),
            ready.path("originalDownloadUrl").asText(),
            pages.get(0).path("previewUrl").asText(),
            "/api/jobs/" + result.at("/jobs/0/jobId").asText()))
      mvc.perform(get(url).session(other.session())).andExpect(status().isNotFound());
    mvc.perform(get(ready.path("originalDownloadUrl").asText()).session(c.session()))
        .andExpect(status().isOk())
        .andExpect(content().bytes(file));
    mvc.perform(
            as(c, body(put("/api/materials/" + id + "/reading-progress"), Map.of("lastPageNo", 4))))
        .andExpect(status().isOk());
    mvc.perform(get("/api/materials/" + id + "/reading-progress").session(c.session()))
        .andExpect(jsonPath("$.data.lastPageNo").value(4));
    mvc.perform(
            as(c, body(put("/api/materials/" + id + "/reading-progress"), Map.of("lastPageNo", 5))))
        .andExpect(status().isBadRequest());
    String deleteKey = key();
    var deletion =
        data(
            mvc.perform(
                    as(
                        c,
                        delete("/api/materials/" + id)
                            .param("expectedVersion", "1")
                            .header("Idempotency-Key", deleteKey)))
                .andExpect(status().isAccepted())
                .andReturn());
    mvc.perform(get(ready.path("originalDownloadUrl").asText()).session(c.session()))
        .andExpect(status().isNotFound());
    drain();
    assertThat(storage.resolve(storage.materialKey(course, id))).doesNotExist();
    assertThat(
            db.queryForObject("SELECT COUNT(*) FROM slides WHERE material_id=?", Integer.class, id))
        .isZero();
    mvc.perform(get("/api/jobs/" + deletion.path("id").asText()).session(c.session()))
        .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
    mvc.perform(
            as(
                c,
                delete("/api/materials/" + id)
                    .param("expectedVersion", "1")
                    .header("Idempotency-Key", deleteKey)))
        .andExpect(status().isAccepted());
    mvc.perform(
            multipart("/api/courses/" + course + "/materials")
                .file(new MockMultipartFile("file", "样例.pptx", "application/octet-stream", file))
                .session(c.session())
                .header(c.header(), c.token())
                .header("Idempotency-Key", uploadKey))
        .andExpect(status().isNotFound());
  }

  @Test
  void retryKeepsStablePagesAndLastGoodPreview() throws Exception {
    previewSuccess();
    Client c = login();
    String course = course(c),
        id = upload(c, course, MaterialFixtures.deck(true), key()).path("materialId").asText();
    drain();
    var first = meta(c, id);
    String originalPdf = first.path("pdfUrl").asText(),
        slideId = slides(c, id).get(0).path("id").asText();
    doThrow(new ApiException(422, "PAGE_MAPPING_FAILED", "页码验证失败"))
        .when(converter)
        .convert(any(), any(), anyList(), any());
    String key = key();
    var job = retry(c, id, 1, "PREVIEW", key);
    assertThat(retry(c, id, 1, "PREVIEW", key).path("id")).isEqualTo(job.path("id"));
    drain();
    var failed = meta(c, id);
    assertThat(failed.at("/stages/PREVIEW/status").asText()).isEqualTo("FAILED");
    assertThat(failed.at("/capabilities/canReadPreview").asBoolean()).isTrue();
    assertThat(failed.path("pdfUrl").asText()).isEqualTo(originalPdf);
    mvc.perform(
            as(
                    c,
                    body(
                        post("/api/materials/" + id + "/processing-retries"),
                        Map.of("stage", "PREVIEW", "expectedVersion", 1)))
                .header("Idempotency-Key", key()))
        .andExpect(status().isConflict());
    previewSuccess();
    retry(c, id, 2, "PREVIEW", key());
    drain();
    assertThat(meta(c, id).at("/stages/PREVIEW/publishedRevision").asLong()).isEqualTo(3);
    assertThat(slides(c, id).get(0).path("id").asText()).isEqualTo(slideId);
    mvc.perform(get(originalPdf).session(c.session())).andExpect(status().isNotFound());
  }

  @Test
  void validationRejectsBadFilesAndMissingOwnership() throws Exception {
    Client c = login(), other = login();
    String course = course(c);
    mvc.perform(
            multipart("/api/courses/" + course + "/materials")
                .file(
                    new MockMultipartFile(
                        "file", "fake.pptx", "application/octet-stream", new byte[] {1, 2, 3}))
                .session(c.session())
                .header(c.header(), c.token())
                .header("Idempotency-Key", key()))
        .andExpect(status().isUnprocessableEntity());
    mvc.perform(
            multipart("/api/courses/" + course + "/materials")
                .file(
                    new MockMultipartFile(
                        "file", "old.ppt", "application/octet-stream", new byte[] {1}))
                .session(c.session())
                .header(c.header(), c.token())
                .header("Idempotency-Key", key()))
        .andExpect(status().isUnsupportedMediaType());
    mvc.perform(
            multipart("/api/courses/" + course + "/materials")
                .file(
                    new MockMultipartFile(
                        "file",
                        "huge.pptx",
                        "application/octet-stream",
                        new byte[20 * 1024 * 1024 + 1]))
                .session(c.session())
                .header(c.header(), c.token())
                .header("Idempotency-Key", key()))
        .andExpect(status().isPayloadTooLarge());
    mvc.perform(
            multipart("/api/courses/" + course + "/materials")
                .file(
                    new MockMultipartFile(
                        "file",
                        "sample.pptx",
                        "application/octet-stream",
                        MaterialFixtures.deck(true)))
                .session(other.session())
                .header(other.header(), other.token())
                .header("Idempotency-Key", key()))
        .andExpect(status().isNotFound());
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM materials WHERE course_id=?", Integer.class, course))
        .isZero();
  }

  @Test
  void blankTextDoesNotBlockPreviewAndInterruptedWorkRecovers() throws Exception {
    previewSuccess();
    Client c = login();
    String id =
        upload(c, course(c), MaterialFixtures.deck(false), key()).path("materialId").asText();
    String
        job =
            db.queryForObject(
                "SELECT id FROM async_jobs WHERE target_id=? AND type='TEXT_EXTRACT'",
                String.class,
                id),
        token = key();
    db.update(
        "UPDATE async_jobs SET"
            + " status='RUNNING',attempt_count=1,lease_token=?,lease_until=TIMESTAMPADD(SECOND,-10,CURRENT_TIMESTAMP(3))"
            + " WHERE id=?",
        token,
        job);
    db.update(
        "INSERT INTO job_attempts(id,job_id,attempt_no,lease_token,started_at,outcome)"
            + " VALUES(?,?,1,?,CURRENT_TIMESTAMP(3),'RUNNING')",
        key(),
        job,
        token);
    pipeline.recover();
    assertThat(
            db.queryForObject("SELECT outcome FROM job_attempts WHERE job_id=?", String.class, job))
        .isEqualTo("INTERRUPTED");
    drain();
    assertThat(meta(c, id).at("/stages/TEXT/error/code").asText()).isEqualTo("NO_EXTRACTABLE_TEXT");
    assertThat(meta(c, id).at("/capabilities/canReadPreview").asBoolean()).isTrue();
  }

  @Test
  void deletionWhileConverterRunsCannotPublishStaleAssets() throws Exception {
    previewSuccess();
    Client c = login();
    String course = course(c),
        id = upload(c, course, MaterialFixtures.deck(true), key()).path("materialId").asText();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            inv -> {
              entered.countDown();
              if (!release.await(20, TimeUnit.SECONDS))
                throw new IllegalStateException("test timeout");
              artifacts(inv.getArgument(1), inv.getArgument(2));
              return null;
            })
        .when(converter)
        .convert(any(), any(), anyList(), any());
    var executor = Executors.newSingleThreadExecutor();
    var worker =
        executor.submit(
            () -> {
              for (int i = 0; i < 50 && entered.getCount() > 0; i++) pipeline.runOne();
            });
    try {
      assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
      mvc.perform(
              as(
                  c,
                  delete("/api/courses/" + course)
                      .param("expectedVersion", "1")
                      .header("Idempotency-Key", key())))
          .andExpect(status().isAccepted());
      mvc.perform(get("/api/materials/" + id).session(c.session()))
          .andExpect(status().isNotFound());
      release.countDown();
      worker.get(15, TimeUnit.SECONDS);
      drain();
      assertThat(storage.resolve(storage.courseKey(course))).doesNotExist();
      assertThat(
              db.queryForObject(
                  "SELECT COUNT(*) FROM file_assets WHERE material_id=? AND status='READY'",
                  Integer.class,
                  id))
          .isZero();
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void failedCleanupCanBeRetriedWithoutResettingAttemptHistory() throws Exception {
    Client c = login(), other = login();
    String course = course(c);
    var result =
        data(
            mvc.perform(
                    as(
                        c,
                        delete("/api/courses/" + course)
                            .param("expectedVersion", "1")
                            .header("Idempotency-Key", key())))
                .andExpect(status().isAccepted())
                .andReturn());
    String id = result.path("id").asText();
    db.update(
        "UPDATE async_jobs SET"
            + " status='FAILED',attempt_count=3,error_code='STORAGE_UNAVAILABLE',error_message='暂时不可用'"
            + " WHERE id=?",
        id);
    mvc.perform(get("/api/cleanup-jobs").session(c.session()))
        .andExpect(jsonPath("$.data[0].id").value(id));
    mvc.perform(as(other, post("/api/jobs/" + id + "/retries").header("Idempotency-Key", key())))
        .andExpect(status().isNotFound());
    String retryKey = key();
    for (int i = 0; i < 2; i++)
      mvc.perform(as(c, post("/api/jobs/" + id + "/retries").header("Idempotency-Key", retryKey)))
          .andExpect(status().isAccepted());
    assertThat(
            db.queryForObject("SELECT max_attempts FROM async_jobs WHERE id=?", Integer.class, id))
        .isEqualTo(6);
    drain();
    mvc.perform(get("/api/jobs/" + id).session(c.session()))
        .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.data.attemptCount").value(4));
  }

  @Test
  void expiredWorkerCannotPublishAfterRecovery() throws Exception {
    previewSuccess();
    Client c = login();
    String id =
        upload(c, course(c), MaterialFixtures.deck(true), key()).path("materialId").asText();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            inv -> {
              entered.countDown();
              if (!release.await(20, TimeUnit.SECONDS))
                throw new IllegalStateException("test timeout");
              artifacts(inv.getArgument(1), inv.getArgument(2));
              return null;
            })
        .when(converter)
        .convert(any(), any(), anyList(), any());
    var executor = Executors.newSingleThreadExecutor();
    var worker =
        executor.submit(
            () -> {
              for (int i = 0; i < 50 && entered.getCount() > 0; i++) pipeline.runOne();
            });
    try {
      assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
      String job =
          db.queryForObject(
              "SELECT id FROM async_jobs WHERE target_id=? AND type='PREVIEW_RENDER'",
              String.class,
              id);
      db.update(
          "UPDATE async_jobs SET lease_until=TIMESTAMPADD(SECOND,-10,CURRENT_TIMESTAMP(3)) WHERE"
              + " id=?",
          job);
      pipeline.recover();
      release.countDown();
      worker.get(15, TimeUnit.SECONDS);
      assertThat(meta(c, id).at("/capabilities/canReadPreview").asBoolean()).isFalse();
      assertThat(db.queryForObject("SELECT status FROM async_jobs WHERE id=?", String.class, job))
          .isEqualTo("QUEUED");
      previewSuccess();
      drain();
      assertThat(meta(c, id).at("/capabilities/canReadPreview").asBoolean()).isTrue();
      assertThat(
              db.queryForObject(
                  "SELECT attempt_count FROM async_jobs WHERE id=?", Integer.class, job))
          .isEqualTo(2);
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }
}
