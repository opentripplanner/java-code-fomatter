package org.tiger.java.code.formatter;

import java.util.ArrayList;
import java.util.List;

/**
 * Formats JSON the way Prettier does: one indent step per nesting level, {@code { "a": 1 }} and {@code [1, 2]} kept on
 * one line when they fit, and everything else broken one entry per line. An object that has a line break after its
 * {@code {} in the source stays expanded. Keys, strings and numbers are copied exactly as written and the order never
 * changes.
 * <p>
 * {@code //} and {@code /* *}{@code /} comments are kept: a comment on its own line stays above the entry that follows
 * it, a comment after an entry on the same line stays after it, and a container with a comment is always expanded. A
 * comment in any other place (between a key and its value, say) makes the file "not supported", like input that is not
 * JSON at all (several values, say): {@link #format} returns {@code null} and the file is left as it is. A trailing
 * comma is dropped. The output is parsed again and must have the same JSON value and the same comments as the input.
 */
final class JsonFormatter {

  /** A comment; {@code gap} is the number of line breaks before it, {@code inlineNext} if the next item follows it. */
  private static final class Comment {

    final String text;
    final boolean block;
    final int gap;
    boolean inlineNext;

    Comment(String text, boolean block, int gap) {
      this.text = text;
      this.block = block;
      this.gap = gap;
    }
  }

  private sealed interface Node permits Scalar, Container {
  }

  private record Scalar(String raw, boolean number) implements Node {
  }

  /** An array element (no key) or an object member. {@code blankBefore}: the source had a blank line before it. */
  private record Entry(String key, Node value, List<Comment> leading, List<Comment> trailing, boolean blankBefore) {

    boolean hasComments() {
      return !leading.isEmpty() || !trailing.isEmpty();
    }
  }

  /** An array or an object; {@code dangling} are the comments after the last entry (or all of them, if it is empty). */
  private record Container(boolean object, List<Entry> entries, List<Comment> dangling, boolean expanded)
    implements Node {
  }

  private record Document(List<Comment> before, int gapToValue, Node value, List<Comment> sameLine,
    List<Comment> after) {
  }

  /** Thrown for anything that is not supported. */
  private static final class NotJson extends Exception {
    NotJson() {
      super(null, null, false, false);
    }
  }

  private JsonFormatter() {
  }

  /** The formatted text, or {@code null} when {@code source} is not supported JSON (or formatting cannot be verified). */
  static String format(String source, int width, int indent) {
    try {
      Document document = new Parser(source.replace("\r\n", "\n")).parseDocument();
      StringBuilder out = new StringBuilder();
      new Printer(out, width, indent).printDocument(document);
      String text = out.toString();
      Document again = new Parser(text).parseDocument();

      return compact(document.value()).equals(compact(again.value())) && comments(document).equals(comments(again))
        ? text
        : null;
    } catch (NotJson | StackOverflowError e) {
      return null;
    }
  }

  // ---- printing ----

  private static final class Printer {

    private final StringBuilder out;
    private final int width;
    private final int indent;

    Printer(StringBuilder out, int width, int indent) {
      this.out = out;
      this.width = width;
      this.indent = indent;
    }

    void printDocument(Document document) {
      printComments(document.before(), document.gapToValue(), "", false);
      print(document.value(), 0, 0);

      for (Comment c : document.sameLine()) {
        out.append(' ').append(text(c, ""));
      }

      out.append('\n');

      for (Comment c : document.after()) {
        if (c.gap >= 2) {
          out.append('\n');
        }

        out.append(text(c, "")).append('\n');
      }
    }

    /** Comments in front of something: each on its own line, or in front of what follows if it is a block comment. */
    private void printComments(List<Comment> comments, int gapNext, String pad, boolean firstInBlock) {
      for (int i = 0; i < comments.size(); i++) {
        Comment c = comments.get(i);
        boolean inline = i > 0 && comments.get(i - 1).inlineNext;

        if (!inline) {
          if (c.gap >= 2 && !(i == 0 && firstInBlock)) {
            out.append('\n');
          }

          out.append(pad);
        }

        out.append(text(c, pad));
        out.append(c.inlineNext ? " " : "\n");
      }

      if (!comments.isEmpty() && gapNext >= 2 && !comments.get(comments.size() - 1).inlineNext) {
        out.append('\n');
      }
    }

    private String text(Comment c, String pad) {
      if (!c.block || c.text.indexOf('\n') < 0) {
        return c.text;
      }

      String[] lines = c.text.split("\n", -1);

      for (int i = 1; i < lines.length; i++) {
        if (!lines[i].stripLeading().startsWith("*")) {
          return c.text;
        }
      }

      StringBuilder result = new StringBuilder(lines[0]);

      for (int i = 1; i < lines.length; i++) {
        result.append('\n').append(pad).append(' ').append(lines[i].strip());
      }

      return result.toString();
    }

