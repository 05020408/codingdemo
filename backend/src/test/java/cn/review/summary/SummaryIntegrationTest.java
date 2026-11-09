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
  AtomicInteger proseRepeats = new AtomicInteger();
  AtomicBoolean truncateFinal = new AtomicBoolean();
  AtomicBoolean failSecondTopic = new AtomicBoolean();
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
                                "先建立有效协作，再通过持续反馈逐步改进。" + "关键条件与过程。".repeat(proseRepeats.get()),
                                "slideIds",
                                List.of(invalid.get() ? "fabricated" : source))),
                        "reviewChecklist",
                        List.of("理解协作与反馈的联系"),
                        "limitations",
                        "只依据提供文字整理。");
            if ("OUTLINE".equals(input.path("stage").asText())) {
              result = new SummaryContent.Document("敏捷开发核心内容", "协作与反馈形成完整思路。",
                  List.of(new SummaryContent.Section("主题1", "阐明协作", List.of(source)),
                      new SummaryContent.Section("主题2", "解释反馈", List.of(source))),
                  List.of("理解协作与反馈"), "仅依据提供文字");
            } else if ("TOPIC".equals(input.path("stage").asText())) {
              result = new SummaryContent.Section(input.path("topic").path("heading").asText(),
                  "依据材料撰写的完整主题正文。", List.of(invalid.get() ? "fabricated" : input.path("topic").path("slideIds").path(0).asText()));
            }
            int reportedTokens = completionTokens.get();
            String completionEnd = finishReason.get();
            if (failSecondTopic.get() && "TOPIC".equals(input.path("stage").asText())
                && "主题2".equals(input.path("topic").path("heading").asText())) completionEnd = "length";
            int requestedLimit = body.path("max_tokens").asInt(8192);
            if (reportedTokens > requestedLimit) {
              reportedTokens = requestedLimit;
              completionEnd = "length";
            }
            if (truncateFinal.get() && input.has("parts")) {
              reportedTokens = 8192;
              completionEnd = "length";
            }
            byte[] bytes =
                json.writeValueAsBytes(
                    Map.of(
                        "id",
                        "synthetic-summary",
                        "choices",
                        List.of(
                            Map.of(
                                "finish_reason",
                                completionEnd,
                                "message",
                                Map.of("content", json.writeValueAsString(result)))),
                        "usage",
                        Map.of("prompt_tokens", 100, "completion_tokens", reportedTokens)));
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
    // Exercise pre-upgrade records with the legacy strategy; dedicated tests below use production defaults.
    service = new SummaryService(db, tx, json, ai, idem, false);
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
  void summaryRequestsUseProviderMaximumAndAcceptCompletionAboveDefaultBudget() {
    completionTokens.set(12000);
    db.update("UPDATE slides SET text_content=? WHERE id=?", "定义与条件".repeat(2300), first);
    var v = generate();
    assertThat(v.status()).isEqualTo("SUCCEEDED");
    assertThat(requests).hasSize(3).allSatisfy(body ->
        assertThat(body.path("max_tokens").asInt()).isEqualTo(393216));
    assertThat(
            db.queryForList(
                "SELECT output_tokens FROM ai_calls WHERE owner_id=? ORDER BY created_at",
                Long.class,
                owner))
        .containsExactly(12000L, 12000L, 12000L);
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
    assertThat(requests.get(0).path("max_tokens").asInt()).isEqualTo(393216);
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
  void longPartAndFinalProseSurvivesGenerationEditingAndDownload() throws Exception {
    proseRepeats.set(1600);
    db.update("UPDATE slides SET text_content=? WHERE id=?", "定义与条件".repeat(4500), first);
    var v = generate();
    assertThat(v.status()).isEqualTo("SUCCEEDED");
    assertThat(v.completedSteps()).isEqualTo(4);
    assertThat(calls.get()).isEqualTo(4);
    assertThat(v.markdown().length()).isGreaterThan(12000);
    assertThat(v.markdown()).contains("关键条件与过程。".repeat(1600));
    assertThat(requests).allSatisfy(body -> {
      assertThat(body.path("max_tokens").asInt()).isEqualTo(393216);
      assertThat(body.path("messages").path(0).path("content").asText())
          .doesNotContain("不超过3000", "不超过9000");
    });
    assertThat(db.queryForList("SELECT DISTINCT prompt_version FROM ai_calls WHERE owner_id=?",
        String.class, owner)).containsExactly("summary-v2");
    String editedText = v.markdown() + "\n人工补充：" + "理解条件😀".repeat(1500);
    var edited = service.edit(owner, material,
        new SummaryService.Edit(v.id(), service.state(owner, material).version(), editedText), id());
    assertThat(edited.markdown()).isEqualTo(editedText);
    assertThat(service.version(owner, v.id()).markdown()).isEqualTo(v.markdown());
    mvc.perform(get("/api/summary-versions/" + edited.id() + "/download").session(auth(owner)))
        .andExpect(status().isOk())
        .andExpect(content().bytes(editedText.getBytes(StandardCharsets.UTF_8)));
    assertThat(service.context(owner, material, edited.id()).markdown()).isEqualTo(editedText);
    assertThat(calls.get()).isEqualTo(4);
  }

  @Test
  void finalTruncationRetryReusesThreeCompletedPartsAndOnlyCallsFinalOnce() {
    db.update("UPDATE slides SET text_content=? WHERE id=?", "定义与条件".repeat(4500), first);
    truncateFinal.set(true);
    var v = generate();
    assertThat(v.status()).isEqualTo("FAILED");
    assertThat(v.errorCode()).isEqualTo("AI_OUTPUT_TRUNCATED");
    assertThat(v.completedSteps()).isEqualTo(3);
    assertThat(v.totalSteps()).isEqualTo(4);
    assertThat(calls.get()).isEqualTo(4);
    var savedParts = db.queryForList(
        "SELECT result FROM material_summary_parts WHERE version_id=? AND kind='PART' ORDER BY position_no",
        String.class, v.id());
    assertThat(worker.runOne()).isFalse();
    truncateFinal.set(false);
    completionTokens.set(12000);
    String key = id();
    service.retry(owner, v.id(), key);
    service.retry(owner, v.id(), key);
    assertThat(calls.get()).isEqualTo(4);
    assertThat(worker.runOne()).isTrue();
    assertThat(worker.runOne()).isFalse();
    assertThat(calls.get()).isEqualTo(5);
    assertThat(inputs.get(4).path("parts")).hasSize(3);
    assertThat(service.version(owner, v.id()).status()).isEqualTo("SUCCEEDED");
    assertThat(db.queryForList(
        "SELECT result FROM material_summary_parts WHERE version_id=? AND kind='PART' ORDER BY position_no",
        String.class, v.id())).isEqualTo(savedParts);
    assertThat(db.queryForObject(
        "SELECT COUNT(*) FROM ai_calls WHERE owner_id=? AND scene='SUMMARY_PART'", Integer.class, owner))
        .isEqualTo(3);
    assertThat(db.queryForObject(
        "SELECT COUNT(*) FROM ai_calls WHERE owner_id=? AND scene='SUMMARY_FINAL'", Integer.class, owner))
        .isEqualTo(2);
  }

  @Test
  void summaryResponseCanExceedOld256KiBReceiverWithoutLosingMarkdown() throws Exception {
    proseRepeats.set(12000);
    completionTokens.set(40000);
    var v = generate();
    assertThat(v.status()).isEqualTo("SUCCEEDED");
    assertThat(v.markdown().getBytes(StandardCharsets.UTF_8).length).isGreaterThan(262144);
    assertThat(v.markdown()).contains("关键条件与过程。".repeat(12000));
    assertThat(calls.get()).isEqualTo(1);
    mvc.perform(get("/api/summary-versions/" + v.id() + "/download").session(auth(owner)))
        .andExpect(status().isOk())
        .andExpect(content().bytes(v.markdown().getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void thematicPipelinePlansThenWritesTopicsAndAssemblesWithoutFinalAiCall() {
    service = new SummaryService(db, tx, json, ai, idem);
    worker = new SummaryWorker(service);
    db.update("UPDATE slides SET text_content=? WHERE id=?", "定义与条件".repeat(4500), first);
    assertThat(service.state(owner, material).estimatedRequests()).isEqualTo(5);
    assertThat(service.state(owner, material).estimatedRequestsLowerBound()).isTrue();
    var v = generate();
    assertThat(v.status()).isEqualTo("SUCCEEDED");
    assertThat(v.totalSteps()).isEqualTo(7);
    assertThat(v.completedSteps()).isEqualTo(7);
    assertThat(calls.get()).isEqualTo(6);
    assertThat(v.remainingRequests()).isZero();
    assertThat(v.markdown()).contains("## 主题1", "## 主题2", "完整主题正文", "复习清单");
    assertThat(v.markdown().indexOf("## 主题1")).isLessThan(v.markdown().indexOf("## 主题2"));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_calls WHERE owner_id=? AND scene='SUMMARY_FINAL'", Integer.class, owner)).isZero();
    assertThat(inputs.stream().filter(n -> "TOPIC".equals(n.path("stage").asText())).count()).isEqualTo(2);
  }

  @Test
  void topicFailureKeepsEarlierWritingAndRetryOnlyWritesMissingTopic() {
    service = new SummaryService(db, tx, json, ai, idem);
    worker = new SummaryWorker(service);
    db.update("UPDATE slides SET text_content=? WHERE id=?", "定义与条件".repeat(2300), first);
    failSecondTopic.set(true);
    var v = generate();
    assertThat(v.status()).isEqualTo("FAILED");
    assertThat(v.phaseLabel()).isEqualTo("撰写主题");
    assertThat(service.state(owner, material).current()).isNull();
    int before = calls.get();
    var saved = db.queryForList("SELECT result FROM material_summary_parts WHERE version_id=? AND status='SUCCEEDED' ORDER BY position_no", String.class, v.id());
    failSecondTopic.set(false);
    service.retry(owner, v.id(), id());
    while (worker.runOne()) {}
    assertThat(calls.get()).isEqualTo(before + 1);
    assertThat(service.version(owner, v.id()).status()).isEqualTo("SUCCEEDED");
    assertThat(db.queryForList("SELECT result FROM material_summary_parts WHERE version_id=? AND status='SUCCEEDED' ORDER BY position_no LIMIT " + saved.size(), String.class, v.id())).isEqualTo(saved);
  }

  @Test
  void failedLegacyFinalUpgradesOnExplicitResumeAndReusesExistingParts() {
    db.update("UPDATE slides SET text_content=? WHERE id=?", "定义与条件".repeat(4500), first);
    truncateFinal.set(true);
    var v = generate();
    assertThat(v.status()).isEqualTo("FAILED");
    assertThat(v.completedSteps()).isEqualTo(3);
    service = new SummaryService(db, tx, json, ai, idem);
    worker = new SummaryWorker(service);
    assertThat(service.version(owner, v.id()).resumeWillPlan()).isTrue();
    truncateFinal.set(false);
    service.retry(owner, v.id(), id());
    while (worker.runOne()) {}
    assertThat(service.version(owner, v.id()).workflow()).isEqualTo("THEMATIC");
    assertThat(service.version(owner, v.id()).status()).isEqualTo("SUCCEEDED");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_calls WHERE owner_id=? AND scene='SUMMARY_PART'", Integer.class, owner)).isEqualTo(3);
    assertThat(calls.get()).isEqualTo(7);
  }

  @Test
  void thematicQuotaCanRunOutAfterOutlineAndResumeWithoutRepeatingSuccessfulSteps() {
    ai = client(5);
    service = new SummaryService(db, tx, json, ai, idem);
    worker = new SummaryWorker(service);
    db.update("UPDATE slides SET text_content=? WHERE id=?", "定义与条件".repeat(4500), first);
    var v = generate();
    assertThat(v.status()).isEqualTo("FAILED");
    assertThat(v.errorCode()).isEqualTo("AI_QUOTA_EXCEEDED");
    assertThat(v.remainingRequests()).isEqualTo(1);
    assertThat(calls.get()).isEqualTo(5);
    ai = client(6);
    service = new SummaryService(db, tx, json, ai, idem);
    worker = new SummaryWorker(service);
    service.retry(owner, v.id(), id());
    while (worker.runOne()) {}
    assertThat(ai.remaining(owner)).isZero();
    assertThat(calls.get()).isEqualTo(6);
    assertThat(service.version(owner, v.id()).status()).isEqualTo("SUCCEEDED");
  }

  @Test
  void localAssemblyCanResumeWhenAiDisabledAndNoQuotaRemains() {
    service = new SummaryService(db, tx, json, ai, idem);
    worker = new SummaryWorker(service);
    db.update("UPDATE slides SET text_content=? WHERE id=?", "定义与条件".repeat(2300), first);
    var v = service.generate(owner, material, id());
    while (!"合成文档".equals(service.version(owner, v.id()).phaseLabel())) assertThat(worker.runOne()).isTrue();
    int before = calls.get();
    service.cancel(owner, v.id());
    ai = client(0);
    service = new SummaryService(db, tx, json, ai, idem);
    worker = new SummaryWorker(service);
    assertThat(service.version(owner, v.id()).remainingRequests()).isZero();
    service.retry(owner, v.id(), id());
    assertThat(worker.runOne()).isTrue();
    assertThat(service.version(owner, v.id()).status()).isEqualTo("SUCCEEDED");
    assertThat(calls.get()).isEqualTo(before);
  }

  @Test
  void forgedTopicSourceIsRejectedAndOutlineRemainsReusable() {
    service = new SummaryService(db, tx, json, ai, idem);
    worker = new SummaryWorker(service);
    db.update("UPDATE slides SET text_content=? WHERE id=?", "定义与条件".repeat(2300), first);
    var v = service.generate(owner, material, id());
    while (!"撰写主题".equals(service.version(owner, v.id()).phaseLabel())) assertThat(worker.runOne()).isTrue();
    invalid.set(true);
    assertThat(worker.runOne()).isTrue();
    assertThat(service.version(owner, v.id()).errorMessage()).contains("引用了本次未提供的页面");
    assertThat(service.state(owner, material).current()).isNull();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM material_summary_parts WHERE version_id=? AND phase='OUTLINE' AND status='SUCCEEDED'", Integer.class, v.id())).isEqualTo(1);
    assertThat(worker.runOne()).isFalse();
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
  void unlimitedSummaryCanGenerateAfterPreviousAttemptsExceedTen() {
    for (int i = 0; i < 11; i++) {
      db.update("INSERT INTO ai_calls(id,owner_id,course_id,scene,model,prompt_version,request_fingerprint,quota_date,status)"
          + " VALUES(?,?,?,'READER_CHAT','stub','test',REPEAT('0',64),?,'FAILED')",
          id(), owner, course, java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")));
    }
    var unlimited = new SummaryService(db, tx, json, client(0), idem);
    assertThat(unlimited.state(owner, material).remainingToday()).isNull();
    var v = unlimited.generate(owner, material, id());
    new SummaryWorker(unlimited).runOne();
    assertThat(unlimited.version(owner, v.id()).status()).isEqualTo("SUCCEEDED");
    assertThat(calls).hasValue(1);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_calls WHERE owner_id=?", Integer.class, owner))
        .isEqualTo(12);
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
