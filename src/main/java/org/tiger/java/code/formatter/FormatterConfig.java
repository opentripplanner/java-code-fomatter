package org.tiger.java.code.formatter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Formatting options, loadable from a {@code .jcf/config.properties} file (see {@link ConfigResolver}): the column at which
 * {@link LineWrapper}/{@link ExpressionWrapper} wrap a line, and the number of spaces per indent
 * level used throughout {@link ScopeReindenter}, {@link LineWrapper} and {@link ExpressionWrapper}.
 */
public record FormatterConfig(int wrapAtColumn, int indent) {

  /** The folder holding the formatter's per-project files; see {@link ConfigResolver}. */
  public static final String DIR_NAME = ".jcf";
  /** The config file inside {@link #DIR_NAME}. */
  public static final String FILE_NAME = "config.properties";
  private static final int DEFAULT_WRAP_AT_COLUMN = 120;
  private static final int DEFAULT_INDENT = 4;

  public static final FormatterConfig DEFAULT = new FormatterConfig(DEFAULT_WRAP_AT_COLUMN, DEFAULT_INDENT);

  public FormatterConfig {
    if (wrapAtColumn < 1) {
      throw new IllegalArgumentException("wrapAtColumn must be at least 1, was " + wrapAtColumn);
    }

    if (indent < 1) {
      throw new IllegalArgumentException("indent must be at least 1, was " + indent);
    }
  }

  /** Reads {@code wrapAtColumn}/{@code indent}, falling back to {@link #DEFAULT} for anything unset. */
  public static FormatterConfig from(Properties properties) {
    int wrapAtColumn = intProperty(properties, "wrapAtColumn", DEFAULT.wrapAtColumn());
    int indent = intProperty(properties, "indent", DEFAULT.indent());
    return new FormatterConfig(wrapAtColumn, indent);
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
