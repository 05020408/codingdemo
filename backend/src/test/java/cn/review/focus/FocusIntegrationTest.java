package cn.review.focus;

import static org.assertj.core.api.Assertions.*;

import cn.review.common.ApiException;
import cn.review.course.CourseService;
import cn.review.material.MaterialPipeline;
import cn.review.search.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {"app.jobs.enabled=false"})
@ActiveProfiles("test")
class FocusIntegrationTest {
  @Autowired FocusService focuses;
  @Autowired JdbcTemplate db;
  @Autowired PageIndex index;
  @Autowired DeepSeekReranker ai;
  @Autowired MaterialPipeline pipeline;
  @Autowired CourseService courses;
  String owner, course, material, slide, second;
  FocusWorker worker;

  String id() {
    return UUID.randomUUID().toString();
  }

  @BeforeEach
  void seed() throws Exception {
    owner = id();
    course = id();
    material = id();
    slide = id();
    second = id();
    String username = "f" + owner.replace("-", "").substring(0, 20);
    db.update(
        "INSERT INTO app_users(id,username,username_norm,password_hash,display_name)"
            + " VALUES(?,?,?,'fixture','重点测试')",
        owner,
        username,
        username);
    db.update("INSERT INTO courses(id,owner_id,name) VALUES(?,?,'检索测试')", course, owner);
    db.update(
        "INSERT INTO materials(id,course_id,display_name,size_bytes,sha256,slide_count)"
            + " VALUES(?,?,'合成课件.pptx',100,REPEAT('0',64),2)",
        material,
        course);
    db.update(
        "INSERT INTO"
            + " slides(id,material_id,page_no,source_slide_id,is_hidden,title,text_content,text_revision)"
            + " VALUES(?,?,1,1,false,'敏捷开发','敏捷开发的四个价值观 Scrum"
            + " sprint',1),(?,?,2,2,false,'软件测试','边界值测试与等价类划分',1)",
        slide,
        material,
        second,
        material);
    db.update(
        "INSERT INTO"
            + " material_stages(material_id,stage,status,published_revision,requested_revision,input_text_revision)"
            + " VALUES(?,'TEXT','SUCCEEDED',1,1,NULL),(?,'INDEX','SUCCEEDED',1,1,1)",
        material,
        material);
    index.build(
        course,
        material,
        1,
        1,
        List.of(
            new PageIndex.Page(slide, "敏捷开发的四个价值观 Scrum sprint"),
            new PageIndex.Page(second, "边界值测试与等价类划分")));
    worker = new FocusWorker(focuses, ai);
  }

  FocusService.View create(String content) {
    return focuses.create(owner, course, new FocusService.Create(content, null), id());
  }

  void locate(FocusService.View focus) {
    focuses.locate(owner, focus.id(), new FocusService.Locate(focus.version(), false), id());
    assertThat(worker.runOne()).isTrue();
  }

