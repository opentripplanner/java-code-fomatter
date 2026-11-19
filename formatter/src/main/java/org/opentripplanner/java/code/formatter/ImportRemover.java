package org.opentripplanner.java.code.formatter;

import com.sun.source.tree.ImportTree;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes single-type and single-static-member imports whose simple name is not mentioned anywhere after the imports.
 * <p>
 * The formatter has no classpath, so this is name based and errs on the side of keeping an import: every identifier
 * after the imports counts as a use, including those in comments, Javadoc ({@code {@link Foo}}) and string literals.
 * An import is only removed when its name cannot appear anywhere in the file. Wildcard and module imports are never
 * touched. A static import of an overloaded name that is used for only one of its overloads is kept.
 * <p>
 * Only whole lines holding nothing but one import are removed; the blank lines around the removed imports are
 * collapsed so no double blank line is left behind.
 */
final class ImportRemover {

  private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}\\p{N}_$]+");

  private ImportRemover() {
  }

  /** {@code source} without its unused imports. The source must be valid Java. */
  static String remove(String source) throws IOException {
    SyntaxValidator.Parsed parsed = SyntaxValidator.parseTree(source);
    List<? extends ImportTree> imports = parsed.unit().getImports();

    if (imports.isEmpty()) {
      return source;
    }

    long bodyStart = 0;

    for (ImportTree tree : imports) {
      bodyStart = Math.max(bodyStart, parsed.positions().getEndPosition(parsed.unit(), tree));
    }

    Set<String> used = new HashSet<>();
    Matcher matcher = IDENTIFIER.matcher(source);
    matcher.region((int) bodyStart, source.length());

    while (matcher.find()) {
      used.add(matcher.group());
    }

    // [start, end) character ranges of the unused imports
    List<int[]> unused = new ArrayList<>();

    for (ImportTree tree : imports) {
      String name = simpleName(tree);

      if (name == null || used.contains(name)) {
        continue;
      }

      int start = (int) parsed.positions().getStartPosition(parsed.unit(), tree);
      int end = (int) parsed.positions().getEndPosition(parsed.unit(), tree);
      unused.add(new int[] { start, end });
    }

    if (unused.isEmpty()) {
      return source;
    }

    return removeLines(source, unused);
  }

  /** The name the import brings into scope, or {@code null} for wildcard and module imports. */
  private static String simpleName(ImportTree tree) {
    if (tree.isModule()) {
      return null;
    }

    String name = tree.getQualifiedIdentifier().toString();
    String simple = name.substring(name.lastIndexOf('.') + 1);
    return simple.equals("*") ? null : simple;
  }

  private static String removeLines(String source, List<int[]> ranges) {
    List<String> lines = new ArrayList<>();
    Matcher lineMatcher = Pattern.compile("[^\\n]*\\n|[^\\n]+").matcher(source);
    List<Integer> lineStarts = new ArrayList<>();

    while (lineMatcher.find()) {
      lineStarts.add(lineMatcher.start());
      lines.add(lineMatcher.group());
    }

    Set<Integer> deleted = new HashSet<>();

    for (int[] range : ranges) {
      int line = lineOf(lineStarts, range[0]);
      String text = lines.get(line);
      int lineStart = lineStarts.get(line);

      boolean onlyThisImport =
        text.substring(0, range[0] - lineStart).isBlank() && range[1] <= lineStart + text.length()
          && text.substring(range[1] - lineStart).isBlank();

      if (onlyThisImport) {
        deleted.add(line);
      }
    }

    StringBuilder out = new StringBuilder();
    boolean lastBlank = true;
    boolean atStart = true;
    boolean afterDeletion = false;

    for (int i = 0; i < lines.size(); i++) {
      if (deleted.contains(i)) {
        afterDeletion = true;
        continue;
      }

      boolean blank = lines.get(i).isBlank();

      if (blank && afterDeletion && (lastBlank || atStart)) {
        continue;
      }

      out.append(lines.get(i));
      lastBlank = blank;
      atStart = false;

      if (!blank) {
        afterDeletion = false;
      }
    }

    return out.toString();
  }

  private static int lineOf(List<Integer> lineStarts, int offset) {
    int line = 0;

    while (line + 1 < lineStarts.size() && lineStarts.get(line + 1) <= offset) {
      line++;
    }

    return line;
  }
}
