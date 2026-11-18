package org.tiger.java.code.formatter;

/**
 * Reindents Java source purely by brace nesting: the leading whitespace of every line is replaced with
 * {@code depth * indentWidth} spaces, where {@code depth} is how many non-verbatim {@code {}/}} braces are currently
 * open. Nothing else about a line is touched - no token spacing, no brace insertion.
 * <p>
 * A line whose previous (real) line doesn't end its statement - its last non-comment, non-whitespace character isn't
 * {@code ;}, {@code {} or {@code }} - is a continuation of that statement (e.g. a line {@link LineWrapper} split off,
 * or one a human already wrapped by hand) and gets one extra indent level instead of the current brace depth. This is
 * what makes wrapping idempotent: reformatting an already-wrapped line recognizes it as a continuation instead of
 * flattening it back to the statement's own depth. Two exceptions to the "one extra level" rule: a continuation line
 * leading with a closing {@code )} (e.g. a wrapped parameter list's closer) returns to the depth of whatever it's
 * closing instead of drilling deeper, and a complete,
 * <p>
 * single-line annotation usage (e.g. {@code @Override}) is treated like {@code ;}/{@code {}/
 * {@code }} - it ends its own unit, so the declaration after it is never misread as continuing it.
 * <p>
 * Text-block interiors, and the line holding a text block's closing {@code """}, are left completely untouched: per JLS
 * 3.10.6 a text block's value depends on the common leading whitespace of its content lines *and* its closing delimiter
 * line, so reindenting that line would silently change the string's value while keeping the file perfectly valid Java.
 * <p>
 * A still-open block comment's continuation lines (e.g. a javadoc's {@code " * "} lines) keep their indentation
 * relative to the comment's own opening line instead of being recomputed from brace depth - that's what keeps a
 * javadoc's conventional alignment intact when the comment as a whole shifts to a new depth.
 */
final class ScopeReindenter {

  private ScopeReindenter() {
  }

  /**
   * One open {@code {}: {@code openerDepth} is the display depth of the line that opened it (and so
   * of the line that closes it); its body sits one level deeper. Tracking this per brace, instead
   * of a bare depth counter, is what lets an anonymous class or lambda opened on a continuation
   * line (e.g. {@code return\n  new Foo() \{}) keep its body and closer relative to that line.
   */
  private record Frame(int openerDepth, boolean isEnum, boolean isCase, int parenBase) {
  }

  private static final java.util.regex.Pattern ENUM_WORD = java.util.regex.Pattern.compile("\\benum\\b");
  private static final java.util.regex.Pattern ANON_OR_LAMBDA =
        java.util.regex.Pattern.compile("\\bnew\\b|->|\\bswitch\\b");

  private static int bodyDepth(java.util.ArrayDeque<Frame> stack) {
    return stack.isEmpty() ? 0 : stack.peek().openerDepth() + 1;
  }

