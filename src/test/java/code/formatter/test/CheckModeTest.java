package code.formatter.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiger.java.code.formatter.FormatRun;
import org.tiger.java.code.formatter.JavaCodeFomatter;

/** {@code --check}, the exit codes, and the structured {@link FormatRun} API behind them. */
class CheckModeTest {

  private static final String MESSY = "class A {\n  int x;\n      void f() {\n    x = 1;\n  }\n}\n";

  @TempDir
  Path repo;

  private Path file;

  @BeforeEach
  void fakeRepository() throws IOException {
    Files.createDirectories(repo.resolve(".git"));
    file = Files.writeString(repo.resolve("A.java"), MESSY);
  }

  private record Outcome(int exit, String out, String err) {
  }

  private Outcome cli(String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int exit = JavaCodeFomatter.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
      new PrintStream(err, true, StandardCharsets.UTF_8));

    return new Outcome(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  @Test
  void checkListsTheFilesThatWouldChangeAndExitsOneWithoutWriting() throws IOException {
    Outcome outcome = cli("--check", file.toString());

    assertEquals(1, outcome.exit());
    assertTrue(outcome.out().contains("would reformat: " + file), outcome.out());
    assertEquals(MESSY, Files.readString(file));
  }

  @Test
  void checkPassesOnceTheFileIsFormatted() {
    assertEquals(0, cli("-w", file.toString()).exit());
    assertEquals(0, cli("--check", file.toString()).exit());
    assertEquals(0, cli("--check", "--no-cache", file.toString()).exit());
  }

  @Test
  void writeAndCheckCannotBeCombined() {
    assertEquals(2, cli("--write", "--check", file.toString()).exit());
  }

  @Test
  void aLineThatCannotBeWrappedIsAWarningNotAFailure() throws IOException {
    Files.writeString(file, "class B {\n    String s = \"" + "x".repeat(150) + "\";\n}\n");

    Outcome outcome = cli("-w", file.toString());

    assertEquals(0, outcome.exit());
    assertTrue(outcome.err().contains("no safe break point"), outcome.err());
    assertEquals(0, cli("--check", file.toString()).exit());
  }

  @Test
  void invalidJavaFailsTheRun() throws IOException {
    Files.writeString(file, "class {");

    assertEquals(1, cli("--check", file.toString()).exit());
  }

  @Test
  void theApiReportsEachFilesStatus() throws IOException {
    FormatRun.Report check = FormatRun.run(List.of(file), FormatRun.Mode.CHECK, false);

    assertEquals(FormatRun.Status.CHANGED, check.files().get(0).status());
    assertEquals(MESSY, Files.readString(file));

    FormatRun.Report print = FormatRun.run(List.of(file), FormatRun.Mode.PRINT, false);

    assertTrue(print.files().get(0).formattedText().contains("    void f() {"), print.files().get(0).formattedText());

    FormatRun.run(List.of(file), FormatRun.Mode.WRITE, true);

    assertEquals(
      FormatRun.Status.CACHED,
      FormatRun.run(List.of(file), FormatRun.Mode.CHECK, true).files().get(0).status()
    );
    assertEquals(
      FormatRun.Status.UNCHANGED,
      FormatRun.run(List.of(file), FormatRun.Mode.CHECK, false).files().get(0).status()
    );
  }
}
