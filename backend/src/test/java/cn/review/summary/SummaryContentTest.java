package cn.review.summary;

import static org.assertj.core.api.Assertions.*;

import cn.review.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class SummaryContentTest {
  @Test
  void ordinaryPagesStayWholeAndOversizedPagesPreferParagraphBoundaries() {
    var parts = SummaryContent.split(List.of(
        new SummaryContent.Page("a", 1, 1L, false, "甲".repeat(6000)),
        new SummaryContent.Page("b", 2, 1L, false, "乙".repeat(6000)),
        new SummaryContent.Page("c", 3, 1L, false, "丙".repeat(3000))));
    assertThat(parts).hasSize(2);
    assertThat(parts.get(0)).hasSize(1);
    assertThat(parts.get(1).stream().map(SummaryContent.Page::slideId)).containsExactly("b", "c");
    String longPage = "甲".repeat(7000) + "\n" + "乙".repeat(5000);
    var longParts = SummaryContent.split(List.of(new SummaryContent.Page("d", 4, 1L, false, longPage)));
    assertThat(longParts).hasSize(2);
    assertThat(longParts.get(0).stream().map(SummaryContent.Page::text).reduce("", String::concat))
        .isEqualTo("甲".repeat(7000) + "\n");
    assertThat(longParts.stream().flatMap(List::stream).map(SummaryContent.Page::text).reduce("", String::concat)).isEqualTo(longPage);
  }

  @Test
  void splittingPreservesUnicodeAndAllNonemptyPages() {
    String text = "文".repeat(9999) + "😀" + "续文";
    var chunks =
        SummaryContent.split(
            List.of(
                new SummaryContent.Page("a", 1, 1L, false, text),
                new SummaryContent.Page("b", 2, 1L, true, "第二页")));
    String joined =
        chunks.stream()
            .flatMap(List::stream)
            .filter(p -> p.slideId().equals("a"))
            .map(SummaryContent.Page::text)
            .reduce("", String::concat);
    assertThat(joined).isEqualTo(text);
    assertThat(chunks)
        .allSatisfy(
            part -> {
              assertThat(part.stream().mapToInt(p -> p.text().length()).sum())
                  .isLessThanOrEqualTo(SummaryContent.PART_LIMIT);
              for (var p : part) assertThat(Character.isLowSurrogate(p.text().charAt(0))).isFalse();
            });
    assertThat(chunks.get(1).get(1).hidden()).isTrue();
  }

  @Test
  void oversizedOrEmptyDeckHasExplicitBoundary() {
    assertThatThrownBy(
            () ->
                SummaryContent.split(
                    List.of(new SummaryContent.Page("a", 1, 1L, false, "文".repeat(120001)))))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> SummaryContent.split(List.of(new SummaryContent.Page("a", 1, 1L, false, ""))))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void outputMustHaveKnownSourcesAndCompleteStructure() throws Exception {
    var json = new ObjectMapper();
    var doc =
        new SummaryContent.Document(
            "题目",
            "概述",
            List.of(new SummaryContent.Section("主题", "连贯内容", List.of("a"))),
            List.of("复习"),
            "限制");
    assertThat(SummaryContent.validate(json, json.writeValueAsString(doc), Set.of("a")))
        .isEqualTo(doc);
    assertThatThrownBy(
            () ->
                SummaryContent.validate(json, json.writeValueAsString(doc), Set.of("other")))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("第 1 个主题引用了本次未提供的页面");
    assertThatThrownBy(() -> SummaryContent.validate(json, "{\"sections\":[]}", Set.of("a")))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("标题字段缺失或不是文本");
  }

  @Test
  void longProseAndManyGenuineSourcesAreNotRejectedByEditorialLengthOrCount() throws Exception {
    var json = new ObjectMapper();
    var ids = java.util.stream.IntStream.rangeClosed(1, 40).mapToObj(i -> "page-" + i).toList();
    var pages = java.util.stream.IntStream.rangeClosed(1, 40)
        .mapToObj(i -> new SummaryContent.Page("page-" + i, i, 1L, false, "来源"))
        .toList();
    String body = "关键条件与过程。".repeat(1600);
    var sections = java.util.stream.IntStream.rangeClosed(1, 21)
        .mapToObj(i -> new SummaryContent.Section("知识主题".repeat(60), i == 1 ? body : "主题正文", ids))
        .toList();
    var checklist = java.util.stream.IntStream.rangeClosed(1, 25)
        .mapToObj(i -> "复习项目 " + i + "：" + "掌握条件".repeat(260)).toList();
    var doc = new SummaryContent.Document("知识概览".repeat(60), "整体联系".repeat(2600),
        sections, checklist, "材料限制".repeat(600));
    var accepted = SummaryContent.validate(json, json.writeValueAsString(doc), new HashSet<>(ids));
    assertThat(accepted).isEqualTo(doc);
    String md = SummaryContent.markdown(accepted, pages, "material", 0);
    assertThat(md.length()).isGreaterThan(12000);
    assertThat(md).contains(body, checklist.get(24), "/read?page=40");
  }

  @Test
  void repeatedValidSourcesAreNormalizedWithoutHidingAnInvalidSource() throws Exception {
    var json = new ObjectMapper();
    var doc = new SummaryContent.Document("题目", "概述",
        List.of(new SummaryContent.Section("主题", "正文", List.of("a", "a"))), List.of(), "");
    assertThat(SummaryContent.validate(json, json.writeValueAsString(doc), Set.of("a"))
        .sections().get(0).slideIds()).containsExactly("a");
    var forged = new SummaryContent.Document("题目", "概述",
        List.of(new SummaryContent.Section("主题", "正文", List.of("a", "a", "forged"))), List.of(), "");
    assertThatThrownBy(() -> SummaryContent.validate(json, json.writeValueAsString(forged), Set.of("a")))
        .isInstanceOf(ApiException.class).hasMessageContaining("引用了本次未提供的页面");
  }

  @Test
  void malformedJsonAndWrongFieldTypesExplainWhyTheyAreRejected() {
    var json = new ObjectMapper();
    assertThatThrownBy(() -> SummaryContent.validate(json, "{\"title\":", Set.of("a")))
        .isInstanceOf(ApiException.class).hasMessageContaining("不是完整有效的 JSON");
    assertThatThrownBy(() -> SummaryContent.validate(json, "{} {}", Set.of("a")))
        .isInstanceOf(ApiException.class).hasMessageContaining("不是完整有效的 JSON");
    assertThatThrownBy(() -> SummaryContent.validate(json,
        "{\"title\":12,\"overview\":\"概述\"}", Set.of("a")))
        .isInstanceOf(ApiException.class).hasMessageContaining("标题字段缺失或不是文本");
  }
}
