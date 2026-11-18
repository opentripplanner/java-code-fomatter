package code.formatter.test;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.Objects;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.tiger.java.code.formatter.FormatterConfig;
import org.tiger.java.code.formatter.SourceFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * One data-driven test over every {@code format-cases/<name>/{Input.java.txt,Expected.java.txt}}
 * pair on the test-resources classpath: growing coverage is adding a fixture directory, not writing
 * code. Each case also gets a free idempotency check - formatting already-formatted code must be a
 * no-op.
 *
 * <p>Fixtures use a {@code .java.txt} extension, not {@code .java}: Jenesis compiles every {@code
 * .java} file under {@code src/test} as real source, so a plain {@code .java} fixture would be
 * compiled as test code instead of copied as a resource.
 *
 * <p>All cases share {@code src/test/java/cases/.jcf/config.properties} ({@code wrapAtColumn=60}), loaded
 * through the same {@link FormatterConfig} a real {@code .jcf/config.properties} would be - a narrower
 * column budget than the 120-column default keeps fixtures short and readable.
 */
class FormatterGoldenFileTest {

  private static final String INPUT_NAME = "Input.java.txt";
  private static final String EXPECTED_NAME = "Expected.java.txt";

  private static final FormatterConfig CONFIG = loadConfig();

  private static FormatterConfig loadConfig() {
    String path = "/cases/.jcf/config.properties";

    try (InputStream in = FormatterGoldenFileTest.class.getResourceAsStream(path)) {
      Objects.requireNonNull(in, path + " resource not found on the test classpath");
      Properties properties = new Properties();
      properties.load(in);
      return FormatterConfig.from(properties);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  record FormatCase(String name, String input, String expected) {
    @Override
    public String toString() {
      return name;
    }
  }

  static List<FormatCase> cases() throws IOException, URISyntaxException {
    URL root = FormatterGoldenFileTest.class.getResource("/cases/format");
    Objects.requireNonNull(root, "format-cases resource directory not found on the test classpath");

    // Test resources may be an exploded directory or packaged into a jar (as Jenesis does when
    // running the suite), so the root's path may need a filesystem opened for it first.
    try (MaybeOwnedFileSystem fs = MaybeOwnedFileSystem.forResource(root.toURI())) {
      Path casesDir = fs.path();

      try (var dirs = Files.list(casesDir)) {
        return dirs.filter(Files::isDirectory)
                    .filter(dir -> Files.exists(dir.resolve(INPUT_NAME)) && Files.exists(dir.resolve(EXPECTED_NAME)))
                    .map(dir -> readCase(dir))
                    .sorted(Comparator.comparing(FormatCase::name))
                    .toList();
      }
    }
  }

  private static FormatCase readCase(Path dir) {
    try {
      String input = Files.readString(dir.resolve(INPUT_NAME), StandardCharsets.UTF_8);
      String expected = Files.readString(dir.resolve(EXPECTED_NAME), StandardCharsets.UTF_8);
      return new FormatCase(dir.getFileName().toString(), input, expected);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void formatsToExpected(FormatCase testCase) throws IOException {
    SourceFormatter.Formatted formatted = assertInstanceOf(SourceFormatter.Formatted.class,
            SourceFormatter.format(testCase.input(), CONFIG), "expected formatting to succeed");
    assertEquals(testCase.expected(), formatted.text(), "formatted output did not match Expected.java.txt");

    // Idempotency: formatting already-formatted code must be a no-op.
    SourceFormatter.Formatted reformatted = assertInstanceOf(SourceFormatter.Formatted.class,
            SourceFormatter.format(testCase.expected(), CONFIG), "expected re-formatting to succeed");
    assertEquals(testCase.expected(), reformatted.text(), "formatting Expected.java.txt again changed it");
  }

  @Test
  void rejectsInvalidSyntax() throws IOException {
    String invalid = "package sample;\n\npublic class Broken(\n";
    assertInstanceOf(SourceFormatter.ValidationFailed.class, SourceFormatter.format(invalid));
  }

  @Test
  void leavesUnwrappableTextBlockLineUntouched() throws IOException {
    String path = "/cases/edge/LongTextBlock.java.txt";

    try (InputStream in = FormatterGoldenFileTest.class.getResourceAsStream(path)) {
      Objects.requireNonNull(in, path + " resource not found on the test classpath");
      String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);

      SourceFormatter.Formatted formatted = assertInstanceOf(SourceFormatter.Formatted.class,
                SourceFormatter.format(source, CONFIG), "expected formatting to succeed");
      assertFalse(formatted.unwrappableLines().isEmpty(),
                "expected the over-length text-block line to be reported as unwrappable");
    }
  }

  /**
   * Wraps a resource {@link Path} that may require its own {@link FileSystem} (a resource inside
   * a jar) - opens one only when needed, and closes only the one it opened.
   */
  private record MaybeOwnedFileSystem(Path path, FileSystem owned) implements AutoCloseable {

    static MaybeOwnedFileSystem forResource(URI uri) throws IOException {
      try {
        return new MaybeOwnedFileSystem(Path.of(uri), null);
      } catch (FileSystemNotFoundException e) {
        FileSystem fs = FileSystems.newFileSystem(uri, Map.of());
        return new MaybeOwnedFileSystem(Path.of(uri), fs);
      }
    }

    @Override
    public void close() throws IOException {
      if (owned != null) {
        owned.close();
      }
    }
  }
}
