package code.formatter.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentripplanner.java.code.formatter.ConfigResolver;
import org.opentripplanner.java.code.formatter.FormatterConfig;

/** The per-file, layered lookup of {@code .jcf/config.properties}. */
class ConfigResolverTest {

  @TempDir
  Path root;

  private void config(Path dir, String content) throws IOException {
    Files.createDirectories(dir.resolve(".jcf"));
    Files.writeString(dir.resolve(".jcf/config.properties"), content);
  }

  private Path file(Path dir) throws IOException {
    Files.createDirectories(dir);
    return Files.writeString(dir.resolve("A.java"), "class A {}");
  }

  @Test
  void nearerConfigOverridesOnlyItsKeysAndInheritsTheRest() throws IOException {
    Files.createDirectories(root.resolve(".git"));
    config(root, "wrapAtColumn=100\nindent=2\n");
    config(root.resolve("module"), "wrapAtColumn=80\n");
    ConfigResolver resolver = new ConfigResolver();

    assertEquals(new FormatterConfig(100, 2), resolver.configFor(file(root.resolve("src/pkg"))));
    assertEquals(new FormatterConfig(80, 2), resolver.configFor(file(root.resolve("module/src/pkg"))));
  }

  @Test
  void anUnconfiguredRepositoryUsesTheDefaults() throws IOException {
    Files.createDirectories(root.resolve(".git"));
    assertEquals(FormatterConfig.DEFAULT, new ConfigResolver().configFor(file(root.resolve("src"))));
  }

  @Test
  void aNestedRepositoryOrSubmoduleInheritsTheOutermostRootAndCanOverrideIt() throws IOException {
    Files.createDirectories(root.resolve(".git"));
    config(root, "wrapAtColumn=100\nindent=2\n");
    Path submodule = root.resolve("libs/sub");
    Files.createDirectories(submodule);
    // a submodule's .git is a file
    Files.writeString(submodule.resolve(".git"), "gitdir: ../../.git/modules/sub\n");
    ConfigResolver resolver = new ConfigResolver();

    assertEquals(new FormatterConfig(100, 2), resolver.configFor(file(submodule.resolve("src"))));
    config(submodule, "indent=8\n");
    assertEquals(new FormatterConfig(100, 8), new ConfigResolver().configFor(file(submodule.resolve("src"))));
    assertEquals(root, resolver.rootOf(submodule));
  }

  @Test
  void outsideAnyRepositoryOnlyTheFilesOwnDirectoryCounts() throws IOException {
    // No .git anywhere under this temp dir: a config in a parent directory must not apply.
    config(root, "wrapAtColumn=50\n");
    ConfigResolver resolver = new ConfigResolver();
    assertNull(resolver.rootOf(root));
    assertEquals(FormatterConfig.DEFAULT, resolver.configFor(file(root.resolve("sub"))));
    assertEquals(new FormatterConfig(50, 4), resolver.configFor(file(root)));
  }

  @Test
  void neverCreatesAConfigFolder() throws IOException {
    Files.createDirectories(root.resolve(".git"));
    new ConfigResolver().configFor(file(root.resolve("a/b/c")));

    try (var walk = Files.walk(root)) {
      assertEquals(0, walk.filter(p -> p.getFileName().toString().equals(".jcf")).count());
    }
  }
}
