package cn.review.chat;

import static org.assertj.core.api.Assertions.*;

import cn.review.common.*;
import cn.review.course.CourseService;
import cn.review.material.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "app.jobs.enabled=false")
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@ActiveProfiles("test")
class ReaderChatIntegrationTest {
  @Autowired JdbcTemplate db;
  @Autowired TransactionTemplate tx;
  @Autowired ObjectMapper json;
  @Autowired MaterialService materials;
  @Autowired MaterialStorage storage;
  @Autowired IdempotencyService idem;
  @Autowired MaterialPipeline pipeline;
  @Autowired CourseService courses;
  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  HttpServer server;
  ReaderAiClient ai;
  ChatService chat;
  ChatWorker worker;
  String owner, course, material, first, second, session;
  AtomicInteger calls;
  AtomicReference<String> request, content;
  AtomicInteger delay;
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
    String username = "c" + owner.replace("-", "").substring(0, 20);
    db.update(
        "INSERT INTO app_users(id,username,username_norm,password_hash,display_name)"
            + " VALUES(?,?,?,'fixture','答疑测试')",
        owner,
        username,
        username);
    db.update("INSERT INTO courses(id,owner_id,name) VALUES(?,?,'合成答疑')", course, owner);
    db.update(
        "INSERT INTO materials(id,course_id,display_name,size_bytes,sha256,slide_count)"
            + " VALUES(?,?,'合成课件.pptx',100,REPEAT('0',64),2)",
        material,
        course);
    db.update(
        "INSERT INTO"
            + " slides(id,material_id,page_no,source_slide_id,is_hidden,text_content,text_revision)"
            + " VALUES(?,?,1,1,false,'敏捷开发重视个体和互动',1),(?,?,2,2,false,'边界值测试与等价类划分',1)",
        first,
        material,
        second,
        material);
    calls = new AtomicInteger();
    request = new AtomicReference<>();
    content = new AtomicReference<>(valid(first, "个体和互动"));
    delay = new AtomicInteger();
    received = new CountDownLatch(1);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/chat/completions",
        exchange -> {
          calls.incrementAndGet();
          request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          received.countDown();
          try {
            Thread.sleep(delay.get());
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] bytes = content.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (var out = exchange.getResponseBody()) {
            out.write(bytes);
          } catch (java.io.IOException ignored) {
          }
        });
    server.start();
    ai = client(10, true);
    chat = new ChatService(db, tx, json, ai, materials, idem);
    worker = new ChatWorker(chat, ai);
    session = chat.create(owner, material, id()).id();
  }

  @AfterEach
  void close() {
    server.stop(0);
  }

  ReaderAiClient client(int limit, boolean enabled) {
    return new ReaderAiClient(
        db,
        tx,
        json,
        enabled,
        "test-only-key",
        "http://127.0.0.1:" + server.getAddress().getPort(),
        "deepseek-flash",
        limit,
        true);
  }

  String valid(String slide, String quote) throws Exception {
    return response(
        json.writeValueAsString(
            Map.of(
                "explanation",
                "这是对课件的解释。",
                "supplement",
                "举一个生活中的例子。",
                "limitations",
                "",
                "citations",
                List.of(Map.of("slideId", slide, "kind", "TEXT", "quote", quote)))));
  }

  String response(String answer) throws Exception {
    return json.writeValueAsString(
        Map.of(
            "id",
            "synthetic-chat",
            "choices",
            List.of(Map.of("finish_reason", "stop", "message", Map.of("content", answer))),
            "usage",
            Map.of("prompt_tokens", 100, "completion_tokens", 80)));
  }

  ChatService.Turn send(String slide) {
    return chat.send(owner, session, new ChatService.Send("解释这个概念", slide, false, true), id());
  }

  @Test
  void pageIsFixedAndReplayDoesNotInsertOrPayTwice() throws Exception {
    String key = id();
    var input = new ChatService.Send("解释敏捷", first, false, true);
    var turn = chat.send(owner, session, input, key);
    assertThat(chat.send(owner, session, input, key).id()).isEqualTo(turn.id());
    assertThatThrownBy(
            () -> chat.send(owner, session, new ChatService.Send("不同问题", second, false, true), key))
        .isInstanceOf(ApiException.class);
    materials.saveProgress(owner, material, 2);
    assertThat(worker.runOne()).isTrue();
    var result = chat.turn(owner, turn.id());
    assertThat(result.status()).isEqualTo("SUCCEEDED");
    assertThat(result.sources().get(0).pageNo()).isEqualTo(1);
    assertThat(result.answer().citations().get(0).quote()).isEqualTo("个体和互动");
    assertThat(chat.send(owner, session, input, key).status()).isEqualTo("SUCCEEDED");
    assertThat(calls).hasValue(1);
    assertThat(chat.get(owner, session).turns()).hasSize(1);
    assertThatThrownBy(() -> chat.get(id(), session)).isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> chat.send(owner, session, new ChatService.Send("问题", id(), false, false), id()))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void followupSendsBoundedHistoryAndNewPageButCanStartFresh() throws Exception {
    send(first);
    worker.runOne();
    content.set(valid(second, "边界值测试"));
    send(second);
    worker.runOne();
    var body = json.readTree(request.get());
    var data = json.readTree(body.at("/messages/1/content/0/text").asText());
    assertThat(data.path("pages").size()).isEqualTo(2);
    assertThat(data.path("pages").get(0).path("slideId").asText()).isEqualTo(second);
    assertThat(data.path("history").size()).isEqualTo(1);
    chat.send(owner, session, new ChatService.Send("独立解释", second, false, false), id());
    worker.runOne();
    data = json.readTree(json.readTree(request.get()).at("/messages/1/content/0/text").asText());
    assertThat(data.path("pages").size()).isEqualTo(1);
    assertThat(data.path("history").size()).isZero();
  }

  @Test
  void invalidCitationsFailAndExplicitRetryReusesTheRound() throws Exception {
    content.set(valid("invented", "个体和互动"));
    var turn = send(first);
    worker.runOne();
    assertThat(chat.turn(owner, turn.id()).status()).isEqualTo("FAILED");
    assertThat(chat.turn(owner, turn.id()).answer()).isNull();
    content.set(valid(first, "不存在的原文"));
    String retry = id();
    chat.retry(owner, turn.id(), retry);
    chat.retry(owner, turn.id(), retry);
    worker.runOne();
    assertThat(chat.turn(owner, turn.id()).status()).isEqualTo("FAILED");
    content.set(valid(first, "个体和互动"));
    chat.retry(owner, turn.id(), id());
    worker.runOne();
    assertThat(chat.get(owner, session).turns()).hasSize(1);
    assertThat(chat.turn(owner, turn.id()).status()).isEqualTo("SUCCEEDED");
    assertThat(calls).hasValue(3);
  }

  @Test
  void cancellationDuringHttpRequestDiscardsLateAnswer() throws Exception {
    delay.set(1200);
    var turn = send(first);
    var claim = worker.claim();
    var pool = Executors.newSingleThreadExecutor();
    try {
      var future =
          pool.submit(
              () -> {
                try {
                  worker.perform(claim);
                } catch (Exception error) {
                  worker.fail(claim, error);
                }
              });
      assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
      chat.cancel(owner, turn.id());
      future.get(5, TimeUnit.SECONDS);
      assertThat(chat.turn(owner, turn.id()).status()).isEqualTo("CANCELLED");
      assertThat(chat.turn(owner, turn.id()).answer()).isNull();
      assertThat(calls).hasValue(1);
      assertThat(
              db.queryForObject(
                  "SELECT status FROM ai_calls WHERE job_id=?", String.class, turn.jobId()))
          .isEqualTo("UNKNOWN");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void queuedCancellationNeverCallsProviderAndCourseBusyIsExplicit() {
    var turn = send(first);
    assertThatThrownBy(() -> send(second))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo("CHAT_BUSY"));
    chat.cancel(owner, turn.id());
    assertThat(worker.runOne()).isFalse();
    assertThat(calls).hasValue(0);
  }

  @Test
  void expiredLeaseIsFailedWithoutAutomaticPaidRetry() {
    var turn = send(first);
    worker.claim();
    db.update(
        "UPDATE async_jobs SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE id=?",
        turn.jobId());
    pipeline.recover(); // The material worker must not recover paid chat tasks.
    assertThat(chat.turn(owner, turn.id()).status()).isEqualTo("RUNNING");
    worker.recover();
    assertThat(chat.turn(owner, turn.id()).status()).isEqualTo("FAILED");
    assertThat(worker.runOne()).isFalse();
    assertThat(calls).hasValue(0);
  }

  @Test
  void changedSourceAndDeletedCourseCannotPublish() throws Exception {
    var turn = send(first);
    var claim = worker.claim();
    db.update("UPDATE slides SET text_revision=2 WHERE id=?", first);
    assertThatThrownBy(() -> worker.perform(claim)).isInstanceOf(ApiException.class);
    assertThat(calls).hasValue(0);
    worker.fail(claim, new ApiException(409, "SOURCE_CHANGED", "来源更新"));
    assertThat(chat.turn(owner, turn.id()).errorCode()).isEqualTo("SOURCE_CHANGED");
    assertThatThrownBy(() -> chat.retry(owner, turn.id(), id())).isInstanceOf(ApiException.class);
    db.update("UPDATE courses SET status='DELETING' WHERE id=?", course);
    assertThatThrownBy(() -> chat.get(owner, session)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> worker.perform(claim)).isInstanceOf(ApiException.class);
  }

  @Test
  void sharedQuotaCountsAttemptsAcrossScenesAndDisabledHasNoHttp() {
    var one = client(1, true);
    var service = new ChatService(db, tx, json, one, materials, idem);
    var task = new ChatWorker(service, one);
    db.update(
        "INSERT INTO"
            + " ai_calls(id,owner_id,course_id,job_id,scene,model,prompt_version,request_fingerprint,quota_date,status)"
            + " VALUES(?,?,?,?,'FOCUS_LOCATE','mock','test',REPEAT('0',64),CURRENT_DATE(),'FAILED')",
        id(),
        owner,
        course,
        id());
    // Use the same Beijing date as the product, independently of the MySQL timezone.
    db.update(
        "UPDATE ai_calls SET quota_date=? WHERE owner_id=?",
        java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")),
        owner);
    var turn = service.send(owner, session, new ChatService.Send("解释", first, false, false), id());
    task.runOne();
    assertThat(service.turn(owner, turn.id()).errorCode()).isEqualTo("AI_QUOTA_EXCEEDED");
    assertThat(calls).hasValue(0);
    var disabled = new ChatService(db, tx, json, client(10, false), materials, idem);
    assertThatThrownBy(
            () ->
                disabled.send(
                    owner, session, new ChatService.Send("解释", first, false, false), id()))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo("AI_DISABLED"));
  }

  @Test
  void imageUsesOnlySelectedOwnedAssetAndAcceptsImageCitation() throws Exception {
    String asset = id(), key = "chat-test/" + asset + ".png";
    var path = storage.resolve(key);
    Files.createDirectories(path.getParent());
    var png = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_RGB);
    javax.imageio.ImageIO.write(png, "png", path.toFile());
    try {
      db.update(
          "INSERT INTO file_assets(id,material_id,kind,storage_key,media_type,size_bytes)"
              + " VALUES(?,?,'PAGE',?,'image/png',?)",
          asset,
          material,
          key,
          Files.size(path));
      db.update("UPDATE slides SET preview_asset_id=?,text_content='' WHERE id=?", asset, first);
      content.set(
          response(
              json.writeValueAsString(
                  Map.of(
                      "explanation",
                      "图片中的内容",
                      "supplement",
                      "",
                      "limitations",
                      "请核对原图",
                      "citations",
                      List.of(Map.of("slideId", first, "kind", "IMAGE", "quote", ""))))));
      var turn = chat.send(owner, session, new ChatService.Send("解释这张图", first, true, false), id());
      worker.runOne();
      assertThat(chat.turn(owner, turn.id()).status()).isEqualTo("SUCCEEDED");
      assertThat(json.readTree(request.get()).at("/messages/1/content/1/image_url/url").asText())
          .startsWith("data:image/png;base64,");
      assertThat(chat.turn(owner, turn.id()).sources().get(0).imageAttached()).isTrue();
    } finally {
      Files.deleteIfExists(path);
    }
  }

  @Test
  void fabricatedImageCitationWithoutImageAndOversizedOutputAreRejected() throws Exception {
    var sources =
        List.of(new ChatService.Source(first, material, "合成课件", 1, 1L, "原文", false, null));
    assertThatThrownBy(
            () ->
                ai.validate(
                    "{\"explanation\":\"解释\",\"supplement\":\"\",\"limitations\":\"\",\"citations\":[{\"slideId\":\""
                        + first
                        + "\",\"kind\":\"IMAGE\",\"quote\":\"\"}]}",
                    sources,
                    false))
        .isInstanceOf(ApiException.class);
    content.set("x".repeat(270000));
    var turn = send(first);
    worker.runOne();
    assertThat(chat.turn(owner, turn.id()).status()).isEqualTo("FAILED");
    assertThat(calls).hasValue(1);
  }

  @Test
  void deletingMaterialPreservesAnswersButRemovesLiveSourcesAndCoursePurgeClearsChat()
      throws Exception {
    var turn = send(first);
    worker.runOne();
    materials.delete(owner, material, 1, id());
    assertThat(chat.row(owner, turn.id(), false).sources().get(0).text()).isEmpty();
    assertThat(chat.turn(owner, turn.id()).sources().get(0).available()).isFalse();
    assertThat(chat.turn(owner, turn.id()).answer()).isNotNull();
    for (int i = 0;
        i < 200
            && !db.queryForObject("SELECT status FROM materials WHERE id=?", String.class, material)
                .equals("DELETED");
        i++) pipeline.runOne();
    assertThat(chat.turn(owner, turn.id()).answer()).isNotNull();
    courses.delete(owner, course, 1, id());
    for (int i = 0;
        i < 200
            && !db.queryForObject("SELECT status FROM courses WHERE id=?", String.class, course)
                .equals("DELETED");
        i++) pipeline.runOne();
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM chat_sessions WHERE course_id=?", Integer.class, course))
        .isZero();
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM chat_turns WHERE id=?", Integer.class, turn.id()))
        .isZero();
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM ai_calls WHERE job_id=?", Integer.class, turn.jobId()))
        .isEqualTo(1);
  }

  @Test
  void distinctExactQuotesOnSamePageAreValidButRepeatedOrRewrittenQuotesAreNot() throws Exception {
    var sources =
        List.of(new ChatService.Source(first, material, "课件", 1, 1L, "敏捷开发重视个体和互动", false, null));
    var refs =
        List.of(
            Map.of("slideId", first, "kind", "TEXT", "quote", "敏捷开发"),
            Map.of("slideId", first, "kind", "TEXT", "quote", "个体和互动"));
    String answer =
        json.writeValueAsString(
            Map.of("explanation", "解释", "supplement", "", "limitations", "", "citations", refs));
    assertThat(ai.validate(answer, sources, false).citations()).hasSize(2);
    assertThatThrownBy(() -> ai.validate(answer.replace("个体和互动", "并不存在"), sources, false))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> ai.validate(answer.replace("个体和互动", "敏捷开发"), sources, false))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void httpBoundariesRequireLoginCsrfAndOwnership() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/chat-sessions/" + session))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isUnauthorized());
    var principal =
        new cn.review.auth.StudentPrincipal(
            new cn.review.auth.UserRow(owner, "chat-user", "chat-user", "fixture", "测试", "ACTIVE"));
    var context =
        org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            principal, "", principal.getAuthorities()));
    var auth = new org.springframework.mock.web.MockHttpSession();
    auth.setAttribute("SPRING_SECURITY_CONTEXT", context);
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/materials/" + material + "/chat-sessions")
                .session(auth)
                .header("Idempotency-Key", id()))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
    var csrf =
        json.readTree(
                mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/auth/csrf")
                            .session(auth))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .path("data");
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/materials/" + material + "/chat-sessions")
                .session(auth)
                .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                .header("Idempotency-Key", id()))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
    var payload =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/chat-sessions/" + session)
                    .session(auth))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(payload).doesNotContain("context_snapshot", "test-only-key", "password");
    String other = id();
    db.update("INSERT INTO courses(id,owner_id,name) VALUES(?,?,'其他课程')", other, owner);
    // An authenticated owner still cannot inject a page from another material into this session.
    assertThatThrownBy(
            () -> chat.send(owner, session, new ChatService.Send("问题", id(), false, false), id()))
        .isInstanceOf(ApiException.class);
    context.setAuthentication(
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            new cn.review.auth.StudentPrincipal(
                new cn.review.auth.UserRow(id(), "other", "other", "fixture", "其他", "ACTIVE")),
            "",
            principal.getAuthorities()));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/chat-sessions/" + session)
                .session(auth))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isNotFound());
  }
}
