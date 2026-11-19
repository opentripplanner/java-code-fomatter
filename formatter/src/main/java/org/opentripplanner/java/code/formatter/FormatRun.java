package org.opentripplanner.java.code.formatter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * Formats a set of files and reports what happened to each: the API behind the command line and the Maven plugin. Files
 * are formatted in parallel, each under the config {@link ConfigResolver} finds for it, and a content-hash
 * {@link FormatCache} lets later runs skip every file that is already formatted.
 */
public final class FormatRun {

  /** What to do with the formatted text. */
  public enum Mode {
    /** Return the formatted text in the result and change nothing. */
    PRINT,
    /** Rewrite the files that change. */
    WRITE,
    /** Only report which files would change; keep no text and write nothing. */
    CHECK
  }

  /** What happened to one file. */
  public enum Status {
    /** Formatting changes the file (written in {@link Mode#WRITE}). */
    CHANGED,
    /** The file is already formatted. */
    UNCHANGED,
    /** The file is already formatted, as the cache knows from an earlier run. */
    CACHED,
    /** The file could not be formatted: it is not valid Java, or could not be read or written. */
    FAILED
  }

  /**
   * The outcome for one file. {@code errors} explain a {@link Status#FAILED}; {@code warnings} are lines the formatter
   * could not bring under the limit (they never fail a run); {@code formattedText} is set only in {@link Mode#PRINT}.
   */
  public record FileResult(Path path, Status status, List<String> errors, List<String> warnings,
    String formattedText) {
  }

  /**
   * All the results, in the order of the files given, and notices about the run itself (a cache that could not be
   * written).
   */
  public record Report(List<FileResult> files, List<String> notices) {

    public List<FileResult> changed() {
      return files.stream().filter(f -> f.status() == Status.CHANGED).toList();
    }

    public List<FileResult> failed() {
      return files.stream().filter(f -> f.status() == Status.FAILED).toList();
    }

    public long count(Status status) {
      return files.stream().filter(f -> f.status() == status).count();
    }
  }

  private FormatRun() {
  }

  public static Report run(List<Path> files, Mode mode, boolean useCache) {
    ConfigResolver resolver = new ConfigResolver();
    // One cache per outermost repository root (or, outside a repository, per file's directory).
    Map<Path, FormatCache> caches = useCache ? new ConcurrentHashMap<>() : null;
    List<FileResult> results = files.parallelStream().map(file -> process(file, mode, resolver, caches)).toList();
    List<String> notices = new ArrayList<>();

    if (caches != null) {
      for (FormatCache cache : caches.values()) {
        try {
          cache.save();
        } catch (IOException e) {
          notices.add("could not write the cache: " + e.getMessage());
        }
      }
    }

    return new Report(results, notices);
  }