  static String reindent(String source, int indentWidth) {
    LiteralScanner.Scan scan = LiteralScanner.scan(source);
    StringBuilder out = new StringBuilder(source.length());
    java.util.ArrayDeque<Frame> stack = new java.util.ArrayDeque<>();
    java.util.ArrayDeque<int[]> parens = new java.util.ArrayDeque<>();
    boolean previousEndsStatement = true;
    boolean previousNetBracesZero = true;
    char previousLast = 0;
    int blockCommentOriginalIndent = 0;
    int blockCommentNewIndent = 0;
    int n = source.length();
    int lineStart = 0;

    while (lineStart <= n) {
      int newline = source.indexOf('\n', lineStart);
      boolean hasNewline = newline != -1;
      int lineEnd = hasNewline ? newline : n;
      int contentEnd = lineEnd;

      if (contentEnd > lineStart && source.charAt(contentEnd - 1) == '\r') {
        contentEnd--;
      }

      String rawLine = source.substring(lineStart, contentEnd);
      String trimmed = rawLine.stripLeading();

      if (trimmed.isEmpty()) {
        // Blank (or whitespace-only) line: emit as truly empty. Safe even inside a text
        // block, since javac strips trailing whitespace from every text-block line anyway
        // and blank lines don't participate in its common-indent calculation. Transparent to
        // continuation tracking, same as a comment-only line.
      } else {
        int firstNonWs = lineStart + (rawLine.length() - trimmed.length());
        boolean isCaseLabel = isCaseLabelLine(scan, trimmed, firstNonWs, contentEnd);
        // A comment-only line right before the next label belongs to that label, so it
        // sits at the label's depth rather than inside the previous body.
        boolean commentBeforeLabel = !stack.isEmpty() && stack.peek().isCase()
                    && (scan.kindAt(firstNonWs) == LiteralScanner.Kind.LINE_COMMENT
                        || scan.kindAt(firstNonWs) == LiteralScanner.Kind.BLOCK_COMMENT)
                    && nextCodeLineIsCaseLabel(scan, lineEnd + 1);

        // A new `case`/`default:` label, or a closing '}', implicitly ends the previous
        // label's (brace-less) body scope.
        if (!stack.isEmpty() && stack.peek().isCase()
                    && (isCaseLabel || commentBeforeLabel || countLeadingCloses(scan, lineStart, contentEnd) > 0)) {
          stack.pop();
        }

        int depth = bodyDepth(stack);
        int lineDisplayDepth = depth;
        boolean lineIsContinuation = false;
        LiteralScanner.Kind firstKind = scan.kindAt(firstNonWs);

        if (firstKind == LiteralScanner.Kind.TEXT_BLOCK) {
          out.append(rawLine);
        } else if (firstKind == LiteralScanner.Kind.BLOCK_COMMENT
                    && scan.spanAt(firstNonWs).start() != firstNonWs) {
          // A continuation line of a still-open block comment (e.g. a javadoc's " * "
          // lines): reindent by shifting it to keep its indentation *relative to the
          // comment's own opening line*, rather than recomputing from brace depth. That
          // preserves conventional internal alignment (javadoc's " * ", ASCII diagrams,
          // param-list alignment) while still relocating the whole comment with its
          // surrounding code.
          int existingIndent = rawLine.length() - trimmed.length();
          int shift = existingIndent - blockCommentOriginalIndent;
          int newIndent = Math.max(0, blockCommentNewIndent + shift);
          out.append(" ".repeat(newIndent));
          out.append(trimmed);
        } else {
          // A continuation only when the previous line neither ended its statement NOR
          // already opened a still-unclosed brace of its own (e.g. an array initializer's
          // '{' straddling the wrap point) - that case already deepens `depth` on its own,
          // so adding a continuation bonus on top of it would double-count the nesting.
          int leadingCloses = countLeadingCloses(scan, lineStart, contentEnd);
          // A line leading with '}' closes a scope, so it is never a continuation.
          // Likewise a line leading with '{' (a brace on its own line after a wrapped
          // class/method header) opens a scope at the header's own depth.
          boolean isContinuation = !previousEndsStatement && previousNetBracesZero
                        && leadingCloses == 0 && !(trimmed.startsWith("{") && previousLast != '=');
          lineIsContinuation = isContinuation;
          int baseDepth;

          if (isContinuation) {
            // Never *decrease* a continuation line's depth below what it already had:
            // ScopeReindenter only knows brace/scope depth, not operator precedence, so
            // it can't derive a multi-level cascade like ExpressionWrapper's (e.g. a
            // bitwise expression split across several precedence tiers) - but it must
            // not flatten one back down to a single "+1" either, or reformatting
            // already-wrapped output wouldn't be idempotent. depth + 1 is still the
            // floor, so a continuation that's genuinely new (not already deeper) still
            // gets the normal single-level bump - except a line leading with a closing
            // ')' (e.g. a wrapped parameter list's closer), which by convention returns
            // to the depth of whatever it's closing rather than drilling one deeper.
            int existingIndent = rawLine.length() - trimmed.length();
            int preExistingDepth = existingIndent / indentWidth + leadingCloses;
            int freshBonus = startsWithCloser(trimmed) ? depth : depth + 1;
            baseDepth = Math.max(freshBonus, preExistingDepth);
          } else {
            baseDepth = depth;
          }

          int displayDepth;

          if (leadingCloses > 0) {
            // A closer returns to the display depth of the line that opened the scope.
            displayDepth = 0;
            int k = 0;

            for (Frame f : stack) {
              if (f.isCase()) {
                continue;
              }

              if (++k == leadingCloses) {
                displayDepth = f.openerDepth();
                break;
              }
            }
          } else {
            displayDepth = baseDepth;
          }

          if (trimmed.startsWith(")") && leadingCloses == 0 && !parens.isEmpty()) {
            // A closing ')' returns to (at least) the display depth of the line that opened it,
            // even when the previous line (e.g. an anonymous class's '}') ended a unit.
            displayDepth = Math.max(displayDepth, parens.peek()[0]);
          }

          lineDisplayDepth = displayDepth;

          if (firstKind == LiteralScanner.Kind.BLOCK_COMMENT) {
            blockCommentOriginalIndent = rawLine.length() - trimmed.length();
            blockCommentNewIndent = displayDepth * indentWidth;
          }

          out.append(" ".repeat(displayDepth * indentWidth));
          out.append(trimmed);
        }

        int netBraces = applyBraces(scan, lineStart, contentEnd, stack, lineDisplayDepth,
                    lineIsContinuation, parens.size());

        if (isCaseLabel && lastCodeChar(scan, lineStart, contentEnd) == ':') {
          // Statements after `case X:` / `default:` sit one level deeper, until the next
          // label or the switch's closing brace.
          stack.push(new Frame(lineDisplayDepth, false, true, parens.size()));
        }

        boolean closedAnnotation = applyParens(scan, lineStart, contentEnd, parens, lineDisplayDepth,
                    trimmed.startsWith("@") && !trimmed.startsWith("@interface"));
        boolean enumConstantContext = !stack.isEmpty() && stack.peek().isEnum()
                    && parens.size() == stack.peek().parenBase();
        Boolean endsStatement = classifyLineEnd(scan, lineStart, contentEnd, enumConstantContext);

        if (isCaseLabel) {
          endsStatement = Boolean.TRUE;
        }

        // A ';' inside a parenthesis opened since the enclosing brace (a `for (init; cond;
        // update)` header) separates clauses; it does not end a statement.
        int parenBase = stack.isEmpty() ? 0 : stack.peek().parenBase();

        if (Boolean.TRUE.equals(endsStatement) && parens.size() > parenBase
                    && lastCodeChar(scan, lineStart, contentEnd) == ';') {
          endsStatement = Boolean.FALSE;
        }

        // The ')' closing a multi-line annotation's argument list ends the annotation, so
        // the declaration after it isn't read as continuing it.
        if (closedAnnotation && parens.isEmpty() && lastCodeChar(scan, lineStart, contentEnd) == ')') {
          endsStatement = Boolean.TRUE;
        }

        if (endsStatement != null) {
          previousEndsStatement = endsStatement;
          previousNetBracesZero = netBraces <= 0;
          previousLast = lastCodeChar(scan, lineStart, contentEnd);
        }
      }

      if (hasNewline) {
        out.append('\n');
        lineStart = lineEnd + 1;
      } else {
        break;
      }
    }

    return out.toString();
  }

