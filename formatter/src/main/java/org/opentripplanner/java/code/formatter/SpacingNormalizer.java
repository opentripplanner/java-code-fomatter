package org.opentripplanner.java.code.formatter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * Normalizes the spaces between two tokens on one line: {@code int   a=1,b=2;} becomes {@code int a = 1, b = 2;} and
 * {@code if(x)return;} becomes {@code if (x) return;}.
 * <p>
 * Only a gap that has no line break in it, and only when both neighbours say what the gap should be, is changed. Where
 * the source is ambiguous without a parser (a {@code <} that opens generics or compares, a {@code -} after a
 * {@code )} that follows a cast or an expression, the colon of a {@code case} label) the gap stays as written. Gaps
 * next to comments, string literals' insides, text blocks and the indentation of a line are never touched. Whatever
 * this class does, {@link SourceFormatter} still checks that the tokens did not change.
 */
final class SpacingNormalizer {

  private static final Set<String> KEYWORDS = Set.of(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default",
    "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements",
    "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public",
    "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient",
    "try", "void", "volatile", "while", "yield", "true", "false", "null"
  );

  /** Keywords that end an expression, so a {@code -} after one is a subtraction. */
  private static final Set<String> VALUE_KEYWORDS = Set.of("this", "super", "true", "false", "null");

  /** Keywords that are followed by a space before an opening parenthesis. */
  private static final Set<String> SPACED_BEFORE_PAREN = Set.of(
    "if", "for", "while", "switch", "catch", "synchronized", "try", "return", "throw", "assert", "case", "instanceof"
  );

  private static final String[] OPERATORS = {
    ">>>=", "<<=", ">>=", "...", "->", "::", "++", "--", "&&", "||", "==", "!=", "<=", ">=", "<<", "+=", "-=", "*=",
    "/=", "%=", "&=", "|=", "^=",
  };

  private enum Kind {
    WORD,
    LITERAL,
    COMMENT,
    OP
  }

  /** What an operator token is in its context; {@link #AMBIGUOUS} means the gaps next to it are left alone. */
  private enum Role {
    NONE,
    BINARY,
    PREFIX,
    POSTFIX,
    TERNARY,
    AMBIGUOUS
  }

  private enum Gap {
    KEEP,
    NONE,
    ONE,
    /** One space if there is a gap at all, otherwise none. */
    COLLAPSE
  }

  private record Token(int start, int end, Kind kind, String text) {

    boolean is(String operator) {
      return kind == Kind.OP && text.equals(operator);
    }

    boolean isWord() {
      return kind == Kind.WORD;
    }
  }

  private SpacingNormalizer() {
  }

  static String format(String source) {
    List<Token> tokens = tokenize(source);
    Role[] roles = roles(tokens);
    StringBuilder out = new StringBuilder(source.length());
    int copied = 0;

    for (int i = 1; i < tokens.size(); i++) {
      Token before = tokens.get(i - 1);
      Token after = tokens.get(i);

      if (!isBlank(source, before.end(), after.start())) {
        continue;
      }

      String wanted = switch (gap(tokens, roles, i)) {
        case NONE -> "";
        case ONE -> " ";
        case COLLAPSE -> before.end() == after.start() ? "" : " ";
        case KEEP -> null;
      };

      if (wanted != null && !wanted.equals(source.substring(before.end(), after.start()))) {
        out.append(source, copied, before.end()).append(wanted);
        copied = after.start();
      }
    }

    return copied == 0 ? source : out.append(source, copied, source.length()).toString();
  }

  /** Whether the gap holds only spaces and tabs, so it is on one line and is not a comment. */
  private static boolean isBlank(String source, int from, int to) {
    for (int i = from; i < to; i++) {
      char c = source.charAt(i);

      if (c != ' ' && c != '\t') {
        return false;
      }
    }

    return true;
  }

