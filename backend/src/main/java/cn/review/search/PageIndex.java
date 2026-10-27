package cn.review.search;

import jakarta.annotation.PreDestroy;
import java.nio.file.*;
import java.util.*;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.*;
import org.apache.lucene.util.BytesRef;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Immutable material/revision scopes. Database publication decides which scope is visible. */
@Component
public class PageIndex {
  private final Directory directory;
  private final SmartChineseAnalyzer analyzer = new SmartChineseAnalyzer();
  private final IndexWriter writer;
  private final SearcherManager readers;

  public PageIndex(@Value("${app.search.root}") String root) throws Exception {
    Path path = Path.of(root).toAbsolutePath().normalize();
    Files.createDirectories(path);
    if (Files.isSymbolicLink(path))
      throw new IllegalArgumentException("Index root cannot be a link");
    directory = FSDirectory.open(path);
    writer = new IndexWriter(directory, new IndexWriterConfig(analyzer));
    writer.commit();
    readers = new SearcherManager(writer, new SearcherFactory());
  }

  public static String scope(String material, long indexRevision, long textRevision) {
    return material + ":" + indexRevision + ":" + textRevision;
  }

  public synchronized void build(
      String course, String material, long revision, long textRevision, List<Page> pages)
      throws Exception {
    String scope = scope(material, revision, textRevision);
    var documents = new ArrayList<Document>();
    for (Page page : pages) {
      var doc = new Document();
      doc.add(new StringField("scope", scope, Field.Store.NO));
      doc.add(new StringField("course", course, Field.Store.NO));
      doc.add(new StringField("material", material, Field.Store.NO));
      doc.add(new StringField("id", page.id(), Field.Store.YES));
      doc.add(new TextField("body", page.text(), Field.Store.NO));
      documents.add(doc);
    }
    writer.updateDocuments(new Term("scope", scope), documents);
    writer.commit();
    readers.maybeRefreshBlocking();
  }

  public boolean contains(String scope) throws Exception {
    var reader = readers.acquire();
    try {
      return reader.count(new TermQuery(new Term("scope", scope))) > 0;
    } finally {
      readers.release(reader);
    }
  }

  public List<String> terms(String text) throws Exception {
    var terms = new LinkedHashSet<String>();
    try (var stream = analyzer.tokenStream("body", text)) {
      var term = stream.addAttribute(CharTermAttribute.class);
      stream.reset();
      while (stream.incrementToken() && terms.size() < 128) terms.add(term.toString());
      stream.end();
    }
    return List.copyOf(terms);
  }

  public List<String> search(String course, List<String> scopes, String text) throws Exception {
    if (scopes.isEmpty()) return List.of();
    var terms = terms(text);
    if (terms.isEmpty()) return List.of();
    var words = new BooleanQuery.Builder();
    for (String term : terms)
      words.add(new TermQuery(new Term("body", term)), BooleanClause.Occur.SHOULD);
    words.setMinimumNumberShouldMatch(1);
    var query =
        new BooleanQuery.Builder()
            .add(words.build(), BooleanClause.Occur.MUST)
            .add(new TermQuery(new Term("course", course)), BooleanClause.Occur.FILTER)
            .add(
                new TermInSetQuery("scope", scopes.stream().map(BytesRef::new).toList()),
                BooleanClause.Occur.FILTER)
            .build();
    var reader = readers.acquire();
    try {
      var result = new ArrayList<String>();
      for (var hit : reader.search(query, 20).scoreDocs)
        result.add(reader.storedFields().document(hit.doc).get("id"));
      return result;
    } finally {
      readers.release(reader);
    }
  }

  public synchronized void removeMaterial(String material) throws Exception {
    writer.deleteDocuments(new Term("material", material));
    writer.commit();
    readers.maybeRefreshBlocking();
  }

  public synchronized void removeCourse(String course) throws Exception {
    writer.deleteDocuments(new Term("course", course));
    writer.commit();
    readers.maybeRefreshBlocking();
  }

  @PreDestroy
  public void close() throws Exception {
    readers.close();
    writer.close();
    directory.close();
    analyzer.close();
  }

  public record Page(String id, String text) {}
}
