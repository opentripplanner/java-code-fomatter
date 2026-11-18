package org.tiger.java.code.formatter;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * The command line entry point. Arguments are Java files and/or directories (every {@code .java} file under them; in a
 * git repository, the tracked and untracked-but-not-ignored ones). Files are formatted in parallel, each under the
 * config {@link ConfigResolver} finds for it, and a content-hash {@link FormatCache} lets later runs skip every file
 * that is already formatted.
 */
public class JavaCodeFomatter {

  private JavaCodeFomatter() {
  }

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  /** Runs the formatter; returns the process exit code (0 ok, 1 a file failed or has an unwrappable line, 2 usage). */
  public static int run(String[] args, PrintStream out, PrintStream err) {
    boolean write = false;
    boolean changedOnly = false;
    boolean useCache = true;
    boolean verbose = false;
    List<String> targets = new ArrayList<>();

    for (String arg : args) {
      switch (arg) {
        case "-w", "--write" -> write = true;
        case "--changed" -> changedOnly = true;
        case "--no-cache" -> useCache = false;
        case "-v", "--verbose" -> verbose = true;
        case "-h", "--help" -> {
          printUsage(out);
          return 0;
        }
        default -> {
          if (arg.startsWith("-")) {
            err.println("Unknown option: " + arg);
            printUsage(err);
            return 2;
          }

          targets.add(arg);
        }
      }
    }

    if (targets.isEmpty() && !changedOnly) {
      printUsage(err);
      return 2;
    }

    List<Path> files = collectFiles(targets, changedOnly);
    ConfigResolver resolver = new ConfigResolver();
    // One cache per outermost repository root (or, outside a repository, per file's directory).
    Map<Path, FormatCache> caches = useCache ? new ConcurrentHashMap<>() : null;

    final boolean writeFiles = write;
    List<Outcome> outcomes = files.parallelStream()
            .map(file -> process(file, writeFiles, resolver, caches))
            .toList();

    int formatted = 0;
    int unchanged = 0;
    int cached = 0;
    int failed = 0;

    for (Outcome outcome : outcomes) {
      outcome.messages().forEach(err::println);
      out.print(outcome.stdout());

      switch (outcome.kind()) {
        case FORMATTED -> formatted++;
        case UNCHANGED -> unchanged++;
        case CACHED -> cached++;
        case FAILED -> failed++;
      }
    }

    if (caches != null) {
      for (FormatCache cache : caches.values()) {
        try {
          cache.save();
        } catch (IOException e) {
          err.println("warning: could not write the cache: " + e.getMessage());
        }
      }
    }

    if (verbose) {
      err.println(files.size() + " files: " + formatted + " formatted, " + unchanged + " already formatted, "
                + cached + " skipped (cached), " + failed + " failed");
    }

    return outcomes.stream().allMatch(Outcome::ok) ? 0 : 1;
  }

  private enum Kind {
    FORMATTED, UNCHANGED, CACHED, FAILED
  }

  private record Outcome(Kind kind, boolean ok, String stdout, List<String> messages) {
  }

