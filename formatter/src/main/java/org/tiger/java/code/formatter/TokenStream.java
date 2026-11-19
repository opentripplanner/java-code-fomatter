package org.tiger.java.code.formatter;

import java.util.ArrayList;
import java.util.List;

/**
 * A minimal Java tokenizer used to prove that formatting changed nothing but whitespace and comments: two sources with
 * the same token list are the same program. Whitespace and comments are skipped; string and character literals are
 * kept exactly; a text block is compared by its value as the compiler computes it (see {@link #textBlockValue}), so a
 * change that the compiler ignores does not count; operators are matched greedily, except that every {@code >} is a token of its own, so
 * {@code List<List<X>>} and {@code List<List<X> >} compare equal (a shift {@code >>} cannot be split into two tokens
 * without making the program invalid, which the syntax check catches).
 */
final class TokenStream {

  private static final String[] OPERATORS = {
    "<<=", "...", "->", "::", "++", "--", "&&", "||", "==", "!=", "<=", "<<", "+=", "-=", "*=", "/=", "%=", "&=", "|=",
    "^=",
  };

  private TokenStream() {
  }

  static List<String> tokens(String source) {
    LiteralScanner.Scan scan = LiteralScanner.scan(source);
    List<LiteralScanner.VerbatimSpan> spans = scan.spans();
    List<String> tokens = new ArrayList<>();
    int n = source.length();
    int span = 0;
    int i = 0;

    while (i < n) {
      if (span < spans.size() && spans.get(span).start() == i) {
        LiteralScanner.VerbatimSpan literal = spans.get(span++);

        if (literal.kind() == LiteralScanner.Kind.TEXT_BLOCK) {
          tokens.add(textBlockValue(source.substring(literal.start(), literal.end())));
        } else if (literal.kind() != LiteralScanner.Kind.LINE_COMMENT
          && literal.kind() != LiteralScanner.Kind.BLOCK_COMMENT) {
          tokens.add(source.substring(literal.start(), literal.end()));
        }

        i = literal.end();
        continue;
      }

      char c = source.charAt(i);

      if (Character.isWhitespace(c)) {
        i++;
      } else if (Character.isJavaIdentifierStart(c)) {
        int end = i + 1;

        while (end < n && Character.isJavaIdentifierPart(source.charAt(end))) {
          end++;
        }

        tokens.add(source.substring(i, end));
        i = end;
      } else if (Character.isDigit(c) || c == '.' && i + 1 < n && Character.isDigit(source.charAt(i + 1))) {
        int end = numberEnd(source, i);
        tokens.add(source.substring(i, end));
        i = end;
      } else {
        String operator = operatorAt(source, i);
        tokens.add(operator);
        i += operator.length();
      }
    }

    return tokens;
  }

  /** A description of the first place the two sources' tokens differ, or {@code null} if they are the same. */
  static String firstDifference(String before, String after) {
    List<String> a = tokens(before);
    List<String> b = tokens(after);
    int common = Math.min(a.size(), b.size());

    for (int i = 0; i < common; i++) {
      if (!a.get(i).equals(b.get(i))) {
        return "token " + i + ": '" + abbreviate(a.get(i)) + "' became '" + abbreviate(b.get(i)) + "' (near '"
          + context(a, i) + "')";
      }
    }

    if (a.size() != b.size()) {
      return "the token count changed from " + a.size() + " to " + b.size();
    }

    return null;
  }

  /**
   * A text block as the compiler sees it before escape sequences are translated (JLS 3.10.6): the opening line and its
   * terminator dropped, line terminators normalized, the common indentation (including that of the closing delimiter's
   * line) removed, and trailing whitespace removed from every line. Two text blocks with the same result are the same
   * string, even if they differ in whitespace the compiler ignores: a whitespace-only line, trailing spaces, CRLF
   * instead of LF, or a uniform shift of the indentation.
   */
  private static String textBlockValue(String literal) {
    int lineEnd = 3;

    while (lineEnd < literal.length() && literal.charAt(lineEnd) != '\n' && literal.charAt(lineEnd) != '\r') {
      lineEnd++;
    }

    if (lineEnd >= literal.length() - 3) {
      return literal;
    }

    int contentStart = literal.startsWith("\r\n", lineEnd) ? lineEnd + 2 : lineEnd + 1;

    return "\"\"\"\n" + literal.substring(contentStart, literal.length() - 3).stripIndent() + "\"\"\"";
  }

  private static int numberEnd(String source, int start) {
    int n = source.length();
    boolean hex = source.startsWith("0x", start) || source.startsWith("0X", start);
    int end = start + 1;

    while (end < n) {
      char c = source.charAt(end);

      if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
        end++;
      } else if ((c == '+' || c == '-') && isExponentMarker(source.charAt(end - 1), hex)) {
        end++;
      } else {
        break;
      }
    }

    return end;
  }

  private static boolean isExponentMarker(char c, boolean hex) {
    return hex ? c == 'p' || c == 'P' : c == 'e' || c == 'E';
  }

  private static String operatorAt(String source, int i) {
    for (String operator : OPERATORS) {
      if (source.startsWith(operator, i)) {
        return operator;
      }
    }

    return String.valueOf(source.charAt(i));
  }

  private static String context(List<String> tokens, int index) {
    return String.join(" ", tokens.subList(Math.max(0, index - 4), Math.min(tokens.size(), index + 3)));
  }

  private static String abbreviate(String token) {
    return token.length() <= 40 ? token : token.substring(0, 37) + "...";
  }
}
