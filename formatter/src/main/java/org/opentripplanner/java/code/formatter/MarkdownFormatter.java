package org.opentripplanner.java.code.formatter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small Markdown formatter that follows Prettier's style for the common cases:
 * <ul>
 * <li>paragraphs are refilled to the width (or, in {@code preserve} mode, keep their line breaks);</li>
 * <li>headings become {@code #} headings, thematic breaks become {@code ---};</li>
 * <li>bullets become {@code -} (alternating with {@code *} for lists that follow each other), ordered lists are
 * numbered the way Prettier numbers them, and nested content is indented to the marker;</li>
 * <li>block quotes are formatted inside, tables are aligned, and there is one blank line between blocks.</li>
 * </ul>
 * Code blocks, HTML blocks, front matter and link definitions are copied as written. Inline text is never changed
 * apart from the line breaks (so {@code *emphasis*} stays as written). A paragraph is never broken inside a code span
 * or a link, and never at a place where the next line would start a new block.
 * <p>
 * The output must keep the same letters and digits in the same order as the input; if it does not, {@link #format}
 * returns {@code null} and the file is left alone.
 */
final class MarkdownFormatter {

  private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
  private static final Pattern ATX = Pattern.compile("^ {0,3}(#{1,6})(?:[ \\t]+(.*?))?(?:[ \\t]+#+)?[ \\t]*$");
  private static final Pattern THEMATIC = Pattern.compile("^ {0,3}([-*_])(?:[ \\t]*\\1){2,}[ \\t]*$");
  private static final Pattern SETEXT = Pattern.compile("^ {0,3}(=+|-+)[ \\t]*$");
  private static final Pattern QUOTE = Pattern.compile("^ {0,3}>.*$");
  private static final Pattern LIST_START = Pattern.compile("^( {0,3})([-+*]|\\d{1,9}[.)])([ \\t]+|$)(.*)$");
  private static final Pattern HTML_START = Pattern.compile("^ {0,3}(?:<(?:!--|\\?|![A-Z]|(?i:script|pre|style|textarea)(?:[ \\t>]|$)"
    + "|/?(?i:address|article|aside|base|basefont|blockquote|body|caption|center|col|colgroup|dd|details|dialog|dir"
    + "|div|dl|dt|fieldset|figcaption|figure|footer|form|frame|frameset|h[1-6]|head|header|hr|html|iframe|legend|li"
    + "|link|main|menu|menuitem|nav|noframes|ol|optgroup|option|p|param|section|source|summary|table|tbody|td|tfoot"
    + "|th|thead|title|tr|track|ul)(?:[ \\t/>]|$))"
    + "|</?[A-Za-z][A-Za-z0-9-]*(?:[ \\t]+[^<>]*)?/?>[ \\t]*$)");
  private static final Pattern LIST_NUMBER = Pattern.compile("(?m)^[ >]*\\d{1,9}[.)](?=\\s)");
  private static final Pattern LINK_TARGET = Pattern.compile("(?s)\\s*(?:<[^<>]*>|\\S*)(?:\\s+(?:\"[^\"]*\"|'[^']*'|\\([^)]*\\)))?\\s*");
  private static final Pattern DEFINITION_SPLIT = Pattern.compile("^ {0,3}\\[[^\\]]+\\]:[ \\t]*$");
  private static final Pattern DEFINITION_PARTS = Pattern
    .compile("^(\\[[^\\]]+\\]):[ \\t]*(<[^>]*>|\\S+)(?:[ \\t]+(\"[^\"]*\"|'[^']*'|\\([^)]*\\)))?[ \\t]*$");
  private static final Pattern TASK = Pattern.compile("^\\[([ xX])\\][ \\t]+(.*)$");
  private static final Pattern DEFINITION = Pattern.compile("^ {0,3}\\[[^\\]]+\\]:[ \\t]*\\S+.*$");
  private static final Pattern TABLE_DELIMITER = Pattern
    .compile("^ {0,3}\\|?[ \\t]*:?-+:?[ \\t]*(?:\\|[ \\t]*:?-+:?[ \\t]*)*\\|?[ \\t]*$");
  private static final Pattern INLINE_TAG = Pattern.compile("<[A-Za-z/!?][^<>\\n]*>");
  /** Words that must not start a wrapped line, or the line would start a new block. */
  private static final Pattern LINE_START_HAZARD = Pattern
    .compile("^(?:>.*|[-+*]|\\d{1,9}[.)]|#{1,6}|[=-]+|~{3,}.*|`{3,}.*)$");

  private enum Kind {
    OTHER,
    PARAGRAPH,
    QUOTE,
    HTML,
    DEFINITION,
    BULLET,
    ORDERED
  }

  /** A formatted block; {@code blankBefore} tells if the source had a blank line before it. */
  private record Block(Kind kind, List<String> lines, boolean blankBefore) {

    Block(Kind kind, List<String> lines) {
      this(kind, lines, false);
    }
  }

  private final int width;
  private final boolean preserve;

  private MarkdownFormatter(int width, boolean preserve) {
    this.width = width;
    this.preserve = preserve;
  }

  /** The formatted text, or {@code null} if it could not be formatted safely. */
  static String format(String source, int width, boolean preserveProse) {
    try {
      String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
      List<String> lines = new ArrayList<>(List.of(normalized.split("\n", -1)));
      List<String> out = new ArrayList<>();
      int start = 0;

      if (!lines.isEmpty() && lines.get(0).stripTrailing().equals("---")) {
        for (int i = 1; i < lines.size(); i++) {
          String line = lines.get(i).stripTrailing();

          if (line.equals("---") || line.equals("...")) {
            out.addAll(lines.subList(0, i + 1));
            start = i + 1;
            break;
          }
        }
      }

      List<Block> blocks = new MarkdownFormatter(width, preserveProse).blocks(lines.subList(start, lines.size()), width);

      if (!blocks.isEmpty() && start > 0) {
        out.add("");
      }

      out.addAll(join(blocks));
      String text = out.isEmpty() ? "" : String.join("\n", out) + "\n";
      return skeleton(source).equals(skeleton(text)) ? text : null;
    } catch (StackOverflowError e) {
      return null;
    }
  }

  /** Only the letters and digits (any case), in order: what the formatter must never change. */
  private static String skeleton(String text) {
    // The numbers of ordered list items are renumbered, so they are not part of what must stay the same.
    text = LIST_NUMBER.matcher(text).replaceAll("");
    StringBuilder out = new StringBuilder();
    text.codePoints().filter(Character::isLetterOrDigit).map(Character::toLowerCase).forEach(out::appendCodePoint);
    return out.toString();
  }

  private static List<String> join(List<Block> blocks) {
    List<String> out = new ArrayList<>();

    for (int i = 0; i < blocks.size(); i++) {
      // HTML blocks that touch in the source stay touching, and link definitions are kept together
      boolean touching = i > 0 && blocks.get(i).kind() == blocks.get(i - 1).kind()
        && (blocks.get(i).kind() == Kind.DEFINITION || blocks.get(i).kind() == Kind.HTML && !blocks.get(i).blankBefore());

      if (i > 0 && !touching) {
        out.add("");
      }

      out.addAll(blocks.get(i).lines());
    }

    return out;
  }

  // ---- blocks ----

  private List<Block> blocks(List<String> lines, int width) {
    return blocks(lines, width, 0);
  }

  /** {@code firstIndent} extra columns are taken from the first block (the text after a task list checkbox). */
  private List<Block> blocks(List<String> lines, int width, int firstIndent) {
    List<Block> blocks = new ArrayList<>();
    int sibling = 0;
    int i = 0;
    boolean sawBlank = false;

    while (i < lines.size()) {
      String line = lines.get(i);

      if (isBlank(line)) {
        sawBlank = true;
        i++;
        continue;
      }

      int before = blocks.size();

      Matcher fence = FENCE.matcher(line);

      if (fence.matches() && !(fence.group(1).charAt(0) == '`' && fence.group(2).indexOf('`') >= 0)) {
        i = fencedCode(lines, i, fence, blocks);
      } else if (indentWidth(line) >= 4) {
        i = indentedCode(lines, i, blocks);
      } else if (ATX.matcher(line).matches()) {
        blocks.add(other(atx(line)));
        i++;
      } else if (THEMATIC.matcher(line).matches()) {
        blocks.add(other("---"));
        i++;
      } else if (QUOTE.matcher(line).matches()) {
        i = quote(lines, i, width - (blocks.isEmpty() ? firstIndent : 0), blocks);
      } else if (LIST_START.matcher(line).matches()) {
        Kind kind = Character.isDigit(LIST_START.matcher(line).results().findFirst().get().group(2).charAt(0))
          ? Kind.ORDERED
          : Kind.BULLET;
        sibling = !blocks.isEmpty() && blocks.get(blocks.size() - 1).kind() == kind ? sibling + 1 : 0;
        i = list(lines, i, width, sibling, kind, blocks);
      } else if (HTML_START.matcher(line).find()) {
        i = html(lines, i, blocks);
      } else if (isDefinition(lines, i)) {
        List<String> definitions = new ArrayList<>();

        while (i < lines.size() && isDefinition(lines, i)) {
          boolean split = DEFINITION_SPLIT.matcher(lines.get(i)).matches();
          definitions.addAll(definition(split ? lines.get(i).strip() + " " + lines.get(i + 1).strip() : lines.get(i).strip(), width));
          i += split ? 2 : 1;
        }

        blocks.add(new Block(Kind.DEFINITION, definitions));
      } else if (i + 1 < lines.size() && isTable(lines, i)) {
        i = table(lines, i, blocks);
      } else {
        i = paragraph(lines, i, width - (blocks.isEmpty() ? firstIndent : 0), blocks);
      }

      if (blocks.size() > before) {
        Block added = blocks.remove(blocks.size() - 1);
        blocks.add(new Block(added.kind(), added.lines(), sawBlank));
        sawBlank = false;
      }

      if (!blocks.isEmpty() && blocks.get(blocks.size() - 1).kind() != Kind.BULLET
        && blocks.get(blocks.size() - 1).kind() != Kind.ORDERED) {
        sibling = 0;
      }
    }

    return blocks;
  }

  /** A link definition: on one line, or with the destination on the (indented) line after the label. */
  private static boolean isDefinition(List<String> lines, int i) {
    String line = lines.get(i);

    return DEFINITION.matcher(line).matches() || DEFINITION_SPLIT.matcher(line).matches() && i + 1 < lines.size()
      && !isBlank(lines.get(i + 1)) && indentWidth(lines.get(i + 1)) > 0;
  }

  /** A link definition on one line, or with its destination on the next line when it does not fit. */
  private static List<String> definition(String line, int width) {
    Matcher m = DEFINITION_PARTS.matcher(line);

    if (!m.matches() || displayWidth(line) <= width) {
      return m.matches() ? List.of(m.group(1) + ": " + m.group(2) + (m.group(3) == null ? "" : " " + m.group(3)))
        : List.of(line);
    }

    List<String> out = new ArrayList<>();
    out.add(m.group(1) + ":");
    out.add("  " + m.group(2));

    if (m.group(3) != null) {
      out.add("  " + m.group(3));
    }

    return out;
  }

  private static Block other(String line) {
    return new Block(Kind.OTHER, List.of(line));
  }

  private static String atx(String line) {
    Matcher m = ATX.matcher(line);
    m.matches();
    String text = m.group(2) == null ? "" : String.join(" ", atoms(emphasis(m.group(2).strip())));
    return text.isEmpty() ? m.group(1) : m.group(1) + " " + text;
  }

  private int fencedCode(List<String> lines, int start, Matcher fence, List<Block> blocks) {
    String marker = fence.group(1);
    int indent = indentWidth(lines.get(start));
    List<String> out = new ArrayList<>();
    String info = fence.group(2).strip();
    int i = start + 1;

    while (i < lines.size()) {
      String line = lines.get(i);
      Matcher close = FENCE.matcher(line);

      if (close.matches() && close.group(1).charAt(0) == marker.charAt(0) && close.group(1).length() >= marker.length()
        && close.group(2).isBlank()) {
        i++;
        break;
      }

      out.add(dedent(line, indent));
      i++;
    }

    // Prettier writes every fence with backticks, one more than the longest run inside the code.
    int longest = 0;

    for (String line : out) {
      int run = 0;

      for (char c : line.toCharArray()) {
        run = c == '`' ? run + 1 : 0;
        longest = Math.max(longest, run);
      }
    }

    String fenceText = marker.charAt(0) == '`' || info.indexOf('`') < 0 ? "`".repeat(Math.max(3, longest + 1)) : marker;
    out.add(0, fenceText + info);
    out.add(fenceText);
    blocks.add(new Block(Kind.OTHER, out));
    return i;
  }

  private int indentedCode(List<String> lines, int start, List<Block> blocks) {
    List<String> out = new ArrayList<>();
    int i = start;

    while (i < lines.size() && (isBlank(lines.get(i)) || indentWidth(lines.get(i)) >= 4)) {
      out.add(isBlank(lines.get(i)) ? "" : lines.get(i));
      i++;
    }

    while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
      out.remove(out.size() - 1);
    }

    blocks.add(new Block(Kind.OTHER, out));
    return i;
  }

  private int html(List<String> lines, int start, List<Block> blocks) {
    List<String> out = new ArrayList<>();
    boolean comment = lines.get(start).strip().startsWith("<!--");
    int i = start;

    // A comment runs to the line that closes it, any other HTML to the next blank line.
    while (i < lines.size() && (comment || !isBlank(lines.get(i)))) {
      out.add(lines.get(i).stripTrailing());
      i++;

      if (comment && lines.get(i - 1).contains("-->")) {
        break;
      }
    }

    blocks.add(new Block(Kind.HTML, out));
    return i;
  }

  private int quote(List<String> lines, int start, int width, List<Block> blocks) {
    List<String> inner = new ArrayList<>();
    int i = start;

    while (i < lines.size() && QUOTE.matcher(lines.get(i)).matches()) {
      String line = lines.get(i).stripLeading().substring(1);
      inner.add(line.startsWith(" ") ? line.substring(1) : line);
      i++;
    }

    List<String> out = new ArrayList<>();

    for (String line : join(blocks(inner, width - 2))) {
      out.add(line.isEmpty() ? ">" : "> " + line);
    }

    if (out.isEmpty()) {
      out.add(">");
    }

    blocks.add(new Block(Kind.QUOTE, out));
    return i;
  }

  // ---- lists ----

  private record Item(List<String> content, boolean loose, int number) {
  }

  private int list(List<String> lines, int start, int width, int sibling, Kind kind, List<Block> blocks) {
    Matcher first = LIST_START.matcher(lines.get(start));
    first.matches();
    String firstMarker = first.group(2);
    char delimiter = firstMarker.charAt(firstMarker.length() - 1);
    boolean ordered = kind == Kind.ORDERED;
    List<List<String>> contents = new ArrayList<>();
    List<Boolean> trailingBlank = new ArrayList<>();
    List<Integer> numbers = new ArrayList<>();
    List<Integer> starts = new ArrayList<>();
    List<Integer> spacings = new ArrayList<>();
    int i = start;
    boolean previousTrailingBlank = false;

    while (i < lines.size()) {
      int at = i;

      while (at < lines.size() && isBlank(lines.get(at))) {
        at++;
      }

      if (at >= lines.size()) {
        break;
      }

      String line = lines.get(at);
      Matcher m = LIST_START.matcher(line);

      if (!m.matches() || THEMATIC.matcher(line).matches()) {
        break;
      }

      previousTrailingBlank = at > i;
      i = at;

      String marker = m.group(2);

      if (Character.isDigit(marker.charAt(0)) != ordered || marker.charAt(marker.length() - 1) != delimiter && ordered
        || !ordered && marker.charAt(0) != delimiter) {
        break;
      }

      if (!contents.isEmpty()) {
        trailingBlank.set(trailingBlank.size() - 1, previousTrailingBlank);
      }

      int markerIndent = m.group(1).length();
      int spaces = columns(m.group(3), markerIndent + marker.length());
      boolean blankStart = m.group(4).isBlank();
      int offset = markerIndent + marker.length() + (blankStart || spaces > 4 ? 1 : spaces);
      List<String> content = new ArrayList<>();
      starts.add(blankStart ? -1 : offset);
      spacings.add(spaces);
      content.add(blankStart ? "" : spaces > 4 ? " ".repeat(spaces - 1) + m.group(4) : m.group(4));
      boolean inFence = false;
      Matcher f = FENCE.matcher(content.get(0));

      if (f.matches()) {
        inFence = true;
      }

      i++;

      while (i < lines.size()) {
        String next = lines.get(i);

        if (isBlank(next)) {
          content.add("");
          i++;
          continue;
        }

        if (indentWidth(next) >= offset) {
          String dedented = dedent(next, offset);
          Matcher fm = FENCE.matcher(dedented);

          if (fm.matches()) {
            inFence = !inFence;
          }

          content.add(dedented);
          i++;
          continue;
        }

        boolean previousBlank = content.get(content.size() - 1).isEmpty();

        if (!previousBlank && !inFence && !startsBlock(next) && !LIST_START.matcher(next).matches()) {
          content.add(next.stripLeading());
          i++;
          continue;
        }

        break;
      }

      int trailing = 0;

      while (content.size() > 1 && content.get(content.size() - 1).isEmpty()) {
        content.remove(content.size() - 1);
        trailing++;
      }

      i -= trailing;
      contents.add(content);
      trailingBlank.add(false);
      numbers.add(ordered ? Integer.parseInt(marker.substring(0, marker.length() - 1)) : 0);
    }

    List<String> out = new ArrayList<>();
    int startNumber = numbers.get(0);
    boolean aligned = ordered && isAligned(starts, spacings);
    boolean gitFriendly = ordered && contents.size() > 1 && startNumber <= 1 && numbers.get(1).equals(startNumber);

    for (int n = 0; n < contents.size(); n++) {
      String marker;

      if (ordered) {
        marker = (gitFriendly ? (n == 0 ? startNumber : 1) : startNumber + n) + (sibling % 2 == 0 ? "." : ")");
      } else {
        marker = sibling % 2 == 0 ? "-" : "*";
      }

      // A list that is aligned in the source keeps its text on the next multiple of two columns (Prettier's tabWidth).
      int pad = aligned && (marker.length() + 1) % 2 == 1 ? 1 : 0;
      int prefix = marker.length() + 1 + pad;
      List<String> content = contents.get(n);
      String checkbox = "";
      Matcher task = content.isEmpty() ? null : TASK.matcher(content.get(0));

      if (task != null && task.matches()) {
        checkbox = task.group(1).equals(" ") ? "[ ] " : "[x] ";
        content = new ArrayList<>(content);
        content.set(0, task.group(2));
      }

      List<Block> children = blocks(content, width - prefix, checkbox.length());
      List<String> body = new ArrayList<>();

      // An item with a blank line between two of its blocks is spread: it then gets a blank line before every block,
      // except a list that follows the block before it directly, or any list that follows a quote or another list. An item without one has none.
      boolean spread = children.stream().skip(1).anyMatch(Block::blankBefore);

      for (int c = 0; c < children.size(); c++) {
        Kind childKind = children.get(c).kind();
        boolean list = childKind == Kind.BULLET || childKind == Kind.ORDERED;
        Kind before = c > 0 ? children.get(c - 1).kind() : Kind.OTHER;
        boolean afterListOrQuote = before == Kind.QUOTE || before == Kind.BULLET || before == Kind.ORDERED;
        boolean directList = list && (!children.get(c).blankBefore() || afterListOrQuote);

        if (c > 0 && spread && !directList) {
          body.add("");
        }

        List<String> childLines = children.get(c).lines();

        if (c == 0 && !checkbox.isEmpty() && (childKind == Kind.OTHER || childKind == Kind.PARAGRAPH)) {
          // the continuation lines of the first block line up with the text after the checkbox
          for (int l = 0; l < childLines.size(); l++) {
            body.add(l == 0 ? checkbox + childLines.get(l) : childLines.get(l).isEmpty() ? "" : "    " + childLines.get(l));
          }
        } else {
          body.addAll(childLines);
        }
      }

      if (body.isEmpty() && !checkbox.isEmpty()) {
        body.add(checkbox.stripTrailing());
      }

      if (body.isEmpty()) {
        out.add(marker);
      }

      for (int b = 0; b < body.size(); b++) {
        String text = body.get(b);

        if (b == 0) {
          out.add(marker + " ".repeat(1 + pad) + text);
        } else {
          out.add(text.isEmpty() ? "" : " ".repeat(prefix) + text);
        }
      }

      if (n < contents.size() - 1 && (spread || trailingBlank.get(n))) {
        out.add("");
      }
    }

    blocks.add(new Block(kind, out));
    return i;
  }

  /** Prettier's rule for an ordered list: whether its source text is aligned to the tab stops (width 2). */
  private static boolean isAligned(List<Integer> starts, List<Integer> spacings) {
    if (spacings.get(0) > 1) {
      return true;
    }

    int first = starts.get(0);

    if (first < 0) {
      return false;
    }

    if (starts.size() == 1) {
      return first % 2 == 0;
    }

    if (first != starts.get(1)) {
      return false;
    }

    return first % 2 == 0 || spacings.get(1) > 1;
  }

  /** Whether the line starts something that interrupts a paragraph. */
  private static boolean startsBlock(String line) {
    if (FENCE.matcher(line).matches() || ATX.matcher(line).matches() || THEMATIC.matcher(line).matches()
      || QUOTE.matcher(line).matches()) {
      return true;
    }

    Matcher m = LIST_START.matcher(line);

    if (m.matches() && !m.group(4).isBlank()) {
      String marker = m.group(2);
      return !Character.isDigit(marker.charAt(0)) || marker.equals("1.") || marker.equals("1)");
    }

    return false;
  }

  // ---- tables ----

  private static boolean isTable(List<String> lines, int i) {
    String header = lines.get(i);
    String delimiter = lines.get(i + 1);

    return header.indexOf('|') >= 0 && TABLE_DELIMITER.matcher(delimiter).matches()
      && (delimiter.indexOf('|') >= 0 || delimiter.indexOf('-') >= 0) && cells(header).size() == cells(delimiter).size();
  }

  private int table(List<String> lines, int start, List<Block> blocks) {
    int end = start + 2;

    while (end < lines.size() && !isBlank(lines.get(end)) && !startsBlock(lines.get(end))) {
      end++;
    }

    List<List<String>> rows = new ArrayList<>();
    int columns = cells(lines.get(start)).size();
    boolean regular = true;

    for (int i = start; i < end; i++) {
      if (i == start + 1) {
        continue;
      }

      List<String> row = cells(lines.get(i));
      regular &= row.size() <= columns;
      rows.add(row);
    }

    if (!regular) {
      List<String> verbatim = new ArrayList<>();

      for (int i = start; i < end; i++) {
        verbatim.add(lines.get(i).strip());
      }

      blocks.add(new Block(Kind.OTHER, verbatim));
      return end;
    }

    List<String> alignment = new ArrayList<>();

    for (String cell : cells(lines.get(start + 1))) {
      boolean left = cell.startsWith(":");
      boolean right = cell.endsWith(":");
      alignment.add(left && right ? "center" : left ? "left" : right ? "right" : "none");
    }

    int[] widths = new int[columns];

    for (int c = 0; c < columns; c++) {
      widths[c] = 3;

      for (List<String> row : rows) {
        widths[c] = Math.max(widths[c], c < row.size() ? displayWidth(row.get(c)) : 0);
      }
    }

    List<String> out = new ArrayList<>();
    out.add(tableRow(rows.get(0), widths, alignment));
    StringBuilder delimiter = new StringBuilder("|");

    for (int c = 0; c < columns; c++) {
      String align = alignment.get(c);
      int dashes = widths[c] - (align.equals("center") ? 2 : align.equals("none") ? 0 : 1);
      delimiter.append(' ').append(align.equals("left") || align.equals("center") ? ":" : "").append("-".repeat(dashes))
        .append(align.equals("right") || align.equals("center") ? ":" : "").append(" |");
    }

    out.add(delimiter.toString());

    for (int r = 1; r < rows.size(); r++) {
      out.add(tableRow(rows.get(r), widths, alignment));
    }

    blocks.add(new Block(Kind.OTHER, out));
    return end;
  }

  private static String tableRow(List<String> row, int[] widths, List<String> alignment) {
    StringBuilder out = new StringBuilder("|");

    // a row with fewer cells than the header keeps only the cells it has
    for (int c = 0; c < row.size(); c++) {
      String cell = row.get(c);
      int space = widths[c] - displayWidth(cell);
      int left = switch (alignment.get(c)) {
        case "right" -> space;
        case "center" -> space / 2;
        default -> 0;
      };
      out.append(' ').append(" ".repeat(left)).append(cell).append(" ".repeat(space - left)).append(" |");
    }

    return out.toString();
  }

  /** The cells of a table row: split at unescaped pipes outside code spans; outer pipes dropped; cells trimmed. */
  private static List<String> cells(String row) {
    String text = row.strip();
    List<String> cells = new ArrayList<>();
    StringBuilder cell = new StringBuilder();

    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);

      if (c == '\\' && i + 1 < text.length()) {
        cell.append(c).append(text.charAt(++i));
      } else if (c == '|') {
        cells.add(cell.toString());
        cell.setLength(0);
      } else {
        cell.append(c);
      }
    }

    cells.add(cell.toString());

    if (text.startsWith("|")) {
      cells.remove(0);
    }

    if (text.endsWith("|") && !text.endsWith("\\|") && !cells.isEmpty()) {
      cells.remove(cells.size() - 1);
    }

    return cells.stream().map(String::strip).map(MarkdownFormatter::emphasis).toList();
  }

  // ---- paragraphs ----

  private int paragraph(List<String> lines, int start, int width, List<Block> blocks) {
    List<String> text = new ArrayList<>();
    int i = start;

    while (i < lines.size() && !isBlank(lines.get(i))) {
      String line = lines.get(i);

      if (i > start) {
        Matcher setext = SETEXT.matcher(line);

        if (setext.matches()) {
          // a setext heading stays as written
          List<String> verbatim = new ArrayList<>(text.stream().map(String::strip).toList());
          verbatim.add(line.strip());
          blocks.add(new Block(Kind.OTHER, verbatim));
          return i + 1;
        }

        if (startsBlock(line) || HTML_START.matcher(line).find() && line.stripLeading().startsWith("<!--")) {
          break;
        }
      }

      text.add(line);
      i++;
    }

    blocks.add(new Block(Kind.PARAGRAPH, preserve ? preserved(text) : filled(text, width)));
    return i;
  }

  private static List<String> preserved(List<String> lines) {
    List<String> out = new ArrayList<>();

    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      String stripped = line.strip();
      out.add(emphasis(stripped) + (i < lines.size() - 1 && hardBreak(line) == ' ' ? "  " : ""));
    }

    return out;
  }

  /** {@code ' '} for a break made of two or more trailing spaces, {@code '\\'} for a trailing backslash, else 0. */
  private static char hardBreak(String line) {
    String stripped = line.stripTrailing();

    if (line.length() - stripped.length() >= 2) {
      return ' ';
    }

    int backslashes = 0;

    while (backslashes < stripped.length() && stripped.charAt(stripped.length() - 1 - backslashes) == '\\') {
      backslashes++;
    }

    return backslashes % 2 == 1 ? '\\' : 0;
  }

  private List<String> filled(List<String> lines, int width) {
    List<String> out = new ArrayList<>();
    StringBuilder segment = new StringBuilder();
    boolean afterBreak = false;

    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      char hard = i < lines.size() - 1 ? hardBreak(line) : 0;
      String text = line.strip();

      if (hard == '\\') {
        text = text.substring(0, text.length() - 1).stripTrailing();
      }

      if (segment.length() > 0 && !text.isEmpty()) {
        segment.append(' ');
      }

      segment.append(text);

      if (hard != 0) {
        List<String> wrapped = wrap(atoms(emphasis(segment.toString())), width, afterBreak);
        afterBreak = true;
        int last = wrapped.size() - 1;
        wrapped.set(last, wrapped.get(last) + (hard == '\\' ? "\\" : "  "));
        out.addAll(wrapped);
        segment.setLength(0);
      }
    }

    if (segment.length() > 0) {
      out.addAll(wrap(atoms(emphasis(segment.toString())), width, afterBreak));
    }

    return out;
  }

  private static List<String> wrap(List<String> words, int width, boolean afterBreak) {
    if (afterBreak && words.size() > 1) {
      // Prettier never breaks the line between the first two words after a hard break.
      words = new ArrayList<>(words);
      words.set(0, words.get(0) + " " + words.remove(1));
    }

    // A word that would start a block when it starts a line (a lone "-", "1." or ">...") stays glued to the word before
    // it, so the pair moves to the next line together.
    List<String> units = new ArrayList<>();

    for (String word : words) {
      if (!units.isEmpty() && LINE_START_HAZARD.matcher(word).matches()) {
        units.set(units.size() - 1, units.get(units.size() - 1) + " " + word);
      } else {
        units.add(word);
      }
    }

    List<String> out = new ArrayList<>();
    StringBuilder line = new StringBuilder();
    int length = 0;

    for (String unit : units) {
      int unitWidth = displayWidth(unit);

      if (line.length() == 0) {
        line.append(unit);
        length = unitWidth;
      } else if (length + 1 + unitWidth <= width) {
        line.append(' ').append(unit);
        length += 1 + unitWidth;
      } else {
        out.add(line.toString());
        line.setLength(0);
        line.append(unit);
        length = unitWidth;
      }
    }

    if (line.length() > 0) {
      out.add(line.toString());
    }

    return out;
  }

  /** Splits at whitespace, except inside code spans, inline tags and links. */
  private static List<String> atoms(String text) {
    List<String> words = new ArrayList<>();
    StringBuilder word = new StringBuilder();
    int i = 0;

    while (i < text.length()) {
      char c = text.charAt(i);

      if (c == ' ' || c == '\t') {
        if (word.length() > 0) {
          words.add(word.toString());
          word.setLength(0);
        }

        i++;
      } else if (c == '\\' && i + 1 < text.length() && text.charAt(i + 1) != ' ') {
        word.append(c).append(text.charAt(i + 1));
        i += 2;
      } else if (c == '`') {
        int run = 1;

        while (i + run < text.length() && text.charAt(i + run) == '`') {
          run++;
        }

        int close = closingBackticks(text, i + run, run);

        if (close < 0) {
          word.append(text, i, i + run);
          i += run;
        } else {
          word.append(text, i, close + run);
          i = close + run;
        }
      } else if (c == '<') {
        Matcher tag = INLINE_TAG.matcher(text);

        if (tag.find(i) && tag.start() == i) {
          word.append(tag.group());
          i = tag.end();
        } else {
          word.append(c);
          i++;
        }
      } else if (c == '[') {
        int end = linkEnd(text, i, word.length() > 0 && word.charAt(word.length() - 1) == '!');

        if (end < 0) {
          word.append(c);
          i++;
        } else {
          String link = text.substring(i, end);
          boolean image = word.length() > 0 && word.charAt(word.length() - 1) == '!';
          word.append(link.indexOf('`') < 0 && !image ? link.replaceAll("[ \\t]{2,}", " ") : link);
          i = end;
        }
      } else {
        word.append(c);
        i++;
      }
    }

    if (word.length() > 0) {
      words.add(word.toString());
    }

    return words;
  }

  /**
   * The end (exclusive) of the inline link, image or reference that starts at {@code open}, or -1 if it is plain text
   * that may be broken: {@code [text](destination)} and images are kept whole, and so is a reference with brackets
   * inside ({@code [![alt][a]][b]}); the words of a simple {@code [text]} or {@code [text][label]} can be separated.
   */
  private static int linkEnd(String text, int open, boolean image) {
    int depth = 0;
    int deepest = 0;

    for (int i = open; i < text.length(); i++) {
      char c = text.charAt(i);

      if (c == '\\') {
        i++;
      } else if (c == '[') {
        deepest = Math.max(deepest, ++depth);
      } else if (c == ']' && --depth == 0) {
        if (i + 1 < text.length() && text.charAt(i + 1) == '(') {
          int close = closingParen(text, i + 1);

          // "(a b)" is not a destination: after the destination only a quoted title may follow
          return close > 0 && LINK_TARGET.matcher(text.substring(i + 2, close)).matches() ? close + 1 : -1;
        }

        int end = i + 1;

        if (end < text.length() && text.charAt(end) == '[') {
          int close = text.indexOf(']', end + 1);
          end = close > 0 ? close + 1 : end;
        }

        return image || deepest > 1 ? end : -1;
      }
    }

    return -1;
  }

  /** The index of the next run of exactly {@code run} backticks at or after {@code from}, or -1. */
  private static int closingBackticks(String text, int from, int run) {
    int i = from;

    while (i < text.length()) {
      if (text.charAt(i) == '`') {
        int length = 1;

        while (i + length < text.length() && text.charAt(i + length) == '`') {
          length++;
        }

        if (length == run) {
          return i;
        }

        i += length;
      } else {
        i++;
      }
    }

    return -1;
  }

  /** The index of the {@code )} that closes the {@code (} at {@code open}, or -1. */
  private static int closingParen(String text, int open) {
    int depth = 0;

    for (int i = open; i < text.length(); i++) {
      char c = text.charAt(i);

      if (c == '\\') {
        i++;
      } else if (c == '(') {
        depth++;
      } else if (c == ')' && --depth == 0) {
        return i;
      }
    }

    return -1;
  }

  // ---- emphasis ----

  /**
   * Writes {@code *text*} as {@code _text_} and {@code __text__} as {@code **text**}, as Prettier does. Only when the
   * markers stand at the edge of a word (never inside one), and never inside code spans, tags, link destinations or
   * after a backslash. Anything unclear is left as written.
   */
  static String emphasis(String text) {
    StringBuilder out = new StringBuilder();
    int i = 0;

    while (i < text.length()) {
      char c = text.charAt(i);

      if (c == '\\' && i + 1 < text.length()) {
        out.append(c).append(text.charAt(i + 1));
        i += 2;
      } else if (c == '`') {
        int end = codeSpanEnd(text, i);
        out.append(text, i, end);
        i = end;
      } else if (c == '<') {
        Matcher tag = INLINE_TAG.matcher(text);
        int end = tag.find(i) && tag.start() == i ? tag.end() : i + 1;
        out.append(text, i, end);
        i = end;
      } else if (c == ']' && i + 1 < text.length() && text.charAt(i + 1) == '(' && closingParen(text, i + 1) > 0) {
        int end = closingParen(text, i + 1) + 1;
        out.append(text, i, end);
        i = end;
      } else if (c == '*' || c == '_') {
        int run = 1;

        while (i + run < text.length() && text.charAt(i + run) == c) {
          run++;
        }

        int close = run == (c == '*' ? 1 : 2) ? emphasisClose(text, i, run) : -1;

        if (close < 0) {
          out.append(text, i, i + run);
          i += run;
        } else {
          String marker = c == '*' ? "_" : "**";
          out.append(marker).append(emphasis(text.substring(i + run, close))).append(marker);
          i = close + run;
        }
      } else {
        out.append(c);
        i++;
      }
    }

    return out.toString();
  }

  /** The end (exclusive) of the code span that starts at {@code i}; just after the backticks if it never closes. */
  private static int codeSpanEnd(String text, int i) {
    int run = 1;

    while (i + run < text.length() && text.charAt(i + run) == '`') {
      run++;
    }

    int close = closingBackticks(text, i + run, run);
    return close < 0 ? i + run : close + run;
  }

  /**
   * The index of the marker that closes the emphasis opened by the {@code run} markers at {@code open}, or -1 if it
   * does not open emphasis at the edge of a word or does not close the same way.
   */
  private static int emphasisClose(String text, int open, int run) {
    char c = text.charAt(open);
    int after = open + run;

    if (after >= text.length() || Character.isWhitespace(text.charAt(after)) || text.charAt(after) == c
      || open > 0 && (Character.isLetterOrDigit(text.charAt(open - 1)) || "*_".indexOf(text.charAt(open - 1)) >= 0)) {
      return -1;
    }

    int j = after;

    while (j < text.length()) {
      char d = text.charAt(j);

      if (d == '\\') {
        j += 2;
      } else if (d == '`') {
        j = codeSpanEnd(text, j);
      } else if (d == c) {
        int length = 1;

        while (j + length < text.length() && text.charAt(j + length) == c) {
          length++;
        }

        if (length != run) {
          j += length;
          continue;
        }

        boolean closes = !Character.isWhitespace(text.charAt(j - 1))
          && (j + run >= text.length()
            || !Character.isLetterOrDigit(text.charAt(j + run)) && "*_".indexOf(text.charAt(j + run)) < 0);
        return closes && j > after ? j : -1;
      } else {
        j++;
      }
    }

    return -1;
  }

  // ---- helpers ----

  private static boolean isBlank(String line) {
    return line.isBlank();
  }

  /** The width of the leading whitespace in columns; a tab goes to the next multiple of 4. */
  private static int indentWidth(String line) {
    int column = 0;

    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);

      if (c == ' ') {
        column++;
      } else if (c == '\t') {
        column += 4 - column % 4;
      } else {
        break;
      }
    }

    return column;
  }

  /** The columns the whitespace {@code ws} spans when it starts at column {@code from}. */
  private static int columns(String ws, int from) {
    int column = from;

    for (char c : ws.toCharArray()) {
      column += c == '\t' ? 4 - column % 4 : 1;
    }

    return column - from;
  }

  /** Removes {@code n} columns of leading whitespace (all of it, if there is less); a tab may be split. */
  private static String dedent(String line, int n) {
    int column = 0;
    int i = 0;

    while (i < line.length() && column < n) {
      char c = line.charAt(i);

      if (c == ' ') {
        column++;
      } else if (c == '\t') {
        int next = column + 4 - column % 4;

        if (next > n) {
          return " ".repeat(next - n) + line.substring(i + 1);
        }

        column = next;
      } else {
        break;
      }

      i++;
    }

    return line.substring(i);
  }

  /** The display width: wide (East Asian, emoji) characters count 2, combining marks 0. */
  static int displayWidth(String text) {
    int width = 0;

    for (int i = 0; i < text.length(); ) {
      int cp = text.codePointAt(i);
      i += Character.charCount(cp);
      int type = Character.getType(cp);

      if (cp == 0x200D && i < text.length()) {
        // a joined emoji sequence counts as one emoji
        i += Character.charCount(text.codePointAt(i));
        continue;
      }

      if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || cp == 0x200B
        || cp >= 0xFE00 && cp <= 0xFE0F || Character.isEmojiModifier(cp)) {
        continue;
      }

      boolean wide = Character.isEmojiPresentation(cp) && !(cp >= 0x1F1E6 && cp <= 0x1F1FF)
        || Character.isEmoji(cp) && cp > 0x7F && i < text.length() && text.codePointAt(i) == 0xFE0F
        || cp >= 0x1100 && cp <= 0x115F || cp >= 0x2E80 && cp <= 0xA4CF || cp >= 0xAC00 && cp <= 0xD7A3
        || cp >= 0xF900 && cp <= 0xFAFF || cp >= 0xFE30 && cp <= 0xFE6F || cp >= 0xFF00 && cp <= 0xFF60
        || cp >= 0xFFE0 && cp <= 0xFFE6 || cp >= 0x1F300 && cp <= 0x1F64F || cp >= 0x1F900 && cp <= 0x1F9FF
        || cp >= 0x20000 && cp <= 0x3FFFD;
      width += wide ? 2 : 1;
    }

    return width;
  }
}