    private int column() {
      return out.length() - out.lastIndexOf("\n") - 1;
    }

    /** Prints {@code node} at the current position; {@code trailing} columns (a comma) must also fit. */
    void print(Node node, int level, int trailing) {
      switch (node) {
        case Scalar s -> out.append(s.raw());
        case Container c -> printContainer(c, level, trailing);
      }
    }

    private void printContainer(Container container, int level, int trailing) {
      String open = container.object() ? "{" : "[";
      String close = container.object() ? "}" : "]";

      if (container.entries().isEmpty()) {
        printEmpty(container, open, close, level);
        return;
      }

      long flat = flatLength(container);

      if (flat >= 0 && column() + flat + trailing <= width) {
        appendFlat(container);
        return;
      }

      String pad = " ".repeat((level + 1) * indent);
      out.append(open).append('\n');

      if (!container.object() && fillable(container)) {
        printFilled(container, pad);
      } else {
        printEntries(container, level, pad);
      }

      out.append(" ".repeat(level * indent)).append(close);
    }

    private void printEmpty(Container container, String open, String close, int level) {
      List<Comment> comments = container.dangling();

      if (comments.isEmpty()) {
        out.append(open).append(close);
      } else if (comments.stream().allMatch(c -> c.block && c.text.indexOf('\n') < 0) && comments.stream().allMatch(c -> c.gap == 0)) {
        out.append(open);
        out.append(String.join(" ", comments.stream().map(c -> c.text).toList()));
        out.append(close);
      } else {
        String pad = " ".repeat((level + 1) * indent);
        out.append(open).append('\n');

        for (Comment c : comments) {
          out.append(pad).append(text(c, pad)).append('\n');
        }

        out.append(" ".repeat(level * indent)).append(close);
      }
    }

    /** Prettier fills number arrays: as many numbers per line as fit. */
    private void printFilled(Container array, String pad) {
      int lineLength = 0;

      for (int i = 0; i < array.entries().size(); i++) {
        String raw = ((Scalar) array.entries().get(i).value()).raw();
        boolean last = i == array.entries().size() - 1;
        int needed = raw.length() + (last ? 0 : 1);

        if (lineLength == 0) {
          out.append(pad);
          lineLength = pad.length();
        } else if (lineLength + 1 + needed > width) {
          out.append('\n').append(pad);
          lineLength = pad.length();
        } else {
          out.append(' ');
          lineLength++;
        }

        out.append(raw);
        lineLength += raw.length();

        if (!last) {
          out.append(',');
          lineLength++;
        }
      }

      out.append('\n');
    }

    private void printEntries(Container container, int level, String pad) {
      for (int i = 0; i < container.entries().size(); i++) {
        Entry entry = container.entries().get(i);
        boolean last = i == container.entries().size() - 1;
        boolean leadingBlank = entry.blankBefore() && i > 0;

        if (entry.leading().isEmpty()) {
          if (leadingBlank) {
            out.append('\n');
          }
        } else {
          printComments(entry.leading(), 0, pad, i == 0);
        }

        boolean inline = !entry.leading().isEmpty() && entry.leading().get(entry.leading().size() - 1).inlineNext;

        if (!entry.leading().isEmpty() && !inline && entry.blankBefore()) {
          out.append('\n');
        }

        if (!inline) {
          out.append(pad);
        }

        if (container.object()) {
          out.append(entry.key()).append(": ");
        }

        print(entry.value(), level + 1, last ? 0 : 1);

        for (Comment c : entry.trailing()) {
          if (c.block) {
            out.append(' ').append(c.text);
          }
        }

        if (!last) {
          out.append(',');
        }

        for (Comment c : entry.trailing()) {
          if (!c.block) {
            out.append(' ').append(c.text);
          }
        }

        out.append('\n');
      }

      for (int i = 0; i < container.dangling().size(); i++) {
        Comment c = container.dangling().get(i);

        if (c.gap >= 2) {
          out.append('\n');
        }

        out.append(pad).append(text(c, pad)).append('\n');
      }
    }

    private static boolean fillable(Container array) {
      return array.entries().stream()
        .allMatch(e -> !e.hasComments() && e.value() instanceof Scalar s && s.number())
        && array.dangling().isEmpty();
    }

    /** Prettier always breaks an array of several objects (or arrays) that each have more than one entry. */
    private static boolean breaksAlways(Container array) {
      if (array.object() || array.entries().size() < 2) {
        return false;
      }

      boolean objects = array.entries().get(0).value() instanceof Container c && c.object();

      for (Entry entry : array.entries()) {
        if (!(entry.value() instanceof Container c) || c.object() != objects || c.entries().size() < 2) {
          return false;
        }
      }

      return true;
    }