  private static Gap gap(List<Token> tokens, Role[] roles, int index) {
    Token p = tokens.get(index - 1);
    Token n = tokens.get(index);
    Role pr = roles[index - 1];
    Role nr = roles[index];

    if (p.kind() == Kind.COMMENT || n.kind() == Kind.COMMENT) {
      return Gap.KEEP;
    }

    // for (...; cond; ) and for (;;): both spellings are common, so the gap is left as written
    if (p.is(";") && (n.is(";") || n.is(")"))) {
      return Gap.KEEP;
    }

    if (n.is(",") || n.is(";") || n.is(".") || n.is("::") || p.is(".") || p.is("::") || n.is("[") || p.is("[")
      || n.is("]") || p.is("(") || n.is(")") || p.is("@") || n.is("...") || nr == Role.POSTFIX
      || pr == Role.PREFIX) {
      return Gap.NONE;
    }

    if (pr == Role.BINARY || pr == Role.TERNARY || nr == Role.BINARY || nr == Role.TERNARY || p.is("...")) {
      return Gap.ONE;
    }

    if (p.is(",")) {
      return n.is("}") ? Gap.KEEP : Gap.ONE;
    }

    if (p.is(";")) {
      return n.is("}") ? Gap.KEEP : Gap.ONE;
    }

    if (n.is("(")) {
      return beforeParenthesis(p);
    }

    if (n.is("{")) {
      return p.isWord() || p.is(")") || p.is(">") ? Gap.ONE : Gap.KEEP;
    }

    if (p.is("}") && n.isWord() && Set.of("else", "catch", "finally", "while").contains(n.text())) {
      return Gap.ONE;
    }

    boolean operand = n.isWord() || n.kind() == Kind.LITERAL;

    if (operand && (p.isWord() || p.kind() == Kind.LITERAL || p.is(")") || p.is("]"))) {
      return Gap.ONE;
    }

    if (operand && p.is(">")) {
      return Gap.COLLAPSE;
    }

    if (p.isWord() && isKeyword(p) && !isValue(p) && n.kind() == Kind.OP
      && (nr == Role.PREFIX || n.is("@"))) {
      return Gap.ONE;
    }

    return Gap.KEEP;
  }

  private static Gap beforeParenthesis(Token p) {
    if (p.isWord()) {
      if (SPACED_BEFORE_PAREN.contains(p.text())) {
        return Gap.ONE;
      }

      if (!isKeyword(p) || p.text().equals("this") || p.text().equals("super")) {
        return isNumber(p) ? Gap.KEEP : Gap.NONE;
      }
    }

    return Gap.KEEP;
  }

  /** Decides what every operator token is, from the token before it and the parentheses and {@code ?} around it. */
  private static Role[] roles(List<Token> tokens) {
    Role[] roles = new Role[tokens.size()];
    Deque<Boolean> parentheses = new ArrayDeque<>();
    Deque<Integer> ternaries = new ArrayDeque<>();

    for (int i = 0; i < tokens.size(); i++) {
      Token token = tokens.get(i);
      Token before = i == 0 ? null : tokens.get(i - 1);
      Token after = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
      roles[i] = Role.NONE;

      if (token.kind() != Kind.OP) {
        continue;
      }

      switch (token.text()) {
        case "(" -> parentheses.push(before != null && before.isWord() && before.text().equals("for"));
        case ")" -> {
          if (!parentheses.isEmpty()) {
            parentheses.pop();
          }

          while (!ternaries.isEmpty() && ternaries.peek() > parentheses.size()) {
            ternaries.pop();
          }
        }
        case ";", "{", "}" -> ternaries.clear();
        case "+", "-" -> roles[i] = plusOrMinus(token, before, after);
        case "!", "~" -> roles[i] = Role.PREFIX;
        case "++", "--" -> roles[i] = before == null || before.kind() == Kind.COMMENT
          ? Role.AMBIGUOUS
          : endsValue(before) && !before.is(")")
            ? Role.POSTFIX
            : before.is(")") ? Role.AMBIGUOUS : Role.PREFIX;
        case "*" -> roles[i] = before != null && before.is(".") ? Role.AMBIGUOUS : Role.BINARY;
        case "/", "%", "^", "|", "&", "&&", "||", "=", "==", "!=", "<=", ">=", "+=", "-=", "*=", "/=", "%=", "&=",
          "|=", "^=", "<<=", ">>=", ">>>=", "->" -> roles[i] = Role.BINARY;
        case "?" -> {
          if (before != null && endsValue(before) && !before.is("<")) {
            roles[i] = Role.TERNARY;
            ternaries.push(parentheses.size());
          } else {
            roles[i] = Role.AMBIGUOUS;
          }
        }
        case ":" -> {
          if (!ternaries.isEmpty() && ternaries.peek() == parentheses.size()) {
            ternaries.pop();
            roles[i] = Role.TERNARY;
          } else if (!parentheses.isEmpty() && parentheses.peek() && ternaries.isEmpty()) {
            roles[i] = Role.TERNARY;
          } else {
            roles[i] = Role.AMBIGUOUS;
          }
        }
        default -> {
        }
      }
    }

    return roles;
  }

