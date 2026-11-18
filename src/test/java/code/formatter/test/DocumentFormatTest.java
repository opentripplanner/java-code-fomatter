package code.formatter.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiger.java.code.formatter.DocumentFormatter;
import org.tiger.java.code.formatter.FormatRun;
import org.tiger.java.code.formatter.FormatterConfig;

/** The JSON and Markdown formatters, and how {@code formats} in the config turns them on. */
class DocumentFormatTest {

  private static FormatterConfig config(String... keyValues) {
    Properties properties = new Properties();
    properties.setProperty("formats", "all");
    properties.setProperty("indent", "2");

    for (int i = 0; i < keyValues.length; i += 2) {
      properties.setProperty(keyValues[i], keyValues[i + 1]);
    }

    return FormatterConfig.from(properties);
  }

  private static String json(String source, String... keyValues) {
    return switch (DocumentFormatter.format(DocumentFormatter.Kind.JSON, source, config(keyValues))) {
      case DocumentFormatter.Formatted f -> f.text();
      case DocumentFormatter.Skipped s -> "SKIPPED";
    };
  }

  private static String markdown(String source, String... keyValues) {
    return switch (DocumentFormatter.format(DocumentFormatter.Kind.MARKDOWN, source, config(keyValues))) {
      case DocumentFormatter.Formatted f -> f.text();
      case DocumentFormatter.Skipped s -> "SKIPPED";
    };
  }

  // ---- JSON ----

  @Test
  void jsonThatFitsStaysOnOneLine() {
    assertEquals("{ \"a\": [1, 2, 3], \"b\": null }\n", json("{\"a\":[1,2,3],\"b\":null}"));
  }

  @Test
  void jsonObjectsWithALineBreakAfterTheBraceStayExpanded() {
    assertEquals("{\n  \"a\": 1\n}\n", json("{\n\"a\":1}"));
  }

  @Test
  void jsonBreaksWhatDoesNotFit() {
    String source = "{\"list\":[\"aaaaaaaaaaaaaaaaaaaa\",\"bbbbbbbbbbbbbbbbbbbb\",\"cccccccccccccccccccc\"]}";

    assertEquals("{\n  \"list\": [\n    \"aaaaaaaaaaaaaaaaaaaa\",\n    \"bbbbbbbbbbbbbbbbbbbb\",\n"
      + "    \"cccccccccccccccccccc\"\n  ]\n}\n", json(source, "jsonWrapAtColumn", "40"));
  }

  @Test
  void jsonKeepsOneBlankLineBetweenMembersAndNeverChangesValues() {
    assertEquals("{\n  \"a\": 1.50,\n\n  \"b\": \"\\u00e9\"\n}\n", json("{\n\"a\":1.50,\n\n\n\"b\":\"\\u00e9\"}"));
  }

  @Test
  void jsonFillsNumberArrays() {
    String numbers = "[" + String.join(",", java.util.Collections.nCopies(30, "12345")) + "]";
    String formatted = json(numbers, "jsonWrapAtColumn", "40");

    assertTrue(formatted.startsWith("[\n  12345, 12345, 12345, 12345, 12345,\n"), formatted);
    assertEquals(formatted, json(formatted, "jsonWrapAtColumn", "40"));
  }

  @Test
  void jsonCommentsStayWhereTheyAre() {
    String source = "// top\n{ // after brace\n  // lead a\n  \"a\": 1, // trail a\n\n  /* lead b */ \"b\": [1, 2], /* trail b */\n"
      + "  /**\n      * doc\n   */\n  \"c\": {}\n  // last\n} // end\n";

    assertEquals("// top\n{\n  // after brace\n  // lead a\n  \"a\": 1, // trail a\n\n  /* lead b */ \"b\": [1, 2] /* trail b */,\n"
      + "  /**\n   * doc\n   */\n  \"c\": {}\n  // last\n} // end\n", json(source));
  }

  @Test
  void jsonTrailingCommasAreDropped() {
    assertEquals("{ \"a\": [1, 2] }\n", json("{\"a\":[1,2,],}"));
  }

  @Test
  void jsonThatCannotBeFormattedIsLeftAlone() {
    assertEquals("SKIPPED", json("{ \"a\": /* odd place */ 1 }"));
    assertEquals("SKIPPED", json("{} {}"));
    assertEquals("SKIPPED", json(""));
  }

  // ---- Markdown ----

  @Test
  void markdownRefillsParagraphsToTheWidth() {
    assertEquals("aaa bbb ccc\nddd eee\n", markdown("aaa\nbbb ccc ddd\neee\n", "markdownWrapAtColumn", "12"));
  }

  @Test
  void markdownPreserveKeepsTheLineBreaks() {
    assertEquals("aaa\nbbb  ccc\n", markdown("aaa\n  bbb  ccc  \n", "markdownProseWrap", "preserve"));
  }

  @Test
  void markdownNormalizesHeadingsBulletsAndBlankLines() {
    assertEquals("# Title\n\n- one\n- two\n\n---\n", markdown("#  Title  #\n* one\n* two\n\n\n\n* * *\n"));
  }

