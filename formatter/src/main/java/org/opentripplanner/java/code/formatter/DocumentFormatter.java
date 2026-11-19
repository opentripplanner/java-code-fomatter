package org.opentripplanner.java.code.formatter;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Formats the non-Java files: JSON ({@link JsonFormatter}) and Markdown ({@link MarkdownFormatter}). Both are
 * conservative. A file they cannot format safely is reported as {@link Skipped} and left as it is.
 */
public final class DocumentFormatter {

  /** The file types handled here; {@link #config()} is the name used in {@code formats} in the config file. */
  public enum Kind {
    JSON("json"),
    MARKDOWN("markdown");

    private final String config;

    Kind(String config) {
      this.config = config;
    }

    public String config() {
      return config;
    }
  }

  /** The result of formatting a document. */
  public sealed interface Result permits Formatted, Skipped {
  }

  public record Formatted(String text) implements Result {
  }

  /** The document was not changed; {@code reason} says why. */
  public record Skipped(String reason) implements Result {
  }

  private DocumentFormatter() {
  }

  /** The kind of document for a file name, or {@code null} if the file is not a document handled here. */
  public static Kind kindOf(Path path) {
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);

    if (name.endsWith(".json")) {
      return Kind.JSON;
    }

    return name.endsWith(".md") || name.endsWith(".markdown") ? Kind.MARKDOWN : null;
  }

  public static Result format(Kind kind, String source, FormatterConfig config) {
    FormatterConfig.Documents documents = config.documents();

    return switch (kind) {
      case JSON -> {
        String text = JsonFormatter.format(source, documents.jsonWrapAtColumn(), config.indent());
        yield text == null ? new Skipped("not supported JSON (a comment in an odd place, several values, ...); left unchanged")
          : new Formatted(text);
      }
      case MARKDOWN -> {
        String text = MarkdownFormatter.format(source, documents.markdownWrapAtColumn(), documents.preserveProse());
        yield text == null ? new Skipped("could not be formatted safely; left unchanged") : new Formatted(text);
      }
    };
  }
}