    /** The length of {@code node} on one line, or -1 if it must break (an expanded object or a comment inside). */
    private static long flatLength(Node node) {
      if (node instanceof Scalar s) {
        return s.raw().length();
      }

      Container c = (Container) node;

      if (c.expanded() && c.object() || !c.dangling().isEmpty() || breaksAlways(c)) {
        return -1;
      }

      long total = c.entries().isEmpty() ? 2 : c.object() ? 4 : 2;

      for (int i = 0; i < c.entries().size(); i++) {
        Entry e = c.entries().get(i);

        if (e.hasComments()) {
          return -1;
        }

        long value = flatLength(e.value());

        if (value < 0) {
          return -1;
        }

        total += (c.object() ? e.key().length() + 2 : 0) + value + (i > 0 ? 2 : 0);
      }

      return total;
    }

    private void appendFlat(Node node) {
      if (node instanceof Scalar s) {
        out.append(s.raw());
        return;
      }

      Container c = (Container) node;
      out.append(c.object() ? "{ " : "[");

      for (int i = 0; i < c.entries().size(); i++) {
        if (i > 0) {
          out.append(", ");
        }

        Entry e = c.entries().get(i);

        if (c.object()) {
          out.append(e.key()).append(": ");
        }

        appendFlat(e.value());
      }

      out.append(c.object() ? " }" : "]");
    }
  }

  /** The value without any layout or comments, to compare input and output. */
  private static String compact(Node node) {
    StringBuilder out = new StringBuilder();
    compact(node, out);
    return out.toString();
  }

  private static void compact(Node node, StringBuilder out) {
    if (node instanceof Scalar s) {
      out.append(s.raw());
      return;
    }

    Container c = (Container) node;
    out.append(c.object() ? '{' : '[');

    for (Entry e : c.entries()) {
      if (c.object()) {
        out.append(e.key()).append(':');
      }

      compact(e.value(), out);
      out.append(',');
    }

    out.append(c.object() ? '}' : ']');
  }

  /** All comments in order, without whitespace (a block comment is re-indented). */
  private static List<String> comments(Document document) {
    List<String> all = new ArrayList<>();
    document.before().forEach(c -> all.add(c.text.replaceAll("\\s+", "")));
    collect(document.value(), all);
    document.sameLine().forEach(c -> all.add(c.text.replaceAll("\\s+", "")));
    document.after().forEach(c -> all.add(c.text.replaceAll("\\s+", "")));
    return all;
  }

  private static void collect(Node node, List<String> all) {
    if (node instanceof Container c) {
      for (Entry e : c.entries()) {
        e.leading().forEach(x -> all.add(x.text.replaceAll("\\s+", "")));
        collect(e.value(), all);
        e.trailing().forEach(x -> all.add(x.text.replaceAll("\\s+", "")));
      }

      c.dangling().forEach(x -> all.add(x.text.replaceAll("\\s+", "")));
    }
  }

  // ---- parsing ----

  /** The comments in a stretch of whitespace, and the line breaks between the last one and what comes next. */
  private record Trivia(List<Comment> comments, int gapNext) {
  }

  private static final class Parser {

    private final String text;
    private int pos;

    Parser(String text) {
      this.text = text;
    }

    Document parseDocument() throws NotJson {
      Trivia before = trivia();
      Node value = parseValue();
      Trivia after = trivia();

      if (pos != text.length()) {
        throw new NotJson();
      }

      List<Comment> rest = new ArrayList<>(after.comments());
      List<Comment> sameLine = new ArrayList<>();
      takeSameLine(rest, sameLine);
      return new Document(before.comments(), before.gapNext(), value, sameLine, rest);
    }

    private Node parseValue() throws NotJson {
      if (pos >= text.length()) {
        throw new NotJson();
      }

      char c = text.charAt(pos);

      return switch (c) {
        case '{' -> parseContainer(true);
        case '[' -> parseContainer(false);
        case '"' -> new Scalar(parseString(), false);
        case 't' -> literal("true");
        case 'f' -> literal("false");
        case 'n' -> literal("null");
        default -> parseNumber();
      };
    }

    private Node literal(String word) throws NotJson {
      if (!text.startsWith(word, pos)) {
        throw new NotJson();
      }

      pos += word.length();
      return new Scalar(word, false);
    }

    private Node parseNumber() throws NotJson {
      int start = pos;

      if (peek() == '-') {
        pos++;
      }

      if (peek() == '0') {
        pos++;
      } else if (peek() >= '1' && peek() <= '9') {
        digits();
      } else {
        throw new NotJson();
      }

      if (peek() == '.') {
        pos++;

        if (digits() == 0) {
          throw new NotJson();
        }
      }

      if (peek() == 'e' || peek() == 'E') {
        pos++;

        if (peek() == '+' || peek() == '-') {
          pos++;
        }

        if (digits() == 0) {
          throw new NotJson();
        }
      }

      return new Scalar(text.substring(start, pos), true);
    }