  /** Whether the line is an old-style {@code case X:} / {@code default:} switch label. */
  private static boolean isCaseLabelLine(LiteralScanner.Scan scan, String trimmed, int firstNonWs,
        int contentEnd) {
    if (scan.isVerbatim(firstNonWs)) {
      return false;
    }

    if (!startsWithWord(trimmed, "case") && !startsWithWord(trimmed, "default")) {
      return false;
    }

    char last = lastCodeChar(scan, firstNonWs, contentEnd);

    if (last == ':') {
      return true;
    }

    // `case X: {` - a label whose body is a braced block.
    return last == '{' && endsWithColonBeforeBrace(scan, firstNonWs, contentEnd);
  }

  private static boolean endsWithColonBeforeBrace(LiteralScanner.Scan scan, int start, int contentEnd) {
    String source = scan.source();
    int i = contentEnd - 1;

    while (i >= start && (Character.isWhitespace(source.charAt(i)) || scan.isVerbatim(i))) {
      i--;
    }

    if (i < start || source.charAt(i) != '{') {
      return false;
    }

    i--;

    while (i >= start && Character.isWhitespace(source.charAt(i))) {
      i--;
    }

    return i > start && source.charAt(i) == ':' && source.charAt(i - 1) != ':';
  }

  private static boolean startsWithWord(String text, String word) {
    return text.startsWith(word)
            && (text.length() == word.length() || !Character.isJavaIdentifierPart(text.charAt(word.length())));
  }