  /** A binary operator after something that ends a value, a prefix sign where a value starts, otherwise unknown. */
  private static Role plusOrMinus(Token token, Token before, Token after) {
    if (before == null || before.kind() == Kind.COMMENT) {
      return Role.AMBIGUOUS;
    }

    if (token.text().equals("-") && before.isWord() && before.text().equals("non") && after != null
      && after.isWord() && after.text().equals("sealed")) {
      return Role.AMBIGUOUS;
    }

    if (endsValue(before)) {
      return before.is(")") || before.is("++") || before.is("--") ? Role.AMBIGUOUS : Role.BINARY;
    }

    return before.is(">") || before.is("}") ? Role.AMBIGUOUS : Role.PREFIX;
  }

  /** Whether an expression can end with this token: a name, a number, a literal, {@code )} or {@code ]}. */
  private static boolean endsValue(Token token) {
    return switch (token.kind()) {
      case LITERAL -> true;
      case WORD -> !isKeyword(token) || isValue(token);
      case OP -> token.text().equals(")") || token.text().equals("]") || token.text().equals("++")
        || token.text().equals("--");
      case COMMENT -> false;
    };
  }

  private static boolean isKeyword(Token word) {
    return KEYWORDS.contains(word.text());
  }

  private static boolean isValue(Token word) {
    return VALUE_KEYWORDS.contains(word.text());
  }

  private static boolean isNumber(Token word) {
    char c = word.text().charAt(0);
    return Character.isDigit(c) || c == '.';
  }

  private static List<Token> tokenize(String source) {
    LiteralScanner.Scan scan = LiteralScanner.scan(source);
    List<LiteralScanner.VerbatimSpan> spans = scan.spans();
    List<Token> tokens = new ArrayList<>();
    int n = source.length();
    int span = 0;
    int i = 0;

    while (i < n) {
      if (span < spans.size() && spans.get(span).start() == i) {
        LiteralScanner.VerbatimSpan literal = spans.get(span++);
        boolean comment = literal.kind() == LiteralScanner.Kind.LINE_COMMENT
          || literal.kind() == LiteralScanner.Kind.BLOCK_COMMENT;
        tokens.add(
          new Token(literal.start(), literal.end(), comment ? Kind.COMMENT : Kind.LITERAL, "")
        );
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

        tokens.add(new Token(i, end, Kind.WORD, source.substring(i, end)));
        i = end;
      } else if (Character.isDigit(c) || c == '.' && i + 1 < n && Character.isDigit(source.charAt(i + 1))) {
        int end = TokenStream.numberEnd(source, i);
        tokens.add(new Token(i, end, Kind.WORD, source.substring(i, end)));
        i = end;
      } else {
        String operator = operatorAt(source, i);
        tokens.add(new Token(i, i + operator.length(), Kind.OP, operator));
        i += operator.length();
      }
    }

    return tokens;
  }

  private static String operatorAt(String source, int i) {
    for (String operator : OPERATORS) {
      if (source.startsWith(operator, i)) {
        return operator;
      }
    }

    return String.valueOf(source.charAt(i));
  }
}
