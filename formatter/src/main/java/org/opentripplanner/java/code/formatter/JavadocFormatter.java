package org.opentripplanner.java.code.formatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats doc comments that start their own line, in both styles: the classic {@code /** ... *}{@code /} (HTML) and the
 * Markdown {@code ///} (JEP 467, a run of adjacent {@code ///} lines).
 * <p>
 * In both, text is reflowed: each line is filled up to the column limit, lines broken too early are joined, and
 * over-long lines are wrapped. An inline tag ({@code {@link ...}}) is never broken, a token too long for a line stands
 * alone on one, and one blank line separates the description from the block tags ({@code @param}, {@code @return},
 * ...), whose text continues on lines indented four spaces.
 * <p>
 * Classic comments: paragraphs are separated by a {@code <p>} line (alone on its line, no blank line before it) - a
 * blank line between two paragraphs becomes {@code <p>}, and {@code <p>Text} becomes {@code <p>} and {@code Text}.
 * {@code <ul>} and {@code <ol>} are lists alike: an item's text continues on lines indented four spaces more than its
 * {@code <li>}. {@code <pre>} blocks, tables, definition lists and block quotes are copied as they are.
 * <p>
 * Markdown comments: paragraphs are separated by a blank line (never {@code <p>}), list items ({@code -}, {@code *},
 * {@code +}, {@code 1.}) continue aligned under their text, backtick code spans are never broken, and fenced and
 * indented code, headings, tables, block quotes, thematic breaks and paragraphs with hard line breaks are copied as
 * they are.
 * <p>
 * Inline tags that span lines are copied as they are too. A comment is rewritten only if the result has exactly the
 * same words as the original (ignoring {@code <p>} markers and whitespace); otherwise it is left untouched.
 */
final class JavadocFormatter {

  private static final Set<String> BLOCK_TAGS = Set.of("param", "return", "throws", "exception", "see", "since",
    "deprecated", "author", "version", "serial", "serialField", "serialData", "hidden", "apiNote", "implSpec",
    "implNote", "provides", "uses", "spec");

  private static final Pattern HTML_VERBATIM_OPEN = Pattern.compile("(?i)^<(pre|table|dl|blockquote)\\b.*");
  private static final Pattern HTML_HEADING = Pattern.compile("(?i)^<h[1-6]\\b.*");
  private static final Pattern HTML_STRUCTURAL = Pattern.compile(
    "(?i)^</?(h[1-6]|hr|tr|th|td|dt|dd|thead|tbody|tfoot|caption|div)\\b.*"
  );
  private static final Pattern HTML_LIST = Pattern.compile("(?i)^</?(ul|ol)\\b.*");
  private static final Pattern HTML_LIST_ITEM = Pattern.compile("(?i)^<li\\b.*");
  private static final Pattern PARAGRAPH_TAG = Pattern.compile("(?i)<p\\s*/?>");
  private static final Pattern PARAGRAPH_CLOSE = Pattern.compile("(?i)</p\\s*>");
  private static final Pattern HTML_PARAGRAPH = Pattern.compile("(?i)^<p\\s*/?>(.*)");
  private static final Pattern MD_LIST_ITEM = Pattern.compile("^(\\s*)([-*+]|\\d{1,9}[.)])\\s+(\\S.*)$");
  private static final Pattern MD_FENCE = Pattern.compile("^\\s*(```|~~~).*");
  private static final Pattern MD_HEADING = Pattern.compile("^\\s{0,3}#{1,6}(\\s.*)?$");
  private static final Pattern MD_THEMATIC_BREAK = Pattern.compile("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$");
  private static final int TAG_HANGING_INDENT = 4;
  private static final int LIST_ITEM_HANGING_INDENT = 4;

  private JavadocFormatter() {
  }

  /**
   * Which comment rules apply (see {@link FormatterConfig#COMMENT_RULES}). {@code fill} refills every line and so
   * includes the other four; without it, lines are kept as they are and only the rules that are on change them.
   */
  record Rules(boolean wrap, boolean paragraphs, boolean blankLines, boolean indent, boolean fill) {

    static final Rules ALL = of(FormatterConfig.COMMENT_RULES);

    static Rules of(java.util.Set<String> names) {
      boolean fill = names.contains("fill");

      return new Rules(fill || names.contains("wrap"), fill || names.contains("paragraphs"),
        fill || names.contains("blank-lines"), fill || names.contains("indent"), fill);
    }

    boolean any() {
      return wrap || paragraphs || blankLines || indent || fill;
    }
  }

  private record Edit(int start, int end, String replacement) {
  }

  static String format(String source, int wrapAtColumn, Rules rules) {
    LiteralScanner.Scan scan = LiteralScanner.scan(source);
    List<Edit> edits = new ArrayList<>();
    List<LiteralScanner.VerbatimSpan> spans = scan.spans();

    for (int s = 0; s < spans.size(); s++) {
      LiteralScanner.VerbatimSpan span = spans.get(s);
      int lineStart = source.lastIndexOf('\n', span.start() - 1) + 1;
      String indent = source.substring(lineStart, span.start());

      if (!indent.isBlank()) {
        // code (or an earlier comment) shares the line
        continue;
      }

      if (span.kind() == LiteralScanner.Kind.BLOCK_COMMENT && source.startsWith("/**", span.start())
        && !source.startsWith("/**/", span.start())) {
        int newline = source.indexOf('\n', span.end());
        int lineEnd = newline == -1 ? source.length() : newline;

        if (!source.substring(span.end(), lineEnd).isBlank()) {
          continue;
        }

        String comment = source.substring(span.start(), span.end());
        String formatted = formatClassic(comment, indent, wrapAtColumn, rules);

        if (formatted != null && !formatted.equals(comment)) {
          edits.add(new Edit(span.start(), span.end(), formatted));
        }
      } else if (span.kind() == LiteralScanner.Kind.LINE_COMMENT && isMarkdownLine(source, span)) {
        int last = s;

        while (last + 1 < spans.size() && continuesRun(source, spans.get(last), spans.get(last + 1), indent)) {
          last++;
        }

        int end = spans.get(last).end();
        String comment = source.substring(span.start(), end);
        String formatted = formatMarkdown(comment, indent, wrapAtColumn, rules);

        if (formatted != null && !formatted.equals(comment)) {
          edits.add(new Edit(span.start(), end, formatted));
        }

        s = last;
      }
    }

    if (edits.isEmpty()) {
      return source;
    }

    StringBuilder out = new StringBuilder(source.length() + 256);
    int copied = 0;

    for (Edit edit : edits) {
      out.append(source, copied, edit.start()).append(edit.replacement());
      copied = edit.end();
    }

    return out.append(source, copied, source.length()).toString();
  }

  private static boolean isMarkdownLine(String source, LiteralScanner.VerbatimSpan span) {
    return source.startsWith("///", span.start()) && !source.startsWith("////", span.start());
  }

  /** Whether {@code next} is a {@code ///} line directly below {@code previous}, at the same indent. */
  private static boolean continuesRun(String source, LiteralScanner.VerbatimSpan previous,
    LiteralScanner.VerbatimSpan next, String indent) {
    if (next.kind() != LiteralScanner.Kind.LINE_COMMENT || !isMarkdownLine(source, next)) {
      return false;
    }

    int lineBreak = source.indexOf('\n', previous.start());

    return lineBreak != -1 && lineBreak < next.start() && source.substring(lineBreak + 1, next.start()).equals(indent);
  }

  // ---- classic /** ... */ ----

  /** The reformatted comment, or {@code null} if it should be left as it is. */
  private static String formatClassic(String comment, String indent, int wrapAtColumn, Rules rules) {
    String eol = comment.contains("\r\n") ? "\r\n" : "\n";
    String[] rawLines = comment.split("\r?\n", -1);
    boolean singleLine = rawLines.length == 1;
    List<String> content = classicContent(rawLines, rules.blankLines());

    if (content.stream().allMatch(String::isBlank)) {
      return null;
    }

    List<String> formatted = layoutClassic(content, wrapAtColumn - indent.length() - 3, rules);

    if (formatted == null || !sameWords(content, formatted) || !rules.fill() && formatted.equals(content)) {
      return null;
    }

    boolean fits = indent.length() + 4 + (formatted.size() == 1 ? formatted.get(0).length() : 0) + 3 <= wrapAtColumn;

    if (formatted.size() == 1 && singleLine && (fits || !(rules.wrap() || rules.fill()))) {
      return "/** " + formatted.get(0) + " */";
    }

    StringBuilder out = new StringBuilder("/**");

    for (String line : formatted) {
      out.append(eol).append(indent).append(line.isEmpty() ? " *" : " * " + line);
    }

    return out.append(eol).append(indent).append(" */").toString();
  }

  /** The comment's text lines: no {@code /**}, {@code *}/}, or leading {@code *}; blank edges trimmed if asked. */
  private static List<String> classicContent(String[] rawLines, boolean trimEdges) {
    List<String> content = new ArrayList<>();

    for (int i = 0; i < rawLines.length; i++) {
      String line = rawLines[i];

      if (i == 0) {
        line = line.substring(3);
      }

      if (i == rawLines.length - 1) {
        line = line.substring(0, line.length() - 2);
      }

      if (i > 0) {
        String trimmed = line.stripLeading();
        line = trimmed.startsWith("*") ? trimmed.substring(1) : trimmed;
      }

      String text = (line.startsWith(" ") ? line.substring(1) : line).stripTrailing();

      if (text.isBlank() && (i == 0 || i == rawLines.length - 1)) {
        // nothing after the opening /** or before the closing */: part of the comment's shell
        continue;
      }

      content.add(text);
    }

    return trimEdges ? trimBlankEdges(content) : content;
  }

  private static List<String> trimBlankEdges(List<String> content) {
    while (!content.isEmpty() && content.get(0).isBlank()) {
      content.remove(0);
    }

    while (!content.isEmpty() && content.get(content.size() - 1).isBlank()) {
      content.remove(content.size() - 1);
    }

    return content;
  }

  private enum Kind {
    NONE, TEXT, VERBATIM, STRUCTURE
  }

  /** What a run of text lines is: a plain paragraph, the text of a block tag, or the text of a list item. */
  private enum Element {
    PARAGRAPH, TAG, ITEM
  }

  /** Lays out classic content lines within {@code available} columns; {@code null} if it can't safely. */
  private static List<String> layoutClassic(List<String> content, int available, Rules rules) {
    List<String> out = new ArrayList<>();
    List<String> paragraph = new ArrayList<>();
    Kind previous = Kind.NONE;
    // "", "blank" or "p": what separated the next element from the previous one; blanks counts the blank lines
    String separator = "";
    int blanks = 0;
    boolean inTags = false;
    int i = 0;

    while (i < content.size()) {
      String line = content.get(i);
      String trimmed = line.strip();
      boolean closingTag = rules.paragraphs() && PARAGRAPH_CLOSE.matcher(trimmed).matches();

      if (trimmed.isEmpty() || closingTag) {
        boolean flushed = !paragraph.isEmpty();
        String before = separator;
        previous = flushParagraph(paragraph, out, previous, separator, blanks, available, true, rules);
        separator = before.equals("p") ? "p" : "blank";
        blanks = (flushed ? 0 : blanks) + (trimmed.isEmpty() ? 1 : 0);
        i++;
        continue;
      }

      Matcher p = HTML_PARAGRAPH.matcher(trimmed);

      if (rules.paragraphs() && !inTags && p.matches()) {
        previous = flushParagraph(paragraph, out, previous, separator, blanks, available, true, rules);
        separator = "p";
        blanks = 0;

        if (!p.group(1).isBlank()) {
          paragraph.add(p.group(1).strip());
        }

        i++;
        continue;
      }

      if (blockTag(trimmed) != null) {
        boolean flushed = !paragraph.isEmpty();
        previous = flushParagraph(paragraph, out, previous, separator, blanks, available, true, rules);

        if (flushed) {
          separator = "";
          blanks = 0;
        }

        if (!inTags) {
          inTags = true;

          if (rules.blankLines()) {
            if (!out.isEmpty() && !out.get(out.size() - 1).isEmpty()) {
              out.add("");
            }
          } else {
            emitBlockSeparator(out, previous, separator, blanks, rules);
          }
        } else if (!rules.blankLines()) {
          emitBlockSeparator(out, previous, separator, blanks, rules);
        }

        List<String> block = new ArrayList<>();
        block.add(line);
        i++;

        while (i < content.size() && !content.get(i).isBlank() && blockTag(content.get(i).strip()) == null) {
          block.add(content.get(i));
          i++;
        }

        if (!reflowInto(out, block, available, 0, TAG_HANGING_INDENT, false, true, rules, Element.TAG)) {
          return null;
        }

        separator = "";
        blanks = 0;
        previous = Kind.TEXT;
        continue;
      }

      if (inTags) {
        // Text after a blank line in the tag section: keep it as it was, and keep the blank line, or the next
        // pass would take it for a continuation of the tag above.
        if (!separator.isEmpty() && !out.isEmpty()) {
          if (!rules.blankLines()) {
            addBlanks(out, blanks);
          } else if (!out.get(out.size() - 1).isEmpty()) {
            out.add("");
          }
        }

        separator = "";
        blanks = 0;
        out.add(line);
        i++;
        continue;
      }

      boolean verbatimOpen = HTML_VERBATIM_OPEN.matcher(trimmed).matches();

      if (verbatimOpen || HTML_STRUCTURAL.matcher(trimmed).matches() || HTML_LIST.matcher(trimmed).matches()) {
        boolean flushed = !paragraph.isEmpty();
        previous = flushParagraph(paragraph, out, previous, separator, blanks, available, true, rules);

        if (flushed) {
          // the paragraph used it; nothing separates it from what follows
          separator = "";
          blanks = 0;
        }

        if (rules.blankLines() && HTML_HEADING.matcher(trimmed).matches()) {
          // a heading starts a section: one blank line before it (never a <p>), unless it comes first
          if (!out.isEmpty() && !out.get(out.size() - 1).isEmpty()) {
            out.add("");
          }
        } else {
          emitBlockSeparator(out, previous, separator, blanks, rules);
        }

        separator = "";
        blanks = 0;
        out.add(line);
        i++;

        if (verbatimOpen) {
          String name = trimmed.substring(1).split("[\\s>]")[0].toLowerCase();
          String close = "</" + name + ">";

          if (!trimmed.toLowerCase().contains(close)) {
            while (i < content.size() && !content.get(i).toLowerCase().contains(close)) {
              out.add(content.get(i));
              i++;
            }

            if (i < content.size()) {
              out.add(content.get(i));
              i++;
            }
          }
        }

        previous = HTML_LIST.matcher(trimmed).matches() ? Kind.STRUCTURE : Kind.VERBATIM;
        continue;
      }

      if (HTML_LIST_ITEM.matcher(trimmed).matches()) {
        boolean flushed = !paragraph.isEmpty();
        previous = flushParagraph(paragraph, out, previous, separator, blanks, available, true, rules);

        if (flushed) {
          // the paragraph used it; nothing separates it from what follows
          separator = "";
          blanks = 0;
        }

        emitBlockSeparator(out, previous, separator, blanks, rules);
        separator = "";
        blanks = 0;
        int itemIndent = line.length() - line.stripLeading().length();
        List<String> item = new ArrayList<>();
        item.add(line);
        i++;

        while (i < content.size() && continuesItem(content.get(i).strip())) {
          item.add(content.get(i));
          i++;
        }

        if (!reflowInto(out, item, available, itemIndent, itemIndent + LIST_ITEM_HANGING_INDENT, false, true, rules,
          Element.ITEM)) {
          return null;
        }

        previous = Kind.STRUCTURE;
        continue;
      }

      if (inlineTagOpen(line)) {
        boolean flushed = !paragraph.isEmpty();
        previous = flushParagraph(paragraph, out, previous, separator, blanks, available, true, rules);

        if (flushed) {
          // the paragraph used it; nothing separates it from what follows
          separator = "";
          blanks = 0;
        }

        emitSeparator(out, previous, separator, blanks, true, rules);
        separator = "";
        blanks = 0;
        StringBuilder spanned = new StringBuilder();

        do {
          out.add(content.get(i));
          spanned.append(content.get(i)).append('\n');
          i++;
        } while (i < content.size() && inlineTagOpen(spanned.toString()));

        previous = Kind.VERBATIM;
        continue;
      }

      paragraph.add(line);
      i++;
    }

    boolean pending = !paragraph.isEmpty();
    flushParagraph(paragraph, out, previous, separator, blanks, available, true, rules);

    if (rules.blankLines()) {
      return trimTrailingBlanks(out);
    }

    if (!pending && separator.equals("blank")) {
      // trailing blank lines stay unless the blank-lines rule trims them
      addBlanks(out, blanks);
    }

    return out;
  }

  private static boolean continuesItem(String trimmed) {
    return !trimmed.isEmpty() && !HTML_LIST_ITEM.matcher(trimmed).matches() && !HTML_LIST.matcher(trimmed).matches()
      && !HTML_PARAGRAPH.matcher(trimmed).matches() && blockTag(trimmed) == null
      && !HTML_VERBATIM_OPEN.matcher(trimmed).matches();
  }

  private static List<String> trimTrailingBlanks(List<String> out) {
    while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
      out.remove(out.size() - 1);
    }

    return out;
  }

  private static void addBlanks(List<String> out, int count) {
    for (int i = 0; i < count; i++) {
      out.add("");
    }
  }

  /** Emits the pending paragraph (reflowed) with the separator before it; returns the new previous kind. */
  private static Kind flushParagraph(List<String> paragraph, List<String> out, Kind previous, String separator,
    int blanks, int available, boolean classic, Rules rules) {
    if (paragraph.isEmpty()) {
      return previous;
    }

    if (classic && rules.paragraphs() && previous == Kind.TEXT && !separator.isEmpty()) {
      // a blank line between two paragraphs is written as <p>
      out.add("<p>");
    } else {
      emitSeparator(out, previous, separator, blanks, classic, rules);
    }

    List<String> lines = new ArrayList<>(paragraph);
    paragraph.clear();
    reflowInto(out, lines, available, 0, 0, !classic, classic && rules.paragraphs(), rules, Element.PARAGRAPH);

    return Kind.TEXT;
  }

  /**
   * The separator before an HTML block ({@code <ul>}, {@code <pre>}, ...): a blank line stays, a {@code <p>} is
   * dropped.
   */
  private static void emitBlockSeparator(List<String> out, Kind previous, String separator, int blanks, Rules rules) {
    if (!separator.equals("blank")) {
      return;
    }

    if (previous != Kind.NONE) {
      addBlanks(out, rules.blankLines() ? 1 : blanks);
    } else if (!rules.blankLines()) {
      addBlanks(out, blanks);
    }
  }

  private static void emitSeparator(List<String> out, Kind previous, String separator, int blanks, boolean classic,
    Rules rules) {
    if (separator.isEmpty()) {
      return;
    }

    if (previous == Kind.NONE) {
      if (!rules.blankLines() && separator.equals("blank")) {
        // leading blank lines stay unless the blank-lines rule trims them
        addBlanks(out, blanks);
      }

      return;
    }

    if (classic && rules.paragraphs() && separator.equals("p")) {
      out.add("<p>");
    } else {
      addBlanks(out, rules.blankLines() ? 1 : blanks);
    }
  }

  // ---- Markdown /// ----

  private static String formatMarkdown(String comment, String indent, int wrapAtColumn, Rules rules) {
    String eol = comment.contains("\r\n") ? "\r\n" : "\n";
    List<String> content = new ArrayList<>();

    for (String raw : comment.split("\r?\n", -1)) {
      String line = raw.stripLeading().substring(3);
      content.add((line.startsWith(" ") ? line.substring(1) : line).stripTrailing());
    }

    if (rules.blankLines()) {
      content = trimBlankEdges(content);
    }

    if (content.stream().allMatch(String::isBlank)) {
      return null;
    }

    List<String> formatted = layoutMarkdown(content, wrapAtColumn - indent.length() - 4, rules);

    if (formatted == null || !sameWords(content, formatted) || !rules.fill() && formatted.equals(content)) {
      return null;
    }

    StringBuilder out = new StringBuilder();

    for (int i = 0; i < formatted.size(); i++) {
      if (i > 0) {
        out.append(eol).append(indent);
      }

      out.append(formatted.get(i).isEmpty() ? "///" : "/// " + formatted.get(i));
    }

    return out.toString();
  }

  private static List<String> layoutMarkdown(List<String> content, int available, Rules rules) {
    List<String> out = new ArrayList<>();
    List<String> paragraph = new ArrayList<>();
    boolean inTags = false;
    int i = 0;

    while (i < content.size()) {
      String line = content.get(i);
      String trimmed = line.strip();

      if (trimmed.isEmpty()) {
        if (!flushMarkdownParagraph(paragraph, out, available, rules)) {
          return null;
        }

        if (!rules.blankLines()) {
          out.add("");
        } else if (!out.isEmpty() && !out.get(out.size() - 1).isEmpty()) {
          out.add("");
        }

        i++;
        continue;
      }

      boolean fence = MD_FENCE.matcher(line).matches();
      boolean indentedCode = !inTags && line.startsWith("    ") && paragraph.isEmpty()
        && (out.isEmpty() || out.get(out.size() - 1).isEmpty() || out.get(out.size() - 1).startsWith("    "));

      if (fence || indentedCode || MD_HEADING.matcher(line).matches() || MD_THEMATIC_BREAK.matcher(line).matches()
        || trimmed.startsWith("|") || trimmed.startsWith(">")) {
        if (!flushMarkdownParagraph(paragraph, out, available, rules)) {
          return null;
        }

        if (rules.blankLines() && MD_HEADING.matcher(line).matches() && !out.isEmpty()
          && !out.get(out.size() - 1).isEmpty()) {
          // a heading starts a section: one blank line before it
          out.add("");
        }

        out.add(line);
        i++;

        if (fence) {
          String marker = line.strip().substring(0, 3);

          while (i < content.size()) {
            out.add(content.get(i));
            boolean closes = content.get(i).strip().startsWith(marker);
            i++;

            if (closes) {
              break;
            }
          }
        } else {
          while (i < content.size() && !content.get(i).isBlank()
            && (trimmed.startsWith("|") || trimmed.startsWith(">") || indentedCode
              ? content.get(i).strip().startsWith(trimmed.startsWith("|") ? "|" : trimmed.startsWith(">") ? ">" : "")
                || indentedCode && content.get(i).startsWith("    ")
              : false)) {
            out.add(content.get(i));
            i++;
          }
        }

        continue;
      }

      if (blockTag(trimmed) != null) {
        if (!flushMarkdownParagraph(paragraph, out, available, rules)) {
          return null;
        }

        if (!inTags) {
          inTags = true;

          if (rules.blankLines() && !out.isEmpty() && !out.get(out.size() - 1).isEmpty()) {
            out.add("");
          }
        }

        List<String> block = new ArrayList<>();
        block.add(line);
        i++;

        while (i < content.size() && !content.get(i).isBlank() && blockTag(content.get(i).strip()) == null) {
          block.add(content.get(i));
          i++;
        }

        if (!reflowInto(out, block, available, 0, TAG_HANGING_INDENT, true, false, rules, Element.TAG)) {
          return null;
        }

        continue;
      }

      Matcher item = MD_LIST_ITEM.matcher(line);

      if (!inTags && item.matches()) {
        if (!flushMarkdownParagraph(paragraph, out, available, rules)) {
          return null;
        }

        int itemIndent = item.group(1).length();
        String marker = item.group(2);
        List<String> text = new ArrayList<>();
        text.add(rules.fill() ? marker + " " + item.group(3).strip() : line);
        i++;

        while (i < content.size() && !content.get(i).isBlank() && !MD_LIST_ITEM.matcher(content.get(i)).matches()
          && !MD_FENCE.matcher(content.get(i)).matches() && !MD_HEADING.matcher(content.get(i)).matches()
          && blockTag(content.get(i).strip()) == null) {
          text.add(content.get(i));
          i++;
        }

        if (!reflowInto(out, text, available, itemIndent, itemIndent + marker.length() + 1, true, false, rules,
          Element.ITEM)) {
          return null;
        }

        continue;
      }

      if (inTags) {
        out.add(line);
        i++;
        continue;
      }

      paragraph.add(line);
      i++;
    }

    if (!flushMarkdownParagraph(paragraph, out, available, rules)) {
      return null;
    }

    return rules.blankLines() ? trimTrailingBlanks(out) : out;
  }

  /** Reflows the pending Markdown paragraph, or copies it if it has hard line breaks; false if it can't. */
  private static boolean flushMarkdownParagraph(List<String> paragraph, List<String> out, int available, Rules rules) {
    if (paragraph.isEmpty()) {
      return true;
    }

    List<String> lines = new ArrayList<>(paragraph);
    paragraph.clear();
    boolean hardBreak = lines.stream().limit(Math.max(0, lines.size() - 1)).anyMatch(l -> l.endsWith("\\"));

    if (hardBreak) {
      out.addAll(lines);
      return true;
    }

    return reflowInto(out, lines, available, 0, 0, true, false, rules, Element.PARAGRAPH);
  }

  // ---- shared ----

  /**
   * Appends the text lines of one element. With {@code fill}, they are joined and refilled up to the limit; without it,
   * each line is kept and only the rules that are on touch it. False if a token would be altered.
   */
  private static boolean reflowInto(List<String> out, List<String> lines, int available, int firstIndent,
    int hangingIndent, boolean markdown, boolean paragraphTags, Rules rules, Element element) {
    if (!rules.fill()) {
      return keepLines(out, lines, available, hangingIndent, markdown, paragraphTags, rules, element);
    }

    String joined = String.join(" ", lines.stream().map(String::strip).toList());

    if (paragraphTags && joined.toLowerCase().contains("<pre")) {
      // verbatim text in the middle of a line: leave the whole comment as it is
      return false;
    }

    List<String> tokens = tokenize(joined, markdown, paragraphTags);

    if (tokens == null) {
      return false;
    }

    StringBuilder current = new StringBuilder(" ".repeat(firstIndent));
    boolean empty = true;
    boolean paragraphBreak = false;

    for (String token : tokens) {
      if (paragraphTags && token.equals("<p>")) {
        // any form of the paragraph tag in the middle of the text: it ends the line and stands alone
        if (!empty) {
          out.add(current.toString());
          current = new StringBuilder(" ".repeat(hangingIndent));
          empty = true;
        }

        paragraphBreak = true;
        continue;
      }

      if (paragraphBreak) {
        paragraphBreak = false;

        if (!out.isEmpty() && !out.get(out.size() - 1).strip().equals("<p>")) {
          out.add(" ".repeat(hangingIndent) + "<p>");
        }
      }

      if (!empty && current.length() + 1 + token.length() > available) {
        out.add(current.toString());
        current = new StringBuilder(" ".repeat(hangingIndent));
        empty = true;
      }

      if (!empty) {
        current.append(' ');
      }

      current.append(token);
      empty = false;
    }

    if (!empty) {
      out.add(current.toString());
    }

    return true;
  }

  /** Keeps each line of an element as it is, applying only the rules that are on. */
  private static boolean keepLines(List<String> out, List<String> lines, int available, int hangingIndent,
    boolean markdown, boolean paragraphTags, Rules rules, Element element) {
    for (int index = 0; index < lines.size(); index++) {
      String line = lines.get(index).stripTrailing();

      if (!markdown && line.toLowerCase().contains("<pre")) {
        // verbatim text in the middle of a line: leave the whole comment as it is
        return false;
      }

      List<String> pieces = List.of(line);

      if (rules.paragraphs() && paragraphTags) {
        pieces = splitAtParagraphTags(line, element, hangingIndent);

        if (pieces == null) {
          return false;
        }
      }

      for (int pieceIndex = 0; pieceIndex < pieces.size(); pieceIndex++) {
        String text = pieces.get(pieceIndex);
        boolean paragraphTag = text.strip().equals("<p>");

        if (paragraphTag && !out.isEmpty() && out.get(out.size() - 1).strip().equals("<p>")) {
          continue;
        }

        if (rules.indent() && element != Element.PARAGRAPH && (index > 0 || pieceIndex > 0) && !text.isBlank()) {
          text = " ".repeat(hangingIndent) + text.strip();
        }

        if (rules.wrap() && !paragraphTag) {
          out.addAll(wrapLine(text, available, markdown, element, index == 0 && pieceIndex == 0, hangingIndent));
        } else {
          out.add(text);
        }
      }
    }

    return true;
  }

  /**
   * {@code line} cut at each paragraph tag (any form), which goes on a line of its own; a closing tag is dropped. The
   * line itself if it has none; {@code null} if it can't safely be tokenized.
   */
  private static List<String> splitAtParagraphTags(String line, Element element, int hangingIndent) {
    List<String> tokens = tokenize(line.strip(), false, true);

    if (tokens == null) {
      return null;
    }

    boolean changed = tokens.contains("<p>")
      || !String.join("", tokens).replaceAll("\\s+", "").equals(line.replaceAll("\\s+", ""));

    if (!changed) {
      return List.of(line);
    }

    String lead = line.substring(0, line.length() - line.stripLeading().length());
    String tagPrefix = element == Element.PARAGRAPH ? "" : " ".repeat(hangingIndent);
    String restPrefix = element == Element.PARAGRAPH ? lead : tagPrefix;
    List<String> pieces = new ArrayList<>();
    StringBuilder current = new StringBuilder(lead);
    boolean empty = true;

    for (String token : tokens) {
      if (token.equals("<p>")) {
        if (!empty) {
          pieces.add(current.toString());
        }

        pieces.add(tagPrefix + "<p>");
        current = new StringBuilder(restPrefix);
        empty = true;
        continue;
      }

      if (!empty) {
        current.append(' ');
      }

      current.append(token);
      empty = false;
    }

    if (!empty) {
      pieces.add(current.toString());
    }

    return pieces;
  }

  /** {@code text} split at spaces if it is longer than {@code available}; each rest line continues the indent. */
  private static List<String> wrapLine(String text, int available, boolean markdown, Element element, boolean first,
    int hangingIndent) {
    if (text.length() <= available) {
      return List.of(text);
    }

    String body = text.stripLeading();
    String lead = text.substring(0, text.length() - body.length());
    List<String> tokens = tokenize(body, markdown, false);

    if (tokens == null || tokens.size() < 2) {
      return List.of(text);
    }

    String restPrefix = element != Element.PARAGRAPH && first ? " ".repeat(hangingIndent) : lead;
    List<String> lines = new ArrayList<>();
    StringBuilder current = new StringBuilder(lead);
    boolean empty = true;

    for (String token : tokens) {
      if (!empty && current.length() + 1 + token.length() > available) {
        lines.add(current.toString());
        current = new StringBuilder(restPrefix);
        empty = true;
      }

      if (!empty) {
        current.append(' ');
      }

      current.append(token);
      empty = false;
    }

    lines.add(current.toString());

    return lines;
  }

  /**
   * Splits text at whitespace, keeping {@code {@...}} inline tags in one piece, HTML tags ({@code <...>}) in one piece,
   * and (Markdown) backtick code spans in one piece.
   */
  private static List<String> tokenize(String text, boolean markdown, boolean paragraphTags) {
    List<String> tokens = new ArrayList<>();
    StringBuilder token = new StringBuilder();
    int braces = 0;
    boolean tag = false;
    int backticks = 0;

    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);

      if (paragraphTags && c == '<' && braces == 0 && !tag) {
        // <p>, <P>, <p/> and <p /> are one paragraph break; a closing </p> is dropped
        Matcher open = PARAGRAPH_TAG.matcher(text).region(i, text.length());
        Matcher close = PARAGRAPH_CLOSE.matcher(text).region(i, text.length());

        if (open.lookingAt() || close.lookingAt()) {
          if (!token.isEmpty()) {
            tokens.add(token.toString());
            token.setLength(0);
          }

          if (open.lookingAt()) {
            tokens.add("<p>");
            i += open.end() - i - 1;
          } else {
            i += close.end() - i - 1;
          }

          continue;
        }
      }

      if (markdown && c == '`' && braces == 0) {
        int run = 1;

        while (i + run < text.length() && text.charAt(i + run) == '`') {
          run++;
        }

        if (backticks == 0) {
          backticks = run;
        } else if (backticks == run) {
          backticks = 0;
        }

        token.append(text, i, i + run);
        i += run - 1;
        continue;
      }

      if (braces == 0 && c == '{' && i + 1 < text.length() && text.charAt(i + 1) == '@') {
        braces = 1;
      } else if (braces > 0 && c == '{') {
        braces++;
      } else if (braces > 0 && c == '}') {
        braces--;
      } else if (!markdown && braces == 0 && !tag && c == '<' && i + 1 < text.length()
        && (Character.isLetter(text.charAt(i + 1)) || text.charAt(i + 1) == '/')) {
        tag = true;
      } else if (tag && c == '>') {
        tag = false;
      }

      if (Character.isWhitespace(c) && braces == 0 && !tag && backticks == 0) {
        if (!token.isEmpty()) {
          tokens.add(token.toString());
          token.setLength(0);
        }
      } else {
        token.append(c);
      }
    }

    if (braces != 0 || backticks != 0) {
      return null;
    }

    if (!token.isEmpty()) {
      tokens.add(token.toString());
    }

    return tokens;
  }

  /** The block tag a line starts with ({@code @param}), or {@code null}. */
  private static String blockTag(String trimmed) {
    if (!trimmed.startsWith("@")) {
      return null;
    }

    int end = 1;

    while (end < trimmed.length() && Character.isLetter(trimmed.charAt(end))) {
      end++;
    }

    String name = trimmed.substring(1, end);

    return BLOCK_TAGS.contains(name) ? name : null;
  }

  /** Whether the text opens an inline tag ({@code {@...}) that it does not close. */
  private static boolean inlineTagOpen(String text) {
    int depth = 0;

    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);

      if (c == '{' && (depth > 0 || i + 1 < text.length() && text.charAt(i + 1) == '@')) {
        depth++;
      } else if (c == '}' && depth > 0) {
        depth--;
      }
    }

    return depth > 0;
  }

  /** Whether both texts hold the same words, ignoring whitespace and {@code <p>} markers. */
  private static boolean sameWords(List<String> before, List<String> after) {
    return normalize(before).equals(normalize(after));
  }

  private static String normalize(List<String> lines) {
    return String.join("", lines).replaceAll("(?i)</?p\\s*/?>", "").replaceAll("\\s+", "");
  }
}
