package org.tiger.java.code.formatter;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps lines over a configured column budget at safe token boundaries: a top-level comma when one
 * fits, otherwise a binary/logical operator or method-chain {@code .}, walking bracket depth
 * outward-in so a nested (e.g. lambda parameter) comma is tried before giving up on commas
 * altogether. A line with no safe break point - or one that's wholly inside a string/text block -
 * is left untouched and reported rather than corrupted; the caller decides what to do with that.
 *
 * <p>Runs as a post-pass over {@link ScopeReindenter}'s output, reusing the same
 * {@link LiteralScanner} so a break never lands inside a comment, character/string literal or text
 * block, and never lands inside or after a trailing line comment.
 */
final class LineWrapper {

  // Longest match first, so a multi-character operator is never split in half.
  private static final String[] OPERATORS = {
    ">>>=", "<<=", ">>=", ">>>",
    "->", "::", "&&", "||", "==", "!=", "<=", ">=",
    "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>", "++", "--",
    "+", "-", "*", "/", "%", "&", "|", "^", "<", ">", "!", "~", "?",
  };

  record WrapResult(String text, List<Integer> unwrappableLines) {
  }

  private record Candidate(int depth, int position) {
  }

  private LineWrapper() {
  }

  static WrapResult wrap(String source, int maxColumns, int indentWidth) {
    LiteralScanner.Scan scan = LiteralScanner.scan(source);
    StringBuilder out = new StringBuilder(source.length());
    List<Integer> unwrappable = new ArrayList<>();
    int bracketDepth = 0;
    int n = source.length();
    int lineStart = 0;
    int lineNumber = 1;

    while (lineStart <= n) {
      int newline = source.indexOf('\n', lineStart);
      boolean hasNewline = newline != -1;
      int lineEnd = hasNewline ? newline : n;
      int contentEnd = lineEnd;

      if (contentEnd > lineStart && source.charAt(contentEnd - 1) == '\r') {
        contentEnd--;
      }

      String head = source.substring(lineStart, contentEnd).stripLeading();
      boolean importOrPackage = head.startsWith("import ") || head.startsWith("package ");

      if (contentEnd - lineStart <= maxColumns || importOrPackage) {
        // import/package lines are never wrapped (a long one is conventionally exempt).
        out.append(source, lineStart, contentEnd);
      } else {
        List<String> fragments = wrapLine(scan, lineStart, contentEnd, bracketDepth, maxColumns, indentWidth);

        if (fragments == null) {
          out.append(source, lineStart, contentEnd);
          unwrappable.add(lineNumber);
        } else {
          for (int k = 0; k < fragments.size(); k++) {
            if (k > 0) {
              out.append('\n');
            }

            out.append(fragments.get(k));
          }
        }
      }

      bracketDepth += netBrackets(scan, lineStart, contentEnd);

      if (hasNewline) {
        out.append('\n');
        lineStart = lineEnd + 1;
        lineNumber++;
      } else {
        break;
      }
    }

    return new WrapResult(out.toString(), unwrappable);
  }

  /**
   * Splits one over-length line into fragments, or returns {@code null} if no safe break point
   * exists anywhere in it (a wholly-verbatim line, or one with no comma/operator candidate that
   * fits the column budget).
   */
  private static List<String> wrapLine(LiteralScanner.Scan scan, int rangeStart, int rangeEnd, int depthAtStart,
    int maxColumns, int indentWidth) {
    String source = scan.source();
    int printedIndent = 0;

    while (rangeStart + printedIndent < rangeEnd && source.charAt(rangeStart + printedIndent) == ' ') {
      printedIndent++;
    }

    int leadingCloses = ScopeReindenter.countLeadingCloses(scan, rangeStart, rangeEnd);
    int logicalIndent = printedIndent + leadingCloses * indentWidth;
    int continuationBaseIndent = logicalIndent + indentWidth;

    List<String> fragments = new ArrayList<>();
    int curStart = rangeStart;
    int curColumnOffset = 0;
    int curDepth = depthAtStart;
    boolean first = true;

    while (true) {
      int visualLength = curColumnOffset + (rangeEnd - curStart);

      if (visualLength <= maxColumns) {
        fragments.add(first ? source.substring(curStart, rangeEnd) : withIndent(scan, curStart, rangeEnd,
          curColumnOffset));
        return fragments;
      }

      BreakPoint bp = findBreakPoint(scan, curStart, rangeEnd, curColumnOffset, curDepth, maxColumns);

      if (bp == null) {
        return null;
      }

      fragments.add(first ? source.substring(curStart, bp.keepEnd) : withIndent(scan, curStart, bp.keepEnd,
        curColumnOffset));
      curDepth += netBrackets(scan, curStart, bp.continueStart);
      curStart = bp.continueStart;
      int fragLeadingCloses = ScopeReindenter.countLeadingCloses(scan, curStart, rangeEnd);
      curColumnOffset = Math.max(0, continuationBaseIndent - fragLeadingCloses * indentWidth);
      first = false;
    }
  }

