package code.formatter.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiger.java.code.formatter.JavaCodeFomatter;

/** Directory arguments, the content-hash cache and its self-ignoring folder. */
class CliCacheTest {

    @TempDir
    Path repo;

    private static final String MESSY = "class A {\n  int x;\n      void f() {\n    x = 1;\n  }\n}\n";

    @BeforeEach
    void fakeRepository() throws IOException {
        Files.createDirectories(repo.resolve(".git"));
        Files.createDirectories(repo.resolve("src/main"));
        Files.writeString(repo.resolve("src/main/A.java"), MESSY);
    }

    /** Runs the CLI with {@code repo} as the (relative-free) target; returns stderr. */
    private String run(String... options) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] args = new String[options.length + 1];
        System.arraycopy(options, 0, args, 0, options.length);
        args[options.length] = repo.resolve("src").toString();
        JavaCodeFomatter.run(args, new PrintStream(OutputStream.nullOutputStream()), new PrintStream(err, true, StandardCharsets.UTF_8));
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aSecondRunSkipsFilesThatAreAlreadyFormatted() throws IOException {
        String first = run("-w", "-v");
        assertTrue(first.contains("1 formatted"), first);
        String formatted = Files.readString(repo.resolve("src/main/A.java"));
        assertFalse(formatted.equals(MESSY));

        String second = run("-w", "-v");
        assertTrue(second.contains("1 skipped (cached)"), second);
        assertEquals(formatted, Files.readString(repo.resolve("src/main/A.java")));
    }

    @Test
    void aChangedFileIsFormattedAgain() throws IOException {
        run("-w");
        Files.writeString(repo.resolve("src/main/A.java"), MESSY);
        String again = run("-w", "-v");
        assertTrue(again.contains("1 formatted"), again);
    }

    @Test
    void theCacheFolderIgnoresItselfAndNoCacheLeavesNoTrace() throws IOException {
        run("-w", "--no-cache");
        assertFalse(Files.exists(repo.resolve(".jcf")));

        run("-w");
        assertEquals("*\n", Files.readString(repo.resolve(".jcf/cache/.gitignore")));
    }

    @Test
    void aChangedConfigInvalidatesTheCacheEntry() throws IOException {
        run("-w");
        Files.createDirectories(repo.resolve(".jcf"));
        Files.writeString(repo.resolve(".jcf/config.properties"), "indent=2\n");
        String again = run("-w", "-v");
        assertTrue(again.contains("1 formatted"), again);
        assertTrue(Files.readString(repo.resolve("src/main/A.java")).contains("\n  void f() {"));
    }

    @Test
    void aCachedFileStillReportsItsUnwrappableLines() throws IOException {
        String longString = "class B {\n    String s = \"" + "x".repeat(150) + "\";\n}\n";
        Files.writeString(repo.resolve("src/main/B.java"), longString);
        String warning = run().lines().filter(l -> l.contains("B.java") && l.contains("no safe break point")).findFirst()
            .orElseThrow();
        run("-w"); // formats it (the warning stays) and caches the result
        String cached = run("-v");
        assertTrue(cached.contains(warning.substring(warning.indexOf("B.java"))), cached);
        assertTrue(cached.contains("2 skipped (cached)"), cached);
    }

    @Test
    void unchangedFilesAreNotRewritten() throws IOException {
        run("-w");
        Path file = repo.resolve("src/main/A.java");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1_000_000_000L));
        run("-w", "--no-cache");
        assertEquals(1_000_000_000L, Files.getLastModifiedTime(file).toMillis());
    }
}