  private static FileResult process(Path path, Mode mode, ConfigResolver resolver, Map<Path, FormatCache> caches) {
    byte[] bytes;

    try {
      bytes = Files.readAllBytes(path);
    } catch (IOException e) {
      return failure(path, path + ": " + e.getMessage());
    }

    String source = new String(bytes, StandardCharsets.UTF_8);
    FormatterConfig config;

    try {
      config = resolver.configFor(path);
    } catch (IOException | IllegalArgumentException e) {
      return failure(path, path + ": " + e.getMessage());
    }

    DocumentFormatter.Kind kind = DocumentFormatter.kindOf(path);

    if (kind != null && !config.documents().formats().contains(kind.config())) {
      return new FileResult(path, Status.UNCHANGED, List.of(), List.of(), null);
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
      List<String> warnings = new ArrayList<>();

      for (int line : cachedLines) {
        warnings.add(unwrappableMessage(path, line, config));
      }

      return new FileResult(path, Status.CACHED, List.of(), warnings, mode == Mode.PRINT ? source : null);
    }

    if (kind != null) {
      return processDocument(path, kind, source, bytes, config, mode, cache, key);
    }

    SourceFormatter.FormatResult result;

    try {
      result = SourceFormatter.format(source, config);
    } catch (IOException e) {
      return failure(path, path + ": " + e.getMessage());
    }

    switch (result) {
      case SourceFormatter.ValidationFailed(var diagnostics) -> {
        List<String> errors = new ArrayList<>();

        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
          errors.add(path + ":" + diagnostic.getLineNumber() + ": " + diagnostic.getMessage(null));
        }

        return new FileResult(path, Status.FAILED, errors, List.of(), null);
      }
      case SourceFormatter.InternalError(var message) -> {
        return failure(path, path + ": " + message);
      }
      case SourceFormatter.Formatted(var text, var unwrappableLines) -> {
        List<String> warnings = new ArrayList<>();

        for (int line : unwrappableLines) {
          warnings.add(unwrappableMessage(path, line, config));
        }

        boolean changed = !text.equals(source);

        if (mode == Mode.WRITE && changed) {
          try {
            Files.writeString(path, text, StandardCharsets.UTF_8);
          } catch (IOException e) {
            return failure(path, path + ": " + e.getMessage());
          }
        }

        if (cache != null && (mode != Mode.CHECK || !changed)) {
          recordIfStable(cache, bytes, text, unwrappableLines, changed, config);
        }

        return new FileResult(path, changed ? Status.CHANGED : Status.UNCHANGED, List.of(), warnings,
          mode == Mode.PRINT ? text : null);
      }
    }
  }

  /** Formats a JSON or Markdown file; one that cannot be formatted safely is left alone and reported as a warning. */
  private static FileResult processDocument(Path path, DocumentFormatter.Kind kind, String source, byte[] bytes,
    FormatterConfig config, Mode mode, FormatCache cache, FormatCache.Key key) {
    DocumentFormatter.Result result = DocumentFormatter.format(kind, source, config);

    if (result instanceof DocumentFormatter.Skipped(var reason)) {
      return new FileResult(path, Status.UNCHANGED, List.of(), List.of(path + ": " + reason), null);
    }

    String text = ((DocumentFormatter.Formatted) result).text();
    boolean changed = !text.equals(source);

    if (mode == Mode.WRITE && changed) {
      try {
        Files.writeString(path, text, StandardCharsets.UTF_8);
      } catch (IOException e) {
        return failure(path, path + ": " + e.getMessage());
      }
    }

    if (cache != null && (mode != Mode.CHECK || !changed)) {
      if (!changed) {
        cache.add(key, new int[0]);
      } else if (DocumentFormatter.format(kind, text, config) instanceof DocumentFormatter.Formatted(var again)
        && again.equals(text)) {
        cache.add(FormatCache.key(text.getBytes(StandardCharsets.UTF_8), config), new int[0]);
      }
    }

    return new FileResult(path, changed ? Status.CHANGED : Status.UNCHANGED, List.of(), List.of(),
      mode == Mode.PRINT ? text : null);
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

  private static FileResult failure(Path path, String message) {
    return new FileResult(path, Status.FAILED, List.of(message), List.of(), null);
  }

  // ---- file selection ----

  /** Every {@code .java} file under {@code dir}: git's list (tracked + untracked, not ignored) if it is a repo. */
  public static List<Path> javaFilesUnder(Path dir) {
    return filesUnder(dir, name -> name.endsWith(".java"));
  }

  /**
   * Every file under {@code dir} the formatter can handle: Java, JSON and Markdown files. Which of them are formatted
   * is decided per file by {@code formats} in the config; see {@link FormatterConfig.Documents}.
   */
  public static List<Path> formattableFilesUnder(Path dir) {
    return filesUnder(dir, FormatRun::isFormattable);
  }

  private static boolean isFormattable(String name) {
    return name.endsWith(".java") || DocumentFormatter.kindOf(Path.of(name)) != null;
  }

  private static List<Path> filesUnder(Path dir, java.util.function.Predicate<String> wanted) {
    List<String> names = git(dir, "ls-files", "-z", "--cached", "--others", "--exclude-standard");

    if (names != null) {
      return names.stream().filter(wanted).map(dir::resolve).filter(Files::isRegularFile).toList();
    }

    try (Stream<Path> walk = Files.walk(dir)) {
      return walk
        .filter(Files::isRegularFile)
        .filter(p -> wanted.test(p.toString()))
        .filter(
          p -> !hasSegment(dir.relativize(p), ".git") && !hasSegment(dir.relativize(p), FormatterConfig.DIR_NAME)
        )
        .sorted()
        .toList();
    } catch (IOException e) {
      return List.of();
    }
  }

  /** The {@code .java} files under {@code dir} that git reports as modified since HEAD or as new and not ignored. */
  public static List<Path> changedJavaFiles(Path dir) {
    return changedFiles(dir, name -> name.endsWith(".java"));
  }

  /** Like {@link #changedJavaFiles}, for every file the formatter can handle (see {@link #formattableFilesUnder}). */
  public static List<Path> changedFormattableFiles(Path dir) {
    return changedFiles(dir, FormatRun::isFormattable);
  }

  private static List<Path> changedFiles(Path dir, java.util.function.Predicate<String> wanted) {
    List<Path> files = new ArrayList<>();
    List<String> modified = git(dir, "diff", "--name-only", "--relative", "-z", "HEAD");
    List<String> untracked = git(dir, "ls-files", "-z", "--others", "--exclude-standard");

    for (List<String> names : java.util.Arrays.asList(modified, untracked)) {
      if (names != null) {
        names.stream().filter(wanted).map(dir::resolve).filter(Files::isRegularFile)
          .filter(p -> !files.contains(p)).forEach(files::add);
      }
    }

    return files;
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
}