  /** The last non-whitespace, non-comment character of a line, or 0 if it has none. */
  private static char lastCodeChar(LiteralScanner.Scan scan, int lineStart, int contentEnd) {
    String source = scan.source();
    char last = 0;

    for (int i = lineStart; i < contentEnd; i++) {
      LiteralScanner.Kind kind = scan.kindAt(i);

      if (kind == LiteralScanner.Kind.LINE_COMMENT) {
        break;
      }

      if (kind == LiteralScanner.Kind.BLOCK_COMMENT) {
        i = scan.spanAt(i).end() - 1;
        continue;
      }

      if (!Character.isWhitespace(source.charAt(i))) {
        last = source.charAt(i);
      }
    }

    return last;
  }

  /**
   * Whether a continuation line leads with a closing {@code )} or a generic-closing {@code >} (not {@code >>},
   * {@code >=} or {@code >>>} operators), which by convention returns to the depth of whatever it closes.
   */
  private static boolean startsWithCloser(String trimmed) {
    if (trimmed.startsWith(")")) {
      return true;
    }

    return trimmed.startsWith(">")
            && (trimmed.length() == 1 || (trimmed.charAt(1) != '>' && trimmed.charAt(1) != '='));
  }

  /** The number of consecutive, non-verbatim {@code '}'} characters leading a line's content. */
  static int countLeadingCloses(LiteralScanner.Scan scan, int lineStart, int contentEnd) {
    String source = scan.source();
    int i = lineStart;

    while (i < contentEnd && Character.isWhitespace(source.charAt(i))) {
      i++;
    }

    int count = 0;

    while (i < contentEnd && source.charAt(i) == '}' && !scan.isVerbatim(i)) {
      count++;
      i++;
    }

    return count;
  }

  /**
   * Pushes/pops {@link Frame}s for the line's non-verbatim braces and returns the net brace
   * change. A {@code {} normally opens at the current depth; one on a continuation line that
   * starts an anonymous class or lambda opens at the line's own display depth instead.
   */
  private static int applyBraces(LiteralScanner.Scan scan, int lineStart, int contentEnd,
        java.util.ArrayDeque<Frame> stack, int lineDisplayDepth, boolean lineIsContinuation,
        int parensAtLineStart) {
    String source = scan.source();
    int net = 0;

    for (int i = lineStart; i < contentEnd; i++) {
      if (scan.isVerbatim(i)) {
        continue;
      }

      char c = source.charAt(i);

      if (c == '{') {
        String prefix = source.substring(lineStart, i);
        String beforeBrace = prefix.stripTrailing();
        boolean arrayInitializer = beforeBrace.endsWith("=") || beforeBrace.endsWith("]")
                    || beforeBrace.endsWith(",") || beforeBrace.endsWith("(")
                    || (beforeBrace.isEmpty() && !stack.isEmpty() && stack.peek().isEnum());
        boolean hangs = lineIsContinuation
                    && (ANON_OR_LAMBDA.matcher(prefix).find()
                        || (parensAtLineStart > 0 && arrayInitializer));
        int opener = hangs ? lineDisplayDepth : bodyDepth(stack);
        int parenBase = parensAtLineStart + netParens(scan, lineStart, i);
        stack.push(new Frame(opener, ENUM_WORD.matcher(prefix).find() || arrayInitializer, false,
                    parenBase));
        net++;
      } else if (c == '}') {
        if (!stack.isEmpty() && stack.peek().isCase()) {
          stack.pop();
        }

        if (!stack.isEmpty()) {
          stack.pop();
        }

        net--;
      }
    }

    return net;
  }

