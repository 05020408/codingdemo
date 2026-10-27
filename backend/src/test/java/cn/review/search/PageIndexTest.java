package cn.review.search;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PageIndexTest {
  @TempDir Path root;

  @Test
  void chineseEnglishAndSpecialInputStayInPublishedCourseScope() throws Exception {
    var index = new PageIndex(root.toString());
    try {
      index.build(
          "course-a",
          "m1",
          1,
          1,
          List.of(
              new PageIndex.Page("s1", "敏捷开发的四个价值观 Scrum sprint"),
              new PageIndex.Page("s2", "需求分析 用例建模")));
      index.build("course-b", "m2", 1, 1, List.of(new PageIndex.Page("private", "敏捷开发 Scrum")));
      assertThat(index.search("course-a", List.of(PageIndex.scope("m1", 1, 1)), "敏捷开发"))
          .containsExactly("s1");
      assertThat(
              index.search(
                  "course-a",
                  List.of(PageIndex.scope("m1", 1, 1), PageIndex.scope("m2", 1, 1)),
                  "Scrum OR *:*"))
          .containsExactly("s1");
      index.build("course-a", "m1", 2, 2, List.of(new PageIndex.Page("s1", "软件测试 verification")));
      assertThat(index.search("course-a", List.of(PageIndex.scope("m1", 2, 2)), "Scrum")).isEmpty();
      assertThat(index.search("course-a", List.of(PageIndex.scope("m1", 1, 1)), "Scrum"))
          .containsExactly("s1");
      index.removeMaterial("m1");
      assertThat(index.contains(PageIndex.scope("m1", 1, 1))).isFalse();
    } finally {
      index.close();
    }
  }

  @Test
  void reopenPreservesIndexAndEvidenceIsOriginalSubstring() throws Exception {
    var index = new PageIndex(root.toString());
    index.build("c", "m", 1, 1, List.of(new PageIndex.Page("s", "review notes")));
    index.close();
    index = new PageIndex(root.toString());
    try {
      assertThat(index.search("c", List.of(PageIndex.scope("m", 1, 1)), "review"))
          .containsExactly("s");
      String text = "背景".repeat(500) + "敏捷开发" + "后续".repeat(500);
      String snippet = SearchCatalog.snippet(text, List.of("敏捷"));
      assertThat(snippet).hasSize(800).contains("敏捷开发");
      assertThat(text).contains(snippet);
    } finally {
      index.close();
    }
  }
}
