package org.opentripplanner.java.code.formatter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Formatting options, loadable from a {@code .jcf/config.properties} file (see {@link ConfigResolver}): the column at
 * which {@link LineWrapper}/{@link ExpressionWrapper} wrap a line, and the number of spaces per indent level used
 * throughout {@link ScopeReindenter}, {@link LineWrapper} and {@link ExpressionWrapper}.
 */
public record FormatterConfig(int wrapAtColumn, int indent, Set<String> commentRules, Documents documents) {

  /**
   * The settings for the non-Java files. {@code formats} are the file types to format: {@code java}, {@code json} and
   * {@code markdown} (default: only {@code java}, so nothing else is touched until it is asked for). JSON is indented
   * by {@link FormatterConfig#indent()}. {@code preserveProse} keeps the line breaks inside Markdown paragraphs.
   */
  public record Documents(Set<String> formats, int jsonWrapAtColumn, int markdownWrapAtColumn,
    boolean preserveProse) {

    public static final Set<String> FORMATS = Set.of("java", "json", "markdown");
    public static final Documents DEFAULT = new Documents(Set.of("java"), 80, 80, false);

    public Documents {
      for (String format : formats) {
        if (!FORMATS.contains(format)) {
          throw new IllegalArgumentException(
            "unknown format '" + format + "', expected one of " + new TreeSet<>(FORMATS)
          );
        }
      }

      if (jsonWrapAtColumn < 1 || markdownWrapAtColumn < 1) {
        throw new IllegalArgumentException("jsonWrapAtColumn and markdownWrapAtColumn must be at least 1");
      }

      formats = Collections.unmodifiableSet(new TreeSet<>(formats));
    }
  }


  /** The folder holding the formatter's per-project files; see {@link ConfigResolver}. */
  public static final String DIR_NAME = ".jcf";
  /** The config file inside {@link #DIR_NAME}. */
  public static final String FILE_NAME = "config.properties";
  private static final int DEFAULT_WRAP_AT_COLUMN = 120;
  private static final int DEFAULT_INDENT = 4;

  /**
   * The comment rules, in the order they were added: {@code wrap} (wrap Javadoc and {@code ///} lines over the limit,
   * never joining lines), {@code paragraphs} (every paragraph tag form becomes one {@code <p>} line),
   * {@code blank-lines} (a blank line before the first block tag and before headings, blank edges trimmed),
   * {@code indent} (continuation lines of tags and list items), {@code fill} (join lines broken too early and refill
   * each line up to the limit; includes all the rules above) and {@code line-comments} (wrap {@code //} comments).
   */
  public static final Set<String> COMMENT_RULES =
    Set.of("wrap", "paragraphs", "blank-lines", "indent", "fill", "line-comments");

  public static final FormatterConfig DEFAULT = new FormatterConfig(DEFAULT_WRAP_AT_COLUMN, DEFAULT_INDENT);

  /** A config with every comment rule on. */
  public FormatterConfig(int wrapAtColumn, int indent) {
    this(wrapAtColumn, indent, COMMENT_RULES);
  }

  /** A config with the default settings for the non-Java files. */
  public FormatterConfig(int wrapAtColumn, int indent, Set<String> commentRules) {
    this(wrapAtColumn, indent, commentRules, Documents.DEFAULT);
  }

  public FormatterConfig {
    if (wrapAtColumn < 1) {
      throw new IllegalArgumentException("wrapAtColumn must be at least 1, was " + wrapAtColumn);
    }

    if (indent < 1) {
      throw new IllegalArgumentException("indent must be at least 1, was " + indent);
    }

    for (String rule : commentRules) {
      if (!COMMENT_RULES.contains(rule)) {
        throw new IllegalArgumentException(
          "unknown comment rule '" + rule + "', expected one of " + new TreeSet<>(COMMENT_RULES)
        );
      }
    }

    commentRules = Collections.unmodifiableSet(new TreeSet<>(commentRules));
  }

  /** Reads {@code wrapAtColumn}/{@code indent}, falling back to {@link #DEFAULT} for anything unset. */
  public static FormatterConfig from(Properties properties) {
    int wrapAtColumn = intProperty(properties, "wrapAtColumn", DEFAULT.wrapAtColumn());
    int indent = intProperty(properties, "indent", DEFAULT.indent());
    return new FormatterConfig(wrapAtColumn, indent, rulesProperty(properties), documentsProperties(properties));
  }

  /**
   * {@code formats} ({@code all}, or a comma-separated list of {@code java}, {@code json}, {@code markdown}),
   * {@code jsonWrapAtColumn}, {@code markdownWrapAtColumn} and {@code markdownProseWrap} ({@code always} or
   * {@code preserve}).
   */
  private static Documents documentsProperties(Properties properties) {
    String value = properties.getProperty("formats");
    Set<String> formats = Documents.DEFAULT.formats();

    if (value != null && !value.isBlank()) {
      formats = value.strip().equals("all")
        ? Documents.FORMATS
        : new TreeSet<>(Arrays.stream(value.split(",")).map(String::strip).filter(f -> !f.isEmpty()).toList());
    }

    String prose = properties.getProperty("markdownProseWrap", "always").strip();

    if (!prose.equals("always") && !prose.equals("preserve")) {
      throw new IllegalArgumentException("markdownProseWrap must be 'always' or 'preserve', was '" + prose + "'");
    }

    return new Documents(
      formats,
      intProperty(properties, "jsonWrapAtColumn", Documents.DEFAULT.jsonWrapAtColumn()),
      intProperty(properties, "markdownWrapAtColumn", Documents.DEFAULT.markdownWrapAtColumn()),
      prose.equals("preserve")
    );
  }

  /** {@code commentRules}: {@code all} (the default), {@code none}, or a comma-separated list of rule names. */
  private static Set<String> rulesProperty(Properties properties) {
    String value = properties.getProperty("commentRules");

    if (value == null || value.isBlank() || value.strip().equals("all")) {
      return COMMENT_RULES;
    }

    if (value.strip().equals("none")) {
      return Set.of();
    }

    return new TreeSet<>(Arrays.stream(value.split(",")).map(String::strip).filter(r -> !r.isEmpty()).toList());
  }

  private static int intProperty(Properties properties, String key, int defaultValue) {
    String value = properties.getProperty(key);

    if (value == null) {
      return defaultValue;
    }

    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(key + " must be an integer, was '" + value + "'", e);
    }
  }

  public static FormatterConfig load(Path propertiesFile) throws IOException {
    Properties properties = new Properties();

    try (InputStream in = Files.newInputStream(propertiesFile)) {
      properties.load(in);
    }

    return from(properties);
  }
}
