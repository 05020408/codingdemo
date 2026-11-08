package cn.review.summary;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.review.chat.*;
import cn.review.common.*;
import cn.review.course.CourseService;
import cn.review.material.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "app.jobs.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SummaryIntegrationTest {
  @Autowired JdbcTemplate db;
  @Autowired TransactionTemplate tx;
  @Autowired ObjectMapper json;
  @Autowired IdempotencyService idem;
  @Autowired MaterialService materials;
  @Autowired MaterialPipeline pipeline;
  @Autowired CourseService courses;
  @Autowired MockMvc mvc;
  @Autowired cn.review.job.JobService jobs;
  SummaryService service;
  SummaryWorker worker;
  ReaderAiClient ai;
  String owner, course, material, first, second;
  HttpServer server;
  AtomicInteger calls = new AtomicInteger(), delay = new AtomicInteger();
  AtomicBoolean invalid = new AtomicBoolean();
  List<JsonNode> inputs = new CopyOnWriteArrayList<>();
  List<JsonNode> requests = new CopyOnWriteArrayList<>();
  AtomicInteger completionTokens = new AtomicInteger(80);
  AtomicReference<String> finishReason = new AtomicReference<>("stop");
  CountDownLatch received;

  String id() {
    return UUID.randomUUID().toString();
  }

  @BeforeEach
  void setup() throws Exception {
    owner = id();
    course = id();
    material = id();
    first = id();
    second = id();
    received = new CountDownLatch(1);
    String name = "sum" + owner.replace("-", "").substring(0, 20);
    db.update(
        "INSERT INTO app_users(id,username,username_norm,password_hash,display_name)"
            + " VALUES(?,?,?,'fixture','摘要测试')",
        owner,
        name,
        name);
    db.update("INSERT INTO courses(id,owner_id,name) VALUES(?,?,'合成摘要')", course, owner);
    db.update(
        "INSERT INTO materials(id,course_id,display_name,size_bytes,sha256,slide_count)"
            + " VALUES(?,?,'合成课件.pptx',100,REPEAT('0',64),2)",
        material,
        course);
    db.update(
        "INSERT INTO"
            + " slides(id,material_id,page_no,source_slide_id,is_hidden,text_content,text_revision)"
            + " VALUES(?,?,1,1,false,'敏捷开发重视个体和互动',1),(?,?,2,2,true,'持续反馈有助于改进',1)",
        first,
        material,
        second,
        material);
    db.update(
        "INSERT INTO material_stages(material_id,stage,status,published_revision)"
            + " VALUES(?,'TEXT','SUCCEEDED',1)",
        material);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(
        Executors.newCachedThreadPool(
            r -> {
              Thread t = new Thread(r);
              t.setDaemon(true);
              return t;
            }));
    server.createContext(
        "/chat/completions",
        e -> {
          try {
            calls.incrementAndGet();
            var body = json.readTree(e.getRequestBody().readAllBytes());
            requests.add(body);
            var raw = body.path("messages").path(1).path("content");
            var input =
                json.readTree(raw.isArray() ? raw.path(0).path("text").asText() : raw.asText());
            inputs.add(input);
            received.countDown();
            Thread.sleep(delay.get());
            String source = first;
            if (input.has("pages") && !input.path("pages").isEmpty())
              source = input.path("pages").path(0).path("slideId").asText();
            if (input.has("parts"))
              source =
                  input
                      .path("parts")
                      .path(0)
                      .path("sections")
                      .path(0)
                      .path("slideIds")
                      .path(0)
                      .asText();
            Object result =
                input.has("question")
                    ? Map.of(
                        "explanation",
                        "依据原文的解释",
                        "supplement",
                        "摘要只作整体背景",
                        "limitations",
                        "",
                        "citations",
                        List.of(Map.of("slideId", first, "kind", "TEXT", "quote", "个体和互动")))
                    : Map.of(
                        "title",
                        "敏捷开发核心内容",
                        "overview",
                        "围绕协作与反馈形成完整的开发思路。",
                        "sections",
                        List.of(
                            Map.of(
                                "heading",
                                "协作与反馈的联系",
                                "body",
                                "先建立有效协作，再通过持续反馈逐步改进。",
                                "slideIds",
                                List.of(invalid.get() ? "fabricated" : source))),
                        "reviewChecklist",
                        List.of("理解协作与反馈的联系"),
                        "limitations",
                        "只依据提供文字整理。");
            byte[] bytes =
                json.writeValueAsBytes(
                    Map.of(
                        "id",
                        "synthetic-summary",
                        "choices",
                        List.of(
                            Map.of(
                                "finish_reason",
                                finishReason.get(),
                                "message",
                                Map.of("content", json.writeValueAsString(result)))),
                        "usage",
                        Map.of("prompt_tokens", 100, "completion_tokens", completionTokens.get())));
            e.getResponseHeaders().set("Content-Type", "application/json");
            e.sendResponseHeaders(200, bytes.length);
            e.getResponseBody().write(bytes);
            e.close();
          } catch (Exception ignored) {
            e.close();
          }
        });
    server.start();
    ai = client(50);
    service = new SummaryService(db, tx, json, ai, idem);
    worker = new SummaryWorker(service);
  }

  ReaderAiClient client(int limit) {
    return new ReaderAiClient(
        db,
        tx,
        json,
        true,
        "synthetic-key",
        "http://127.0.0.1:" + server.getAddress().getPort(),
        "deepseek-flash",
        limit,
        true);
  }

  @AfterEach
  void cleanup() {
    if (server != null) server.stop(0);
    if (course != null
        && db.queryForObject(
                "SELECT COUNT(*) FROM courses WHERE id=? AND status='ACTIVE'",
                Integer.class,
                course)
            > 0) courses.delete(owner, course, 1, id());
    for (int i = 0; i < 80 && pipeline.runOne(); i++) {}
  }

  SummaryService.Version generate() {
    var v = service.generate(owner, material, id());
    for (int i = 0; i < 20 && worker.runOne(); i++) {}
    return service.version(owner, v.id());
  }

  @Test
  void shortSummaryIsExplicitIdempotentAndDownloadMatchesSavedUtf8() throws Exception {
    String key = id();
    var queued = service.generate(owner, material, key);
    assertThat(calls.get()).isZero();
    assertThat(service.generate(owner, material, key).id()).isEqualTo(queued.id());
    assertThat(worker.runOne()).isTrue();
    var v = service.version(owner, queued.id());
    assertThat(v.status()).isEqualTo("SUCCEEDED");
    assertThat(calls.get()).isEqualTo(1);
    assertThat(v.markdown()).contains("协作与反馈的联系", "参考原页", "/read?page=1");
    assertThat(service.state(owner, material).current().id()).isEqualTo(v.id());
    mvc.perform(get("/api/summary-versions/" + v.id() + "/download").session(auth(owner)))
        .andExpect(status().isOk())
        .andExpect(content().bytes(v.markdown().getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void longDeckCoversEveryCharacterThenRewritesPartsAsOneDocument() {
    String original = "定义与条件".repeat(2300);
    db.update("UPDATE slides SET text_content=? WHERE id=?", original, first);
    var v = generate();
    assertThat(v.status()).isEqualTo("SUCCEEDED");
    assertThat(calls.get()).isEqualTo(3);
    assertThat(inputs.get(2).path("parts").size()).isEqualTo(2);
    String reconstructed =
        inputs.subList(0, 2).stream()
            .flatMap(
                n -> {
                  var a = new ArrayList<JsonNode>();
                  n.path("pages").forEach(a::add);
                  return a.stream();
                })
            .filter(n -> n.path("slideId").asText().equals(first))
            .map(n -> n.path("text").asText())
            .reduce("", String::concat);
    assertThat(reconstructed).isEqualTo(original);
    assertThat(v.completedSteps()).isEqualTo(3);
    assertThat(v.markdown()).contains("完整的开发思路");
  }

  @Test
  void summaryRequestsOmitTokenLimitAndAcceptCompletionAboveOldPartLimit() {
    completionTokens.set(2200);
    db.update("UPDATE slides SET text_content=? WHERE id=?", "定义与条件".repeat(2300), first);
    var v = generate();
    assertThat(v.status()).isEqualTo("SUCCEEDED");
    assertThat(requests).hasSize(3).allSatisfy(body -> assertThat(body.has("max_tokens")).isFalse());
    assertThat(
            db.queryForList(
                "SELECT output_tokens FROM ai_calls WHERE owner_id=? ORDER BY created_at",
                Long.class,
                owner))
        .containsExactly(2200L, 2200L, 2200L);
  }

  @Test
  void providerTruncationIsExplicitAndRequiresUserRetry() {
    finishReason.set("length");
    completionTokens.set(8192);
    var v = generate();
    assertThat(v.status()).isEqualTo("FAILED");
    assertThat(v.errorCode()).isEqualTo("AI_OUTPUT_TRUNCATED");
    assertThat(v.markdown()).isNull();
    assertThat(service.state(owner, material).current()).isNull();
    assertThat(worker.runOne()).isFalse();
    assertThat(calls.get()).isEqualTo(1);
    assertThat(requests.get(0).has("max_tokens")).isFalse();
    assertThat(
            db.queryForObject(
                "SELECT output_tokens FROM ai_calls WHERE owner_id=?", Long.class, owner))
        .isEqualTo(8192L);
    finishReason.set("stop");
    service.retry(owner, v.id(), id());
    assertThat(worker.runOne()).isTrue();
    assertThat(service.version(owner, v.id()).status()).isEqualTo("SUCCEEDED");
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void badSourceFailsAndExplicitRetryReusesCompletedParts() {
    db.update("UPDATE slides SET text_content=? WHERE id=?", "知识".repeat(6000), first);
    var v = service.generate(owner, material, id());
    worker.runOne();
    invalid.set(true);
    worker.runOne();
    assertThat(service.version(owner, v.id()).status()).isEqualTo("FAILED");
    assertThat(calls.get()).isEqualTo(2);
    assertThat(worker.runOne()).isFalse();
    invalid.set(false);
    String key = id();
    service.retry(owner, v.id(), key);
    service.retry(owner, v.id(), key);
    while (worker.runOne()) {}
    assertThat(calls.get()).isEqualTo(4);
    assertThat(service.version(owner, v.id()).completedSteps()).isEqualTo(3);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM ai_calls WHERE owner_id=? AND status='FAILED'",
                Integer.class,
                owner))
        .isEqualTo(1);
  }

  @Test
  void editingAndAdoptionAreVersionedAndDoNotLoseUpdates() {
    var base = generate();
    var state = service.state(owner, material);
    String key = id();
    var input = new SummaryService.Edit(base.id(), state.version(), "# 人工修订\n\n老师补充的限定条件。");
    var edited = service.edit(owner, material, input, key);
    assertThat(service.edit(owner, material, input, key).id()).isEqualTo(edited.id());
    assertThat(service.version(owner, base.id()).markdown()).isEqualTo(base.markdown());
    assertThatThrownBy(
            () ->
                service.edit(
                    owner,
                    material,
                    new SummaryService.Edit(base.id(), state.version(), "旧版本覆盖"),
                    id()))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("已被更新");
    assertThat(service.state(owner, material).current().id()).isEqualTo(edited.id());
    service.adopt(
        owner,
        material,
        new SummaryService.Adopt(base.id(), service.state(owner, material).version()),
        id());
    assertThat(service.state(owner, material).current().id()).isEqualTo(base.id());
  }

  @Test
  void backgroundCompletionCannotOverwriteConcurrentManualEdit() {
    var base = generate();
    var next = service.generate(owner, material, id());
    var edited =
        service.edit(
            owner,
            material,
            new SummaryService.Edit(base.id(), service.state(owner, material).version(), "# 人工新版本"),
            id());
    worker.runOne();
    assertThat(service.version(owner, next.id()).status()).isEqualTo("SUCCEEDED");
    assertThat(service.state(owner, material).current().id()).isEqualTo(edited.id());
  }

  @Test
  void changedSourceRejectsPublicationAndRequiresNewGeneration() {
    var v = service.generate(owner, material, id());
    db.update("UPDATE slides SET text_content='已修改',text_revision=2 WHERE id=?", first);
    worker.runOne();
    assertThat(calls.get()).isZero();
    assertThat(service.version(owner, v.id()).errorCode()).isEqualTo("SOURCE_CHANGED");
    assertThatThrownBy(() -> service.retry(owner, v.id(), id()))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("已变化");
  }

  @Test
  void quotaIsCheckedForWholePlanAndSharedWithChatAttempts() {
    var limited = new SummaryService(db, tx, json, client(1), idem);
    db.update("UPDATE slides SET text_content=? WHERE id=?", "长文".repeat(6000), first);
    assertThatThrownBy(() -> limited.generate(owner, material, id()))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("额度不足");
    assertThat(calls.get()).isZero();
    db.update("UPDATE slides SET text_content='短文' WHERE id=?", first);
    var v = limited.generate(owner, material, id());
    // Another feature consumes the last allowance after planning, before the worker sends.
    db.update(
        "INSERT INTO"
            + " ai_calls(id,owner_id,course_id,scene,model,prompt_version,request_fingerprint,quota_date,status)"
            + " VALUES(?,?,?,'READER_CHAT','stub','test',REPEAT('0',64),CURRENT_DATE(),'FAILED')",
        id(),
        owner,
        course);
    db.update(
        "UPDATE ai_calls SET quota_date=? WHERE owner_id=?",
        java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")),
        owner);
    new SummaryWorker(limited).runOne();
    assertThat(calls.get()).isZero();
    assertThat(limited.version(owner, v.id()).status()).isEqualTo("FAILED");
  }

  @Test
  void cancellationDiscardsLateResultAndDoesNotAutomaticallyRetry() throws Exception {
    var v = service.generate(owner, material, id());
    delay.set(1200);
    var executor = Executors.newSingleThreadExecutor();
    try {
      var future = executor.submit(worker::runOne);
      assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
      service.cancel(owner, v.id());
      future.get(8, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
    assertThat(service.version(owner, v.id()).status()).isEqualTo("CANCELLED");
    assertThat(service.state(owner, material).current()).isNull();
    assertThat(worker.runOne()).isFalse();
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void expiredLeaseRequiresExplicitRecoveryWithNewJob() {
    var v = service.generate(owner, material, id());
    var claim = worker.claim();
    db.update(
        "UPDATE async_jobs SET lease_until=TIMESTAMPADD(SECOND,-5,CURRENT_TIMESTAMP(3)) WHERE id=?",
        claim.job());
    worker.recover();
    assertThat(service.version(owner, v.id()).status()).isEqualTo("FAILED");
    assertThat(worker.runOne()).isFalse();
    service.retry(owner, v.id(), id());
    worker.runOne();
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void chatOptInFreezesExactVersionAndPlainChatOmitsIt() throws Exception {
    var v = generate();
    var chat = new ChatService(db, tx, json, ai, materials, idem, service);
    var chatWorker = new ChatWorker(chat, ai);
    var session = chat.create(owner, material, id());
    var turn =
        chat.send(
            owner, session.id(), new ChatService.Send("解释", first, false, false, v.id()), id());
    service.edit(
        owner,
        material,
        new SummaryService.Edit(v.id(), service.state(owner, material).version(), "# 新的人工版本"),
        id());
    chatWorker.runOne();
    var payload = inputs.get(inputs.size() - 1);
    assertThat(payload.path("summaryBackground").path("versionId").asText()).isEqualTo(v.id());
    assertThat(payload.path("summaryBackground").path("markdown").asText()).isEqualTo(v.markdown());
    assertThat(chat.get(owner, session.id()).turns().get(0).summary().number())
        .isEqualTo(v.number());
    chat.send(owner, session.id(), new ChatService.Send("再次解释", first, false, false), id());
    chatWorker.runOne();
    assertThat(inputs.get(inputs.size() - 1).path("summaryBackground").isEmpty()).isTrue();
    assertThat(chat.get(owner, session.id()).turns().get(1).summary()).isNull();
  }

  @Test
  void materialDeletionPurgesSummaryBodiesAndChatCopiesButKeepsCompletedAnswer() {
    var v = generate();
    var chat = new ChatService(db, tx, json, ai, materials, idem, service);
    var session = chat.create(owner, material, id());
    var turn =
        chat.send(
            owner, session.id(), new ChatService.Send("解释", first, false, false, v.id()), id());
    new ChatWorker(chat, ai).runOne();
    materials.delete(owner, material, 1, id());
    assertThat(chat.get(owner, session.id()).turns().get(0).answer()).isNotNull();
    assertThat(chat.get(owner, session.id()).turns().get(0).summary().unavailable()).isTrue();
    assertThat(
            db.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(summary_snapshot,'$.markdown')) FROM chat_turns"
                    + " WHERE id=?",
                String.class,
                turn.id()))
        .isEmpty();
    for (int i = 0; i < 40 && pipeline.runOne(); i++) {}
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM material_summary_versions WHERE material_id=?",
                Integer.class,
                material))
        .isZero();
    assertThatThrownBy(() -> service.version(owner, v.id())).isInstanceOf(ApiException.class);
  }

  @Test
  void ownershipCsrfAndDownloadAreCheckedAtHttpBoundary() throws Exception {
    var v = generate();
    mvc.perform(get("/api/materials/" + material + "/summary"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/materials/" + material + "/summary-generations")
                .session(auth(owner))
                .header("Idempotency-Key", id()))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/summary-versions/" + v.id() + "/download").session(auth(id())))
        .andExpect(status().isNotFound());
    assertThatThrownBy(() -> service.context(owner, id(), v.id())).isInstanceOf(ApiException.class);
  }

  @Test
  void emptyTextAndUnreadyStagesDoNotCallAi() {
    db.update("UPDATE slides SET text_content='' WHERE material_id=?", material);
    assertThat(service.state(owner, material).blockedReason()).contains("没有可提取文字");
    assertThatThrownBy(() -> service.generate(owner, material, id()))
        .isInstanceOf(ApiException.class);
    db.update("UPDATE material_stages SET published_revision=NULL WHERE material_id=?", material);
    assertThat(service.state(owner, material).blockedReason()).contains("等待");
    assertThat(calls.get()).isZero();
  }

  @Test
  void failedMergeKeepsOldVersionAndResumesOnlyMerge() {
    var old = generate();
    db.update("UPDATE slides SET text_content=? WHERE id=?", "扩展知识".repeat(3000), first);
    var v = service.generate(owner, material, id());
    worker.runOne();
    worker.runOne();
    invalid.set(true);
    worker.runOne();
    assertThat(service.version(owner, v.id()).status()).isEqualTo("FAILED");
    assertThat(service.state(owner, material).current().id()).isEqualTo(old.id());
    assertThat(service.version(owner, v.id()).completedSteps()).isEqualTo(2);
    int before = calls.get();
    invalid.set(false);
    service.retry(owner, v.id(), id());
    worker.runOne();
    assertThat(calls.get()).isEqualTo(before + 1);
    assertThat(service.version(owner, v.id()).status()).isEqualTo("SUCCEEDED");
  }

  @Test
  void genericJobQueryChecksSummaryOwnershipAndQueuedCancelNeverPays() {
    var v = service.generate(owner, material, id());
    String job =
        db.queryForObject(
            "SELECT last_job_id FROM material_summary_parts WHERE version_id=? AND status='QUEUED'",
            String.class,
            v.id());
    assertThat(jobs.get(owner, job).status()).isEqualTo("QUEUED");
    assertThatThrownBy(() -> jobs.get(id(), job)).isInstanceOf(ApiException.class);
    service.cancel(owner, v.id());
    assertThat(worker.runOne()).isFalse();
    assertThat(calls.get()).isZero();
  }

  @Test
  void deletionDuringRequestPreventsLatePublication() throws Exception {
    var v = service.generate(owner, material, id());
    delay.set(1200);
    var executor = Executors.newSingleThreadExecutor();
    try {
      var future = executor.submit(worker::runOne);
      assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
      materials.delete(owner, material, 1, id());
      future.get(8, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
    for (int i = 0; i < 40 && pipeline.runOne(); i++) {}
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM material_summary_versions WHERE material_id=?",
                Integer.class,
                material))
        .isZero();
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM ai_calls WHERE owner_id=? AND status='UNKNOWN'",
                Integer.class,
                owner))
        .isEqualTo(1);
  }

  org.springframework.mock.web.MockHttpSession auth(String id) {
    var principal =
        new cn.review.auth.StudentPrincipal(
            new cn.review.auth.UserRow(id, "test", "test", "fixture", "测试", "ACTIVE"));
    var context =
        org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            principal, "", principal.getAuthorities()));
    var session = new org.springframework.mock.web.MockHttpSession();
    session.setAttribute("SPRING_SECURITY_CONTEXT", context);
    return session;
  }
}