    private int digits() {
      int start = pos;

      while (peek() >= '0' && peek() <= '9') {
        pos++;
      }

      return pos - start;
    }

    private String parseString() throws NotJson {
      int start = pos;
      pos++;

      while (true) {
        if (pos >= text.length()) {
          throw new NotJson();
        }

        char c = text.charAt(pos++);

        if (c == '"') {
          return text.substring(start, pos);
        }

        if (c < 0x20) {
          throw new NotJson();
        }

        if (c == '\\') {
          if (pos >= text.length()) {
            throw new NotJson();
          }

          char escaped = text.charAt(pos++);

          if ("\"\\/bfnrt".indexOf(escaped) < 0) {
            if (escaped != 'u' || pos + 4 > text.length()
              || !text.substring(pos, pos + 4).chars().allMatch(ch -> Character.digit(ch, 16) >= 0)) {
              throw new NotJson();
            }

            pos += 4;
          }
        }
      }
    }

    /** An array or object; {@code pos} is at the opening bracket. */
    private Node parseContainer(boolean object) throws NotJson {
      char close = object ? '}' : ']';
      pos++;
      boolean expanded = false;

      for (int i = pos; i < text.length() && " \t\r\n".indexOf(text.charAt(i)) >= 0; i++) {
        expanded |= text.charAt(i) == '\n';
      }

      List<Entry> entries = new ArrayList<>();
      Trivia trivia = trivia();
      List<Comment> carry = trivia.comments();
      int gap = trivia.gapNext();

      while (true) {
        if (entries.isEmpty() && peek() == close) {
          pos++;
          return new Container(object, entries, carry, false);
        }

        String key = null;

        if (object) {
          if (peek() != '"') {
            throw new NotJson();
          }

          key = parseString();

          if (!trivia().comments().isEmpty() || peek() != ':') {
            throw new NotJson();
          }

          pos++;

          if (!trivia().comments().isEmpty()) {
            throw new NotJson();
          }
        }

        Node value = parseValue();
        Trivia afterValue = trivia();
        List<Comment> rest = new ArrayList<>(afterValue.comments());
        List<Comment> trailing = new ArrayList<>();
        takeSameLine(rest, trailing);
        Entry entry = new Entry(key, value, carry, trailing, gap >= 2);
        entries.add(entry);
        char c = peek();
        pos++;

        if (c == close) {
          return new Container(object, entries, rest, expanded);
        }

        if (c != ',') {
          throw new NotJson();
        }

        Trivia afterComma = trivia();
        List<Comment> next = new ArrayList<>(afterComma.comments());

        if (rest.isEmpty()) {
          takeSameLine(next, trailing);
        }

        rest.addAll(next);
        carry = rest;
        gap = afterComma.gapNext();

        if (peek() == close) {
          // a trailing comma is dropped
          pos++;
          return new Container(object, entries, carry, expanded);
        }
      }
    }

    /** Moves the comments at the front that are on the same line as what came before. */
    private static void takeSameLine(List<Comment> from, List<Comment> to) {
      while (!from.isEmpty() && from.get(0).gap == 0) {
        to.add(from.remove(0));
      }
    }

    /** Skips whitespace and collects the comments in it. */
    private Trivia trivia() throws NotJson {
      List<Comment> comments = new ArrayList<>();
      int gap = 0;

      while (pos < text.length()) {
        char c = text.charAt(pos);

        if (c == '\n') {
          gap++;
          pos++;
        } else if (c == ' ' || c == '\t' || c == '\r') {
          pos++;
        } else if (c == '/' && pos + 1 < text.length() && text.charAt(pos + 1) == '/') {
          int end = text.indexOf('\n', pos);
          end = end < 0 ? text.length() : end;
          comments.add(new Comment(text.substring(pos, end).stripTrailing(), false, gap));
          gap = 0;
          pos = end;
        } else if (c == '/' && pos + 1 < text.length() && text.charAt(pos + 1) == '*') {
          int end = text.indexOf("*/", pos + 2);

          if (end < 0) {
            throw new NotJson();
          }

          comments.add(new Comment(text.substring(pos, end + 2), true, gap));
          gap = 0;
          pos = end + 2;
        } else {
          break;
        }
      }

      for (int i = 0; i < comments.size(); i++) {
        int next = i + 1 < comments.size() ? comments.get(i + 1).gap : gap;
        comments.get(i).inlineNext = comments.get(i).block && next == 0 && pos < text.length();
      }

      return new Trivia(comments, gap);
    }

    private char peek() {
      return pos < text.length() ? text.charAt(pos) : '\0';
    }
  }
}