  private static int netParens(LiteralScanner.Scan scan, int from, int to) {
    String source = scan.source();
    int net = 0;

    for (int i = from; i < to; i++) {
      if (!scan.isVerbatim(i)) {
        char c = source.charAt(i);
        net += c == '(' ? 1 : c == ')' ? -1 : 0;
      }
    }

    return net;
  }

  /**
   * Pushes the line's display depth (and whether the line starts an annotation) for each unmatched '(' and pops one for
   * each ')'. Returns whether a ')' on this line closed the parenthesis an annotation line opened.
   */
  private static boolean applyParens(LiteralScanner.Scan scan, int lineStart, int contentEnd,
        java.util.ArrayDeque<int[]> parens, int lineDisplayDepth, boolean lineStartsAnnotation) {
    String source = scan.source();
    boolean closedAnnotation = false;

    for (int i = lineStart; i < contentEnd; i++) {
      if (scan.isVerbatim(i)) {
        continue;
      }

      char c = source.charAt(i);

      if (c == '(') {
        parens.push(new int[] {lineDisplayDepth, lineStartsAnnotation ? 1 : 0});
      } else if (c == ')' && !parens.isEmpty()) {
        closedAnnotation = parens.pop()[1] == 1;
      }
    }

    return closedAnnotation;
  }

  /** Whether the next line holding code (skipping blank and comment-only lines) is a case label. */
  private static boolean nextCodeLineIsCaseLabel(LiteralScanner.Scan scan, int from) {
    String source = scan.source();
    int n = source.length();
    int lineStart = from;

    while (lineStart < n) {
      int newline = source.indexOf('\n', lineStart);
      int lineEnd = newline == -1 ? n : newline;
      String trimmed = source.substring(lineStart, lineEnd).strip();

      if (!trimmed.isEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("/*")
                && !trimmed.startsWith("*")) {
        int firstNonWs = lineStart + (source.substring(lineStart, lineEnd).length()
                    - source.substring(lineStart, lineEnd).stripLeading().length());
        return isCaseLabelLine(scan, trimmed, firstNonWs, lineEnd);
      }

      if (newline == -1) {
        return false;
      }

      lineStart = newline + 1;
    }

    return false;
  }

  /**
   * Whether a line's own code ends a statement/block ({@code true}: last real character is {@code ;},
   * {@code {} or {@code }}), continues one ({@code false}), or is blank/comment-only and transparent to continuation
   * tracking ({@code null}). Trailing line/block comments don't count as the line's "real" ending.
   */
  private static Boolean classifyLineEnd(LiteralScanner.Scan scan, int lineStart, int contentEnd,
        boolean enumConstantContext) {
    String source = scan.source();
    char last = 0;
    boolean found = false;
    int parenBalance = 0;

    for (int i = lineStart; i < contentEnd; i++) {
      char c = source.charAt(i);

      if (Character.isWhitespace(c)) {
        continue;
      }

      LiteralScanner.Kind kind = scan.kindAt(i);

      if (kind == LiteralScanner.Kind.LINE_COMMENT) {
        break;
      }

      if (kind == LiteralScanner.Kind.BLOCK_COMMENT) {
        i = scan.spanAt(i).end() - 1;
        continue;
      }

      if (!scan.isVerbatim(i)) {
        if (c == '(') {
          parenBalance++;
        } else if (c == ')') {
          parenBalance--;
        }
      }

      last = c;
      found = true;
    }

    if (!found) {
      return null;
    }

    if (last == '}' && endsWithInlineInitializer(scan, lineStart, contentEnd)) {
      return false;
    }

    if (last == ';' || last == '{' || last == '}') {
      return true;
    }

    // A comma ending a line directly inside an enum body (outside any parentheses) separates
    // constants: it ends its own unit, so the next constant is not a continuation.
    if (last == ',' && enumConstantContext) {
      return true;
    }

    // A complete, single-line annotation usage (`@Override`, `@SuppressWarnings("x")`) always
    // precedes a fresh declaration - never a continuation of a wrapped expression - so it ends
    // its own unit here just like `;`/`{`/`}` do, instead of forcing the declaration after it to
    // read as a continuation of the annotation.
    if (parenBalance == 0 && isAnnotationUsageLine(scan, lineStart, contentEnd)) {
      return true;
    }

    return false;
  }

