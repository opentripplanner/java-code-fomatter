package org.opentripplanner.java.code.formatter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wraps own-line {@code //} comments that are longer than the column limit: the line is broken at spaces and the rest
 * continues on a new {@code //} line with the same indent. Lines are only ever split, never joined, because a
 * hand-broken {@code //} block is often deliberate.
 * <p>
 * A continuation keeps the text's indentation after the {@code //}; the continuation of a list item ({@code - text},
 * {@code 1. text}) is aligned under its text. These are left alone: lines that look like code (ending in {@code ;},
 * {@code {} or {@code }}), lines with two or more spaces in a row (alignment, diagrams), tool and region markers
 * ({@code NOSONAR}, {@code noinspection}, {@code @formatter:off}, {@code region}, ...), a line that is a single token
 * (a URL), and {@code ///} doc comments (see {@link JavadocFormatter}). A word longer than the limit stands alone on a
 * line.
 */
final class LineCommentWrapper {

  private static final Pattern TOOL_OR_REGION = Pattern.compile(
    "^(NOSONAR|NOPMD|NOCHECKSTYLE|CHECKSTYLE|noinspection|nosemgrep|lgtm|nolint|\\$NON-NLS|@formatter|region|endregion|"
      + "<editor-fold|</editor-fold|language=)",
    Pattern.CASE_INSENSITIVE
  );
  private static final Pattern LIST_ITEM = Pattern.compile("^([-*+]|\\d+[.)])\\s+\\S.*");

  private LineCommentWrapper() {
  }

  static String format(String source, int wrapAtColumn) {
    LiteralScanner.Scan scan = LiteralScanner.scan(source);
    String eol = source.contains("\r\n") ? "\r\n" : "\n";
    StringBuilder out = null;
    int copied = 0;

    for (LiteralScanner.VerbatimSpan span : scan.spans()) {
      if (span.kind() != LiteralScanner.Kind.LINE_COMMENT || source.startsWith("///", span.start())) {
        continue;
      }

      int lineStart = source.lastIndexOf('\n', span.start() - 1) + 1;
      String indent = source.substring(lineStart, span.start());

      if (!indent.isBlank()) {
        // a trailing comment
        continue;
      }

      String comment = source.substring(span.start(), span.end()).stripTrailing();

      if (indent.length() + comment.length() <= wrapAtColumn) {
        continue;
      }

      String wrapped = wrap(indent, comment, wrapAtColumn, eol);

      if (wrapped != null) {
        if (out == null) {
          out = new StringBuilder(source.length() + 256);
        }

        out.append(source, copied, span.start()).append(wrapped);
        copied = span.start() + comment.length();
      }
    }

    return out == null ? source : out.append(source, copied, source.length()).toString();
  }

  /** The comment split over several {@code //} lines, or {@code null} to leave it as it is. */
  private static String wrap(String indent, String comment, int wrapAtColumn, String eol) {
    String text = comment.substring(2);
    String leading = text.substring(0, text.length() - text.stripLeading().length());
    String body = text.strip();

    if (body.isEmpty() || !body.contains(" ") || body.contains("  ") || TOOL_OR_REGION.matcher(body).find()
      || body.endsWith(";") || body.endsWith("{") || body.endsWith("}")) {
      return null;
    }

    Matcher item = LIST_ITEM.matcher(body);
    String hanging = leading;

    if (item.matches()) {
      hanging = leading + " ".repeat(body.indexOf(' ') + 1);
    }

    String firstPrefix = "//" + leading;
    String restPrefix = "//" + hanging;
    List<String> lines = new ArrayList<>();
    StringBuilder current = new StringBuilder(firstPrefix);
    boolean empty = true;

    for (String word : body.split(" ")) {
      if (!empty && indent.length() + current.length() + 1 + word.length() > wrapAtColumn) {
        lines.add(current.toString());
        current = new StringBuilder(restPrefix);
        empty = true;
      }

      if (!empty) {
        current.append(' ');
      }

      current.append(word);
      empty = false;
    }

    lines.add(current.toString());

    return lines.size() == 1 ? null : String.join(eol + indent, lines);
  }
}