  private static Outcome process(Path path, boolean write, ConfigResolver resolver, Map<Path, FormatCache> caches) {
    byte[] bytes;

    try {
      bytes = Files.readAllBytes(path);
    } catch (IOException e) {
      return failure(path + ": " + e.getMessage());
    }

    String source = new String(bytes, StandardCharsets.UTF_8);

    FormatterConfig config;

    try {
      config = resolver.configFor(path);
    } catch (IOException | IllegalArgumentException e) {
      return failure(path + ": " + e.getMessage());
    }

    FormatCache cache = null;

    if (caches != null) {
      Path directory = path.toAbsolutePath().normalize().getParent();
      Path root = resolver.rootOf(directory);
      cache = caches.computeIfAbsent(root != null ? root : directory, FormatCache::open);
    }

    FormatCache.Key key = cache == null ? null : FormatCache.key(bytes, config);
    int[] cachedLines = cache == null ? null : cache.lookup(key);

    if (cachedLines != null) {
      List<String> messages = new ArrayList<>();

      for (int line : cachedLines) {
        messages.add(unwrappableMessage(path, line, config));
      }

      return new Outcome(Kind.CACHED, cachedLines.length == 0, write ? "" : source, messages);
    }

    SourceFormatter.FormatResult result;

    try {
      result = SourceFormatter.format(source, config);
    } catch (IOException e) {
      return failure(path + ": " + e.getMessage());
    }

    switch (result) {
      case SourceFormatter.ValidationFailed(var diagnostics) -> {
        List<String> messages = new ArrayList<>();

        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
          messages.add(path + ":" + diagnostic.getLineNumber() + ": " + diagnostic.getMessage(null));
        }

        return new Outcome(Kind.FAILED, false, "", messages);
      }
      case SourceFormatter.InternalError(var message) -> {
        return failure(path + ": " + message);
      }
      case SourceFormatter.Formatted(var text, var unwrappableLines) -> {
        List<String> messages = new ArrayList<>();

        for (int line : unwrappableLines) {
          messages.add(unwrappableMessage(path, line, config));
        }

        boolean changed = !text.equals(source);

        if (write && changed) {
          try {
            Files.writeString(path, text, StandardCharsets.UTF_8);
          } catch (IOException e) {
            return failure(path + ": " + e.getMessage());
          }
        }

        if (cache != null) {
          recordIfStable(cache, bytes, text, unwrappableLines, changed, config);
        }

        return new Outcome(changed ? Kind.FORMATTED : Kind.UNCHANGED, unwrappableLines.isEmpty(),
                    write ? "" : text, messages);
      }
    }
  }

  /**
   * Records a fixed point: the source if formatting left it alone, or the formatted text if formatting that again also
   * changes nothing (checked here, so only stable output is cached).
   */
  private static void recordIfStable(FormatCache cache, byte[] sourceBytes, String text,
        List<Integer> unwrappableLines, boolean changed, FormatterConfig config) {
    if (!changed) {
      cache.add(FormatCache.key(sourceBytes, config), toArray(unwrappableLines));
      return;
    }

    try {
      if (SourceFormatter.format(text, config) instanceof SourceFormatter.Formatted again
                && again.text().equals(text)) {
        cache.add(FormatCache.key(text.getBytes(StandardCharsets.UTF_8), config), toArray(again.unwrappableLines()));
      }
    } catch (IOException e) {
      // not cached; the next run just formats it again
    }
  }

  private static int[] toArray(List<Integer> lines) {
    return lines.stream().mapToInt(Integer::intValue).toArray();
  }

  private static String unwrappableMessage(Path path, int line, FormatterConfig config) {
    return path + ":" + line + ": line exceeds " + config.wrapAtColumn()
            + " columns with no safe break point; left unchanged";
  }

  private static Outcome failure(String message) {
    return new Outcome(Kind.FAILED, false, "", List.of(message));
  }

  // ---- file selection ----

  private static List<Path> collectFiles(List<String> targets, boolean changedOnly) {
    Set<Path> files = new LinkedHashSet<>();

    for (String target : targets) {
      Path path = Path.of(target);

      if (Files.isDirectory(path)) {
        files.addAll(javaFilesUnder(path));
      } else {
        files.add(path);
      }
    }

    if (changedOnly) {
      Path here = Path.of("");
      List<String> modified = git(here, "diff", "--name-only", "--relative", "-z", "HEAD");
      List<String> untracked = git(here, "ls-files", "-z", "--others", "--exclude-standard");

      for (List<String> names : java.util.Arrays.asList(modified, untracked)) {
        if (names != null) {
          names.stream().filter(name -> name.endsWith(".java")).map(here::resolve)
                        .filter(Files::isRegularFile).forEach(files::add);
        }
      }
    }

    return new ArrayList<>(files);
  }

  /** Every {@code .java} file under {@code dir}: git's list (tracked + untracked, not ignored) if it is a repo. */
  private static List<Path> javaFilesUnder(Path dir) {
    List<String> names = git(dir, "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", "*.java");

    if (names != null) {
      return names.stream().map(dir::resolve).filter(Files::isRegularFile).toList();
    }

    try (Stream<Path> walk = Files.walk(dir)) {
      return walk
        .filter(Files::isRegularFile)
        .filter(p -> p.toString().endsWith(".java"))
        .filter(
          p -> !hasSegment(dir.relativize(p), ".git") && !hasSegment(dir.relativize(p), FormatterConfig.DIR_NAME)
        )
        .sorted()
        .toList();
    } catch (IOException e) {
      return List.of();
    }
  }

  private static boolean hasSegment(Path path, String name) {
    for (Path segment : path) {
      if (segment.toString().equals(name)) {
        return true;
      }
    }

    return false;
  }

  /**
   * Runs {@code git -C dir args...}; the NUL-separated stdout entries, or {@code null} if git is unavailable or fails.
   */
  private static List<String> git(Path dir, String... args) {
    List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString().isEmpty() ? "." : dir.toString()));
    command.addAll(List.of(args));

    try {
      Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
      byte[] output = process.getInputStream().readAllBytes();

      if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
        return null;
      }

      List<String> entries = new ArrayList<>();

      for (String entry : new String(output, StandardCharsets.UTF_8).split("\0")) {
        if (!entry.isEmpty()) {
          entries.add(entry);
        }
      }

      return entries;
    } catch (IOException e) {
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  private static void printUsage(PrintStream out) {
    out.println("Usage: java -m org.tiger.java.code.formatter [options] <file.java|directory>...");
    out.println("  -w, --write     rewrite files in place (default: print the formatted text)");
    out.println("      --changed   also format the files git reports as changed or untracked");
    out.println("      --no-cache  ignore and do not update the " + FormatterConfig.DIR_NAME + "/cache");
    out.println("  -v, --verbose   print a summary line");
    out.println("Reads " + FormatterConfig.DIR_NAME + "/" + FormatterConfig.FILE_NAME
            + " from each file's directory up to the outermost repository root; nearer files override.");
  }
}