  /**
   * Whether the line consists solely of one or more annotation usages ({@code @Override},
   * {@code @SuppressWarnings("x")}). A line like {@code @Nullable Foo foo,} has a parameter or declaration after its
   * annotation, so it is not one.
   */
  /**
   * Whether a line's trailing {@code }} closes an inline array initializer / expression block
   * ({@code new Foo[] { a, b }}): its matching {@code {} follows {@code ]}, {@code =}, {@code ,}
   * or {@code (}. Such a line is part of an enclosing expression, not the end of a statement.
   */
  private static boolean endsWithInlineInitializer(LiteralScanner.Scan scan, int lineStart, int contentEnd) {
    String source = scan.source();
    int j = contentEnd - 1;

    while (j >= lineStart && (Character.isWhitespace(source.charAt(j)) || scan.isVerbatim(j))) {
      j--;
    }

    if (j < lineStart || source.charAt(j) != '}') {
      return false;
    }

    int depth = 0;

    for (int i = j; i >= lineStart; i--) {
      if (scan.isVerbatim(i)) {
        continue;
      }

      char c = source.charAt(i);

      if (c == '}') {
        depth++;
      } else if (c == '{' && --depth == 0) {
        int k = i - 1;

        while (k >= lineStart && Character.isWhitespace(source.charAt(k))) {
          k--;
        }

        return k >= lineStart && "]=,(".indexOf(source.charAt(k)) >= 0;
      }
    }

    return false;
  }

  private static boolean isAnnotationUsageLine(LiteralScanner.Scan scan, int lineStart, int contentEnd) {
    String source = scan.source();
    int i = lineStart;
    boolean any = false;

    while (true) {
      while (i < contentEnd && Character.isWhitespace(source.charAt(i))) {
        i++;
      }

      if (i >= contentEnd || scan.kindAt(i) == LiteralScanner.Kind.LINE_COMMENT) {
        return any;
      }

      if (scan.kindAt(i) == LiteralScanner.Kind.BLOCK_COMMENT) {
        i = scan.spanAt(i).end();
        continue;
      }

      if (source.charAt(i) != '@' || scan.isVerbatim(i)) {
        return false;
      }

      int after = i + 1;
      int interfaceEnd = after + "interface".length();

      if (source.regionMatches(after, "interface", 0, "interface".length())
                && (interfaceEnd >= contentEnd || !Character.isJavaIdentifierPart(source.charAt(interfaceEnd)))) {
        return false;
      }

      i = after;

      while (i < contentEnd && (Character.isJavaIdentifierPart(source.charAt(i)) || source.charAt(i) == '.')) {
        i++;
      }

      int j = i;

      while (j < contentEnd && Character.isWhitespace(source.charAt(j))) {
        j++;
      }

      if (j < contentEnd && source.charAt(j) == '(' && !scan.isVerbatim(j)) {
        int balance = 0;

        for (; j < contentEnd; j++) {
          if (scan.isVerbatim(j)) {
            continue;
          }

          char c = source.charAt(j);

          if (c == '(') {
            balance++;
          } else if (c == ')' && --balance == 0) {
            j++;
            break;
          }
        }

        if (balance != 0) {
          return false;
        }

        i = j;
      }

      any = true;
    }
  }
}
