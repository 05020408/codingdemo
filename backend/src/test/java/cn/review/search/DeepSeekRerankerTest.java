package cn.review.search;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "app.jobs.enabled=false")
@ActiveProfiles("test")
class DeepSeekRerankerTest {
  @Autowired JdbcTemplate db;
  @Autowired TransactionTemplate tx;
  @Autowired ObjectMapper json;
  HttpServer server;
  DeepSeekReranker ai;
  String owner, course;
  AtomicInteger calls;
  AtomicReference<String> reply;
  AtomicReference<String> request;
  List<SearchCatalog.Candidate> pages =
      List.of(
          new SearchCatalog.Candidate("slide-1", "m", "合成样本", 2, "敏捷", "敏捷开发重视个体和互动", 1, false));

  String id() {
    return UUID.randomUUID().toString();
  }

  @BeforeEach
  void start() throws Exception {
    owner = id();
    course = id();
    String name = "ai" + owner.replace("-", "").substring(0, 20);
    db.update(
        "INSERT INTO app_users(id,username,username_norm,password_hash,display_name)"
            + " VALUES(?,?,?,'fixture','合成 AI 测试')",
        owner,
        name,
        name);
    db.update("INSERT INTO courses(id,owner_id,name) VALUES(?,?,'合成 AI 测试')", course, owner);
    calls = new AtomicInteger();
    reply = new AtomicReference<>();
    request = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/chat/completions",
        exchange -> {
          calls.incrementAndGet();
          request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (var out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    ai =
        new DeepSeekReranker(
            db,
            tx,
            json,
            true,
            "test-only-key",
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "mock-model",
            2);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  String response(String content) throws Exception {
    return json.writeValueAsString(
        Map.of(
            "id",
            "synthetic-response",
            "choices",
            List.of(Map.of("finish_reason", "stop", "message", Map.of("content", content))),
            "usage",
            Map.of("prompt_tokens", 50, "completion_tokens", 20)));
  }

  @Test
  void oversizedResponseStopsAndDoesNotRetry() {
    reply.set("x".repeat(270000));
    var result = ai.rank(owner, course, id(), "敏捷", pages);
    assertThat(result.matches()).isEmpty();
    assertThat(result.warning()).isNotNull();
    assertThat(calls).hasValue(1);
  }

  @Test
  void validExactQuoteStoresUsageAndSameJobNeverCallsTwice() throws Exception {
    reply.set(
        response(
            "{\"matches\":[{\"slideId\":\"slide-1\",\"quote\":\"个体和互动\",\"reason\":\"涉及敏捷价值观\"}]}"));
    String job = id();
    var result = ai.rank(owner, course, job, "敏捷价值观", pages);
    assertThat(result.warning()).isNull();
    assertThat(result.matches()).hasSize(1);
    assertThat(result.matches().get(0).quote()).isEqualTo("个体和互动");
    assertThat(json.readTree(request.get()).at("/thinking/type").asText()).isEqualTo("disabled");
    assertThat(
            db.queryForObject("SELECT input_tokens FROM ai_calls WHERE job_id=?", Long.class, job))
        .isEqualTo(50);
    assertThat(db.queryForObject("SELECT status FROM ai_calls WHERE job_id=?", String.class, job))
        .isEqualTo("SUCCEEDED");
    assertThat(ai.rank(owner, course, job, "敏捷价值观", pages).warning())
        .isEqualTo("AI_ALREADY_ATTEMPTED");
    assertThat(calls).hasValue(1);
  }

  @Test
  void inventedIdOrQuoteFailsClosedAndQuotaCountsFailures() throws Exception {
    reply.set(
        response("{\"matches\":[{\"slideId\":\"invented\",\"quote\":\"原文\",\"reason\":\"相关\"}]}"));
    assertThat(ai.rank(owner, course, id(), "敏捷", pages).warning())
        .isEqualTo("AI_INVALID_RESPONSE");
    reply.set(
        response(
            "{\"matches\":[{\"slideId\":\"slide-1\",\"quote\":\"并不存在的引文\",\"reason\":\"相关\"}]}"));
    assertThat(ai.rank(owner, course, id(), "敏捷", pages).matches()).isEmpty();
    assertThat(ai.rank(owner, course, id(), "敏捷", pages).warning()).isEqualTo("AI_QUOTA_EXCEEDED");
    assertThat(calls).hasValue(2);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM ai_calls WHERE owner_id=? AND status='FAILED'",
                Integer.class,
                owner))
        .isEqualTo(2);
  }

  @Test
  void malformedOrTruncatedResponseIsRejectedWithoutPaidRetry() throws Exception {
    reply.set("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{}\"}}]}");
    var result = ai.rank(owner, course, id(), "敏捷", pages);
    assertThat(result.warning()).isEqualTo("AI_INVALID_RESPONSE");
    assertThat(result.matches()).isEmpty();
    assertThat(calls).hasValue(1);
    assertThatThrownBy(
            () -> new DeepSeekReranker(db, tx, json, true, "x", "http://example.com", "m", 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
