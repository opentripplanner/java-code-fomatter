package org.opentripplanner.java.code.formatter;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-rolled, character-level scan of Java source that locates every comment, character literal, string literal and
 * text block, without building a full token stream or AST. {@link ScopeReindenter} and {@link LineWrapper} use the
 * result to avoid ever reindenting or splitting inside one of these "verbatim" spans.
 */
final class LiteralScanner {

  enum Kind {
    LINE_COMMENT,
    BLOCK_COMMENT,
    CHAR_LITERAL,
    STRING_LITERAL,
    TEXT_BLOCK
  }

  record VerbatimSpan(int start, int end, Kind kind) {
  }

  record Scan(String source, List<VerbatimSpan> spans) {

    /** The verbatim span containing {@code offset}, or {@code null} if it isn't inside one. */
    VerbatimSpan spanAt(int offset) {
      // Spans are sorted and non-overlapping; a linear scan from the front is simple and, since
      // both consumers walk the source left to right exactly once, each lookup starts near where
      // the previous one left off in practice. Correctness over micro-optimization here.
      for (VerbatimSpan span : spans) {
        if (offset < span.start()) {
          return null;
        }

        if (offset < span.end()) {
          return span;
        }
      }

      return null;
    }

    Kind kindAt(int offset) {
      VerbatimSpan span = spanAt(offset);
      return span == null ? null : span.kind();
    }

    boolean isVerbatim(int offset) {
      return spanAt(offset) != null;
    }
  }

  private LiteralScanner() {
  }

  static Scan scan(String source) {
    List<VerbatimSpan> spans = new ArrayList<>();
    int n = source.length();
    int i = 0;

    while (i < n) {
      char c = source.charAt(i);

      if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
        int end = source.indexOf('\n', i);
        end = end == -1 ? n : end;
        spans.add(new VerbatimSpan(i, end, Kind.LINE_COMMENT));
        i = end;
      } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
        int close = source.indexOf("*/", i + 2);
        int end = close == -1 ? n : close + 2;
        spans.add(new VerbatimSpan(i, end, Kind.BLOCK_COMMENT));
        i = end;
      } else if (c == '"' && i + 2 < n && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
        int end = scanTextBlock(source, i);
        spans.add(new VerbatimSpan(i, end, Kind.TEXT_BLOCK));
        i = end;
      } else if (c == '"') {
        int end = scanQuoted(source, i, '"');
        spans.add(new VerbatimSpan(i, end, Kind.STRING_LITERAL));
        i = end;
      } else if (c == '\'') {
        int end = scanQuoted(source, i, '\'');
        spans.add(new VerbatimSpan(i, end, Kind.CHAR_LITERAL));
        i = end;
      } else {
        i++;
      }
    }

    return new Scan(source, spans);
  }

  /**
   * Scans a single- or double-quoted literal starting at the opening quote, honoring backslash escapes, and returns the
   * offset right after the closing quote. Classic string/char literals cannot legally contain a raw newline, so an
   * unterminated literal is closed at the end of its line rather than consuming the rest of the file.
   */
  private static int scanQuoted(String source, int start, char quote) {
    int n = source.length();
    int i = start + 1;

    while (i < n) {
      char c = source.charAt(i);

      if (c == '\\' && i + 1 < n) {
        i += 2;
      } else if (c == quote) {
        return i + 1;
      } else if (c == '\n') {
        return i;
      } else {
        i++;
      }
    }

    return n;
  }

  /**
   * Scans a text block starting at its opening {@code """}. The closing delimiter is the first run of three or more
   * unescaped double quotes encountered after the opening line (JLS 3.10.6): the first three quotes of that run close
   * the block, even if more immediately follow.
   */
  private static int scanTextBlock(String source, int start) {
    int n = source.length();
    int i = start + 3;

    while (i < n) {
      char c = source.charAt(i);

      if (c == '\\' && i + 1 < n) {
        i += 2;
      } else if (c == '"') {
        int quoteRun = 0;
        int j = i;

        while (j < n && source.charAt(j) == '"') {
          quoteRun++;
          j++;
        }

        if (quoteRun >= 3) {
          return i + 3;
        }

        i = j;
      } else {
        i++;
      }
    }

    return n;
  }
}
