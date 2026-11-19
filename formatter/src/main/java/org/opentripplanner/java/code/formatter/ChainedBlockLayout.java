package org.opentripplanner.java.code.formatter;

import java.util.List;

/**
 * Puts the continuation of a chained block on its own line: {@code } else {}, {@code } else if (x) {}, {@code } catch
 * (E e) {} and {@code } finally {} become the {@code }} on one line and {@code else ...}, {@code catch ...} or
 * {@code finally ...} on the next, at the same indent. A {@code }} with a comment, or anything else, between it and the
 * keyword is left as written. The indent of the new line is fixed later by {@link ScopeReindenter}.
 */
final class ChainedBlockLayout {

  private static final List<String> KEYWORDS = List.of("else", "catch", "finally");

  private ChainedBlockLayout() {
  }

  static String format(String source) {
    LiteralScanner.Scan scan = LiteralScanner.scan(source);
    String newline = source.contains("\r\n") ? "\r\n" : "\n";
    StringBuilder out = new StringBuilder(source.length() + 64);
    int copied = 0;
    int n = source.length();

    for (int i = source.indexOf('}'); i >= 0 && i < n; i = source.indexOf('}', i + 1)) {
      int gapEnd = i + 1;

      while (gapEnd < n && (source.charAt(gapEnd) == ' ' || source.charAt(gapEnd) == '\t')) {
        gapEnd++;
      }

      if (!startsKeyword(source, gapEnd) || scan.isVerbatim(i)) {
        continue;
      }

      int lineStart = source.lastIndexOf('\n', i) + 1;
      int indentEnd = lineStart;

      while (indentEnd < i && (source.charAt(indentEnd) == ' ' || source.charAt(indentEnd) == '\t')) {
        indentEnd++;
      }

      out.append(source, copied, i + 1).append(newline).append(source, lineStart, indentEnd);
      copied = gapEnd;
    }

    return copied == 0 ? source : out.append(source, copied, n).toString();
  }

  /** Whether {@code else}, {@code catch} or {@code finally} starts at {@code at}, as a whole word. */
  private static boolean startsKeyword(String source, int at) {
    for (String keyword : KEYWORDS) {
      int end = at + keyword.length();

      if (source.startsWith(keyword, at) && (end >= source.length() || !Character.isJavaIdentifierPart(source.charAt(end)))) {
        return true;
      }
    }

    return false;
  }
}
