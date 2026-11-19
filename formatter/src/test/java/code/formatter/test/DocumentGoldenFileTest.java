package code.formatter.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.tiger.java.code.formatter.DocumentFormatter;
import org.tiger.java.code.formatter.FormatterConfig;

/**
 * One data-driven test over every {@code cases/documents/<name>/{Input.md,Expected.md}} (or {@code .json}) pair. The
 * expected files were produced by Prettier 3.9 with {@code --print-width 40} (and {@code --prose-wrap always} for
 * Markdown), so the formatter is held to Prettier's output. Each case also gets an idempotency check. To add coverage,
 * add a fixture folder.
 */
class DocumentGoldenFileTest {

  private static final FormatterConfig CONFIG = config();

  private static FormatterConfig config() {
    Properties properties = new Properties();
    properties.setProperty("formats", "all");
    properties.setProperty("indent", "2");
    properties.setProperty("jsonWrapAtColumn", "40");
    properties.setProperty("markdownWrapAtColumn", "40");
    return FormatterConfig.from(properties);
  }

  record DocumentCase(String name, DocumentFormatter.Kind kind, String input, String expected) {
    @Override
    public String toString() {
      return name;
    }
  }

  static List<DocumentCase> cases() throws IOException, URISyntaxException {
    URL root = DocumentGoldenFileTest.class.getResource("/cases/documents");
    assertNotNull(root, "cases/documents not found on the test classpath");

    // The resources may be a directory or packed into a jar, as Jenesis does when it runs the tests.
    FileSystem owned = null;
    Path directory;

    try {
      directory = Path.of(root.toURI());
    } catch (FileSystemNotFoundException e) {
      owned = FileSystems.newFileSystem(root.toURI(), Map.of());
      directory = Path.of(root.toURI());
    }

    try (var dirs = Files.list(directory)) {
      return dirs.filter(Files::isDirectory).map(DocumentGoldenFileTest::readCase)
        .sorted(Comparator.comparing(DocumentCase::name)).toList();
    } finally {
      if (owned != null) {
        owned.close();
      }
    }
  }

  private static DocumentCase readCase(Path dir) {
    try (var files = Files.list(dir)) {
      Path input = files.filter(f -> f.getFileName().toString().startsWith("Input.")).findFirst().orElseThrow();
      String extension = input.getFileName().toString().substring("Input".length());
      DocumentFormatter.Kind kind = DocumentFormatter.kindOf(input);
      String expected = Files.readString(dir.resolve("Expected" + extension), StandardCharsets.UTF_8);
      return new DocumentCase(dir.getFileName().toString(), kind, Files.readString(input, StandardCharsets.UTF_8),
        expected);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void formatsToExpected(DocumentCase testCase) {
    DocumentFormatter.Formatted formatted = assertInstanceOf(DocumentFormatter.Formatted.class,
      DocumentFormatter.format(testCase.kind(), testCase.input(), CONFIG), "expected formatting to succeed");
    assertEquals(testCase.expected(), formatted.text());

    DocumentFormatter.Formatted again = assertInstanceOf(DocumentFormatter.Formatted.class,
      DocumentFormatter.format(testCase.kind(), testCase.expected(), CONFIG));
    assertEquals(testCase.expected(), again.text(), "formatting the expected output again changed it");
  }
}