  @Test
  void aiFailureFallsBackToKeywordAndPendingTextManualLinkIsSafe() {
    var fallback = org.mockito.Mockito.mock(DeepSeekReranker.class);
    org.mockito.Mockito.when(
            fallback.rank(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(new DeepSeekReranker.Result(List.of(), "AI_INVALID_RESPONSE"));
    var focus = create("敏捷开发");
    focuses.locate(owner, focus.id(), new FocusService.Locate(focus.version(), true), id());
    new FocusWorker(focuses, fallback).runOne();
    var result = focuses.get(owner, focus.id());
    assertThat(result.method()).isEqualTo("KEYWORD");
    assertThat(result.warnings()).contains("AI_INVALID_RESPONSE");
    assertThat(focuses.matches(owner, focus.id(), false).items()).isNotEmpty();
    db.update("UPDATE slides SET text_revision=NULL,text_content=NULL WHERE id=?", second);
    var manual =
        focuses.decide(
            owner,
            focus.id(),
            second,
            new FocusService.Decision("CONFIRMED", result.version(), null));
    assertThat(manual.link().needsReview()).isTrue();
  }

  @Test
  void simultaneousEditsWithSameVersionCannotLoseAnUpdate() throws Exception {
    var focus = create("并发修改前");
    var start = new java.util.concurrent.CountDownLatch(1);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      java.util.concurrent.Callable<Boolean> edit =
          () -> {
            start.await();
            try {
              focuses.patch(
                  owner,
                  focus.id(),
                  new FocusService.Edit(
                      "修改为 " + Thread.currentThread().getName(), null, focus.version()));
              return true;
            } catch (ApiException conflict) {
              assertThat(conflict.code()).isEqualTo("VERSION_CONFLICT");
              return false;
            }
          };
      var first = pool.submit(edit);
      var second = pool.submit(edit);
      start.countDown();
      assertThat(
              List.of(
                  first.get(10, java.util.concurrent.TimeUnit.SECONDS),
                  second.get(10, java.util.concurrent.TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
      assertThat(focuses.get(owner, focus.id()).version()).isEqualTo(focus.version() + 1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void crudIdempotencyBatchAndOwnership() {
    String key = id();
    var input = new FocusService.Create("敏捷价值观", null);
    var focus = focuses.create(owner, course, input, key);
    assertThat(focuses.create(owner, course, input, key).id()).isEqualTo(focus.id());
    assertThatThrownBy(
            () -> focuses.create(owner, course, new FocusService.Create("不同内容", null), key))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> focuses.get(id(), focus.id())).isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                focuses.batch(
                    owner, course, new FocusService.Batch(List.of("合法", " "), null), id()))
        .isInstanceOf(ApiException.class);
    assertThat(focuses.list(owner, course, 1, 20, null, null).total()).isEqualTo(1);
    assertThat(
            focuses
                .split(owner, course, new FocusService.SplitInput("1. 敏捷开发\n2、软件测试"), id())
                .items())
        .containsExactly("敏捷开发", "软件测试");
    var edited =
        focuses.patch(owner, focus.id(), new FocusService.Edit(null, "MASTERED", focus.version()));
    assertThat(edited.contentRevision()).isEqualTo(1);
    assertThatThrownBy(
            () ->
                focuses.patch(
                    owner, focus.id(), new FocusService.Edit("新内容", null, focus.version())))
        .isInstanceOf(ApiException.class);
    focuses.delete(owner, focus.id(), edited.version(), id());
    assertThatThrownBy(() -> focuses.create(owner, course, input, key))
        .isInstanceOf(ApiException.class);
    assertThat(
            db.queryForObject(
                "SELECT content FROM focus_items WHERE id=?", String.class, focus.id()))
        .isEmpty();
  }

  @Test
  void locateConfirmRejectManualAndRerunPreserveHumanDecisions() {
    var focus = create("敏捷开发 Scrum");
    locate(focus);
    var matched = focuses.get(owner, focus.id());
    assertThat(matched.matchStatus()).isEqualTo("MATCHED");
    var link = focuses.matches(owner, focus.id(), false).items().get(0);
    assertThat(link.slideId()).isEqualTo(slide);
    assertThat(link.evidenceText()).contains("敏捷开发");
    focuses.decide(
        owner,
        focus.id(),
        slide,
        new FocusService.Decision("CONFIRMED", matched.version(), link.version()));
    var current = focuses.get(owner, focus.id());
    focuses.decide(
        owner, focus.id(), second, new FocusService.Decision("CONFIRMED", current.version(), null));
    locate(focuses.get(owner, focus.id()));
    assertThat(focuses.matches(owner, focus.id(), false).items())
        .hasSize(2)
        .allMatch(l -> l.decision().equals("CONFIRMED"));
    var edited =
        focuses.patch(
            owner,
            focus.id(),
            new FocusService.Edit("软件测试", null, focuses.get(owner, focus.id()).version()));
    assertThat(focuses.matches(owner, focus.id(), false).items())
        .allMatch(FocusService.Link::needsReview);
    locate(edited);
    var secondLink =
        focuses.matches(owner, focus.id(), false).items().stream()
            .filter(l -> l.slideId().equals(second))
            .findFirst()
            .orElseThrow();
    focuses.decide(
        owner,
        focus.id(),
        second,
        new FocusService.Decision(
            "REJECTED", focuses.get(owner, focus.id()).version(), secondLink.version()));
    locate(focuses.get(owner, focus.id()));
    assertThat(focuses.matches(owner, focus.id(), true).items())
        .anyMatch(l -> l.slideId().equals(second) && l.decision().equals("REJECTED"));
  }

  @Test
  void noMatchIsDifferentFromNotReadyAndLostIndexCanRebuild() throws Exception {
    var focus = create("火星量子河流");
    locate(focus);
    assertThat(focuses.get(owner, focus.id()).matchStatus()).isEqualTo("NO_MATCH");
    index.removeMaterial(material);
    assertThatThrownBy(
            () ->
                focuses.locate(
                    owner,
                    focus.id(),
                    new FocusService.Locate(focuses.get(owner, focus.id()).version(), false),
                    id()))
        .isInstanceOf(ApiException.class)
        .extracting("code")
        .isEqualTo("PROCESSING_NOT_READY");
    pipeline.ensureIndexes();
    for (int n = 0; n < 150 && focuses.catalog.scope(owner, course).isEmpty(); n++)
      pipeline.runOne();
    assertThat(focuses.catalog.scope(owner, course)).hasSize(1);
  }

  @Test
  void lateResultCannotOverwriteEditedFocusOrChangedSources() throws Exception {
    var focus = create("敏捷开发");
    focuses.locate(owner, focus.id(), new FocusService.Locate(focus.version(), false), id());
    var claimed = worker.claim();
    assertThat(claimed).isNotNull();
    var edited =
        focuses.patch(
            owner,
            focus.id(),
            new FocusService.Edit("软件测试", null, focuses.get(owner, focus.id()).version()));
    assertThatThrownBy(() -> worker.perform(claimed)).isInstanceOf(ApiException.class);
    assertThat(focuses.matches(owner, focus.id(), false).items()).isEmpty();
    focuses.locate(owner, focus.id(), new FocusService.Locate(edited.version(), false), id());
    var next = worker.claim();
    db.update(
        "UPDATE materials SET status='DELETING',lifecycle_version=lifecycle_version+1 WHERE id=?",
        material);
    assertThatThrownBy(() -> worker.perform(next))
        .isInstanceOf(ApiException.class)
        .extracting("code")
        .isEqualTo("SOURCE_CHANGED");
  }

  @Test
  void expiredLeaseDoesNotPublishAndRecoveryNeverRetriesAi() throws Exception {
    var focus = create("敏捷开发");
    focuses.locate(owner, focus.id(), new FocusService.Locate(focus.version(), true), id());
    var claim = worker.claim();
    db.update(
        "UPDATE async_jobs SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE id=?",
        claim.id());
    assertThatThrownBy(() -> worker.perform(claim)).isInstanceOf(ApiException.class);
    worker.recover();
    assertThat(
            db.queryForObject("SELECT status FROM async_jobs WHERE id=?", String.class, claim.id()))
        .isEqualTo("FAILED");
    assertThat(worker.runOne()).isFalse();
    assertThat(focuses.matches(owner, focus.id(), false).items()).isEmpty();
  }

  @Test
  void materialPurgeRetainsOnlyReferencedSlideTombstoneThenCoursePurgeRemovesIt() {
    var focus = create("敏捷开发");
    focuses.decide(
        owner, focus.id(), slide, new FocusService.Decision("CONFIRMED", focus.version(), null));
    db.update("UPDATE materials SET status='DELETING',lifecycle_version=2 WHERE id=?", material);
    String job = id();
    db.update(
        "INSERT INTO"
            + " async_jobs(id,owner_id,course_id,type,target_id,dedup_key,input_snapshot,run_after)"
            + " VALUES(?,?,?,'MATERIAL_PURGE',?,?,?,CURRENT_TIMESTAMP(3))",
        job,
        owner,
        course,
        material,
        "purge:" + job,
        "{\"lifecycleVersion\":2}");
    for (int n = 0;
        n < 100
            && !"DELETED"
                .equals(
                    db.queryForObject(
                        "SELECT status FROM materials WHERE id=?", String.class, material));
        n++) pipeline.runOne();
    var link = focuses.matches(owner, focus.id(), false).items().get(0);
    assertThat(link.sourceAvailable()).isFalse();
    assertThat(link.materialName()).isEqualTo("合成课件.pptx");
    assertThat(db.queryForObject("SELECT text_content FROM slides WHERE id=?", String.class, slide))
        .isNull();
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM slides WHERE material_id=?", Integer.class, material))
        .isEqualTo(1);
    courses.delete(owner, course, 1, id());
    for (int n = 0;
        n < 100
            && !"DELETED"
                .equals(
                    db.queryForObject(
                        "SELECT status FROM courses WHERE id=?", String.class, course));
        n++) pipeline.runOne();
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM focus_items WHERE course_id=?", Integer.class, course))
        .isZero();
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM slides WHERE material_id=?", Integer.class, material))
        .isZero();
  }
}
