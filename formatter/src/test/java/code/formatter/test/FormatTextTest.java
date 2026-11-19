package code.formatter.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiger.java.code.formatter.FormatRun;

/** {@link FormatRun#formatText} and {@link FormatRun#handles}: formatting an editor buffer, with the file's config. */
class FormatTextTest {

  private static final String MESSY = "class A {\n  int x;\n      void f() {\n    x = 1;\n  }\n}\n";

  @TempDir
  Path repo;

  @BeforeEach
  void fakeRepository() throws IOException {
    Files.createDirectories(repo.resolve(".git"));
  }

  @Test
  void formatsTextWithoutTouchingTheFile() throws IOException {
    Path file = Files.writeString(repo.resolve("A.java"), "unchanged on disk");
    FormatRun.TextResult result = FormatRun.formatText(file, MESSY);

    assertEquals(FormatRun.Status.CHANGED, result.status());
    assertTrue(result.text().contains("\n\n"), result.text());
    assertEquals("unchanged on disk", Files.readString(file));
  }

  @Test
  void formattedTextIsUnchanged() {
    Path file = repo.resolve("A.java");
    String neat = FormatRun.formatText(file, MESSY).text();
    FormatRun.TextResult result = FormatRun.formatText(file, neat);

    assertEquals(FormatRun.Status.UNCHANGED, result.status());
    assertEquals(neat, result.text());
  }

  @Test
  void invalidJavaFailsWithoutText() {
    FormatRun.TextResult result = FormatRun.formatText(repo.resolve("A.java"), "class A {");

    assertEquals(FormatRun.Status.FAILED, result.status());
    assertNull(result.text());
    assertFalse(result.errors().isEmpty());
  }

  @Test
  void documentsAreFormattedOnlyWhenTheConfigEnablesThem() throws IOException {
    Path json = repo.resolve("a.json");

    assertFalse(FormatRun.handles(json));
    assertEquals(FormatRun.Status.UNCHANGED, FormatRun.formatText(json, "{\"a\":1}").status());

    Files.createDirectories(repo.resolve(".jcf"));
    Files.writeString(repo.resolve(".jcf/config.properties"), "formats=all\n");

    assertTrue(FormatRun.handles(json));
    assertEquals("{ \"a\": 1 }\n", FormatRun.formatText(json, "{\"a\":1}").text());
  }

  @Test
  void javaFilesAreAlwaysHandledAndOtherFilesNever() {
    assertTrue(FormatRun.handles(repo.resolve("A.java")));
    assertFalse(FormatRun.handles(repo.resolve("A.txt")));
  }

  @Test
  void aTextBlockWithWhitespaceOnlyLinesIsFormattedNotRejected() {
    // an IDE's formatter leaves whitespace-only lines and trailing spaces in text blocks; the compiler ignores them
    String source = "class A {\n  String t = \"\"\"\n      one   \n        \n      two\n      \"\"\";\n}\n";
    FormatRun.TextResult result = FormatRun.formatText(repo.resolve("A.java"), source);

    assertNotEquals(FormatRun.Status.FAILED, result.status(), String.valueOf(result.errors()));
  }
}
