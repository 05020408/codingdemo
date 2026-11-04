package cn.review.summary;

import static org.assertj.core.api.Assertions.*;

import cn.review.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class SummaryContentTest {
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
    assertThat(SummaryContent.validate(json, json.writeValueAsString(doc), Set.of("a"), 3000))
        .isEqualTo(doc);
    assertThatThrownBy(
            () ->
                SummaryContent.validate(json, json.writeValueAsString(doc), Set.of("other"), 3000))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> SummaryContent.validate(json, "{\"sections\":[]}", Set.of("a"), 3000))
        .isInstanceOf(ApiException.class);
  }
}