  private static String withIndent(LiteralScanner.Scan scan, int start, int end, int indentSpaces) {
    return " ".repeat(indentSpaces) + scan.source().substring(start, end);
  }

  private record BreakPoint(int keepEnd, int continueStart) {
  }

  private static BreakPoint findBreakPoint(LiteralScanner.Scan scan, int start, int end, int columnOffset,
    int depthAtStart, int maxColumns) {
    String source = scan.source();
    List<Candidate> commas = new ArrayList<>();
    List<Candidate> operators = new ArrayList<>();
    List<Candidate> dots = new ArrayList<>();
    int depth = depthAtStart;
    // The '->' of a `case X -> ...` / `default -> ...` arm is not a place to break.
    String lineText = source.substring(start, end).stripLeading();
    boolean switchArm = lineText.startsWith("case ") || lineText.startsWith("default ");

    int i = start;

    while (i < end) {
      if (scan.isVerbatim(i)) {
        LiteralScanner.Kind kind = scan.kindAt(i);

        if (kind == LiteralScanner.Kind.LINE_COMMENT) {
          break;
        }

        LiteralScanner.VerbatimSpan span = scan.spanAt(i);
        i = span.end();
        continue;
      }

      char c = source.charAt(i);
      int genericEnd = c == '<' ? genericArgumentsEnd(scan, start, i, end) : -1;

      if (genericEnd > 0) {
        // A generic argument list (`<org.example.Type>`) is never broken, inside or at its
        // brackets.
        i = genericEnd;
      } else if (c == '(' || c == '[') {
        depth++;
        i++;
      } else if (c == ')' || c == ']') {
        depth--;
        i++;
      } else if (c == ',') {
        commas.add(new Candidate(depth, i));
        i++;
      } else if (c == '.') {
        boolean prevDigit = i > start && Character.isDigit(source.charAt(i - 1));
        boolean nextDigit = i + 1 < end && Character.isDigit(source.charAt(i + 1));
        boolean afterStringLiteral = i > start && source.charAt(i - 1) == '"';

        if (!prevDigit && !nextDigit && !afterStringLiteral) {
          Candidate dot = new Candidate(depth, i);
          operators.add(dot);
          dots.add(dot);
        }

        i++;
      } else {
        int matched = matchOperator(source, i, end);

        if (matched > 0) {
          // An operator that starts the line, or that ends it, is never a break point: breaking
          // there would leave it alone on a line (the operand it belongs to is on the other side).
          boolean leading = source.substring(start, i).isBlank();
          boolean trailing = source.substring(i + matched, end).isBlank();
          if (!(switchArm && source.startsWith("->", i)) && !leading && !trailing) {
            operators.add(new Candidate(depth, i));
          }

          i += matched;
        } else {
          i++;
        }
      }
    }

    // A lone `.` at its depth is a single call (`a.b(...)`), not a chain worth breaking before.
    for (Candidate dot : dots) {
      long sameDepth = dots.stream().filter(d -> d.depth() == dot.depth()).count();

      if (sameDepth == 1) {
        operators.remove(dot);
      }
    }

    BreakPoint fromComma = bestByDepth(source, commas, start, columnOffset, true, maxColumns);

    if (fromComma != null) {
      return fromComma;
    }

    return bestByDepth(source, operators, start, columnOffset, false, maxColumns);
  }