  @Test
  void markdownNumbersOrderedListsLikePrettier() {
    assertEquals("1. a\n1. b\n1. c\n", markdown("1) a\n1) b\n1) c\n"));
    assertEquals("1. a\n2. b\n3. c\n", markdown("1. a\n5. b\n9. c\n"));
    assertEquals("3. a\n4. b\n", markdown("3. a\n7. b\n"));
  }

  @Test
  void markdownIndentsNestedContentToTheMarker() {
    assertEquals("- a\n  - b\n    more\n", markdown("-   a\n    - b\n      more\n", "markdownProseWrap", "preserve"));
    assertEquals("1. a\n   - b\n", markdown("1. a\n   - b\n"));
  }

  @Test
  void markdownAlignsTables() {
    assertEquals("| a   | bb  |\n| --- | :-: |\n| ccc |  d  |\n", markdown("|a|bb|\n|-|:-:|\n|ccc|d|\n"));
  }

  @Test
  void markdownNeverBreaksCodeLinksOrCopiedBlocks() {
    String copied = "```java\nint   x  = 1 ;\n```\n\n    indented   code\n\n<div>\n  html   stays\n</div>\n\n";
    String paragraph = "see `a  b  c` and [a link text](http://example.com/a%20b \"title here\") ok\n";

    assertEquals(copied + "see `a  b  c` and\n[a link text](http://example.com/a%20b \"title here\")\nok\n",
      markdown(copied + paragraph, "markdownWrapAtColumn", "20"));
  }

  @Test
  void markdownDoesNotStartABlockWhenWrapping() {
    // "-" must not start a line (it would turn into a list), so it moves down with the word before it
    assertEquals("aaaa\nbbbb -\ncccc\n", markdown("aaaa bbbb - cccc\n", "markdownWrapAtColumn", "10"));
  }

  @Test
  void markdownEmphasisUsesUnderscores() {
    assertEquals("some _word_ and **bold** and a*b*c\n", markdown("some *word* and __bold__ and a*b*c\n"));
  }

  @Test
  void markdownFormattingIsIdempotent() {
    String source = "Intro   text that is long enough to wrap over more than one line, at least twice.\n"
      + "* item one\n  continued\n\n  - nested\n* item two\n\n1. first\n1. second\n> quote with long text that wraps around\n";
    String once = markdown(source, "markdownWrapAtColumn", "30");

    assertEquals(once, markdown(once, "markdownWrapAtColumn", "30"));
  }

  // ---- configuration and runs ----

  @Test
  void onlyJavaIsFormattedUnlessTheConfigAsksForMore() {
    FormatterConfig defaults = FormatterConfig.from(new Properties());

    assertEquals(java.util.Set.of("java"), defaults.documents().formats());
    assertEquals(java.util.Set.of("java", "json", "markdown"), config().documents().formats());
    assertThrows(IllegalArgumentException.class, () -> config("formats", "java,yaml"));
    assertThrows(IllegalArgumentException.class, () -> config("markdownProseWrap", "never"));
  }

  @Test
  void aRunFormatsDocumentsOnlyWhereTheConfigListsThem(@TempDir Path repo) throws IOException {
    Files.createDirectories(repo.resolve(".git"));
    Path markdown = Files.writeString(repo.resolve("a.md"), "*  one\n");
    Path json = Files.writeString(repo.resolve("a.json"), "{\"a\":1}");

    FormatRun.Report off = FormatRun.run(List.of(markdown, json), FormatRun.Mode.CHECK, false);
    assertTrue(off.changed().isEmpty(), "documents are off by default");

    Files.createDirectories(repo.resolve(".jcf"));
    Files.writeString(repo.resolve(".jcf/config.properties"), "formats=markdown,json\nindent=2\n");

    FormatRun.Report on = FormatRun.run(List.of(markdown, json), FormatRun.Mode.WRITE, true);
    assertEquals(2, on.changed().size());
    assertEquals("- one\n", Files.readString(markdown));
    assertEquals("{ \"a\": 1 }\n", Files.readString(json));

    FormatRun.Report again = FormatRun.run(List.of(markdown, json), FormatRun.Mode.CHECK, true);
    assertTrue(again.changed().isEmpty());
    assertEquals(2, again.count(FormatRun.Status.CACHED));
  }

  @Test
  void aJsonFileThatCannotBeFormattedIsReportedAndLeftAlone(@TempDir Path repo) throws IOException {
    Files.createDirectories(repo.resolve(".jcf"));
    Files.writeString(repo.resolve(".jcf/config.properties"), "formats=json\n");
    Path file = Files.writeString(repo.resolve("c.json"), "{ \"a\": /* odd */ 1 }\n");

    FormatRun.Report report = FormatRun.run(List.of(file), FormatRun.Mode.WRITE, false);

    assertEquals(FormatRun.Status.UNCHANGED, report.files().get(0).status());
    assertEquals(1, report.files().get(0).warnings().size());
    assertEquals("{ \"a\": /* odd */ 1 }\n", Files.readString(file));
    assertInstanceOf(DocumentFormatter.Skipped.class,
      DocumentFormatter.format(DocumentFormatter.Kind.JSON, "{ \"a\": /* odd */ 1 }", config()));
  }
}