  /**
   * If the {@code '<'} at {@code i} opens a generic argument list - it directly follows an
   * identifier (or a {@code '.'}, as in {@code Foo.<T>bar()}) and everything up to its matching
   * {@code '>'} is type-name text - returns the index just past that {@code '>'}; otherwise
   * {@code -1}. A spaced comparison ({@code a < b}) never matches.
   */
  private static int genericArgumentsEnd(LiteralScanner.Scan scan, int start, int i, int end) {
    String source = scan.source();

    if (i <= start) {
      return -1;
    }

    char before = source.charAt(i - 1);

    if (!Character.isJavaIdentifierPart(before) && before != '.') {
      return -1;
    }

    int nesting = 1;

    for (int j = i + 1; j < end; j++) {
      if (scan.isVerbatim(j)) {
        return -1;
      }

      char c = source.charAt(j);

      if (c == '<') {
        nesting++;
      } else if (c == '>') {
        if (--nesting == 0) {
          return j + 1;
        }
      } else if (!Character.isJavaIdentifierPart(c) && c != '.' && c != ',' && c != ' ' && c != '['
                && c != ']' && c != '@' && c != '?') {
        return -1;
      }
    }

    return -1;
  }

  private static int matchOperator(String source, int i, int end) {
    for (String op : OPERATORS) {
      if (i + op.length() <= end && source.regionMatches(i, op, 0, op.length())) {
        return op.length();
      }
    }

    return 0;
  }

  /**
   * Among candidates, walks depth outward-in (shallowest first); at each depth level picks the
   * candidate with the largest position that still fits the column budget. {@code afterToken}
   * selects comma semantics (break kept with the preceding text, right after it) versus
   * operator/dot semantics (break moves to the start of the continuation, trailing whitespace on
   * the kept side trimmed).
   */
  private static BreakPoint bestByDepth(String source, List<Candidate> candidates, int rangeStart, int columnOffset,
    boolean afterToken, int maxColumns) {
    if (candidates.isEmpty()) {
      return null;
    }

    int minDepth = Integer.MAX_VALUE;
    int maxDepth = Integer.MIN_VALUE;

    for (Candidate c : candidates) {
      minDepth = Math.min(minDepth, c.depth());
      maxDepth = Math.max(maxDepth, c.depth());
    }

    for (int depth = minDepth; depth <= maxDepth; depth++) {
      Candidate best = null;

      for (Candidate c : candidates) {
        if (c.depth() != depth) {
          continue;
        }

        int keepEnd = afterToken ? c.position() + 1 : trimTrailingWhitespace(source, c.position());
        int visual = columnOffset + (keepEnd - rangeStart);

        // keepEnd <= rangeStart means there's nothing to keep before the break (the
        // operator is the first thing on the line after trimming) - not real progress,
        // and looping on it would keep re-selecting the same non-advancing candidate.
        if (keepEnd > rangeStart && visual <= maxColumns && (best == null || c.position() > best.position())) {
          best = c;
        }
      }

      if (best != null) {
        if (afterToken) {
          int keepEnd = best.position() + 1;
          int continueStart = keepEnd;
          int len = source.length();

          while (continueStart < len && (source.charAt(continueStart) == ' ' || source.charAt(continueStart) == '\t')) {
            continueStart++;
          }

          return new BreakPoint(keepEnd, continueStart);
        } else {
          int keepEnd = trimTrailingWhitespace(source, best.position());
          return new BreakPoint(keepEnd, best.position());
        }
      }
    }

    return null;
  }

  private static int trimTrailingWhitespace(String source, int position) {
    int end = position;

    while (end > 0 && (source.charAt(end - 1) == ' ' || source.charAt(end - 1) == '\t')) {
      end--;
    }

    return end;
  }

  private static int netBrackets(LiteralScanner.Scan scan, int start, int end) {
    String source = scan.source();
    int net = 0;

    for (int i = start; i < end; i++) {
      if (scan.isVerbatim(i)) {
        continue;
      }

      char c = source.charAt(i);

      if (c == '(' || c == '[') {
        net++;
      } else if (c == ')' || c == ']') {
        net--;
      }
    }

    return net;
  }
}
