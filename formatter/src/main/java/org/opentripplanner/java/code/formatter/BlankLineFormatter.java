package org.opentripplanner.java.code.formatter;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Puts a blank line before and after every multi-line, brace-bodied block: type declarations, methods and constructors
 * with a body, initializers, and {@code if}, {@code for}, {@code while}, {@code do}, {@code switch}, {@code try} and
 * {@code synchronized} statements.
 * <p>
 * The blank line before goes above whatever is attached to the block - a run of comment lines (Javadoc included)
 * directly above it, and its annotations - never between them and it. No blank
 * <p>
 * line is added right after an opening {@code {}, at the start of a file, after a {@code case}
 * label or a statement label; none is added after a block when a closing {@code }}/{@code )}/
 * {@code ]} or a {@code case}/{@code default} label follows. {@code else if} chains, {@code catch}/{@code finally} and
 * a {@code do}'s {@code while} are part of their block. Blocks with no braces ({@code if (x) return;}), blocks that fit
 * on one line, lambda and anonymous-class bodies, and bodiless methods are left out. An existing blank line is kept,
 * and a second one is never created.
 */
final class BlankLineFormatter {

  private BlankLineFormatter() {
  }

  static String format(String source) {
    SyntaxValidator.Parsed parsed;

    try {
      parsed = SyntaxValidator.parseTree(source);
    } catch (IOException e) {
      return source;
    }

    LiteralScanner.Scan scan = LiteralScanner.scan(source);
    String[] lines = source.split("\n", -1);
    int[] lineStarts = new int[lines.length + 1];

    for (int i = 0; i < lines.length; i++) {
      lineStarts[i + 1] = lineStarts[i] + lines[i].length() + 1;
    }

    Set<Integer> insertBefore = new HashSet<>();
    parsed.unit().accept(new Collector(parsed.unit(), parsed.positions(), source, scan, lines, lineStarts,
      insertBefore), null);

    if (insertBefore.isEmpty()) {
      return source;
    }

    StringBuilder out = new StringBuilder(source.length() + insertBefore.size() * 2);
    String eol = source.contains("\r\n") ? "\r\n" : "\n";

    for (int i = 0; i < lines.length; i++) {
      if (insertBefore.contains(i)) {
        out.append(eol);
      }

      out.append(lines[i]);

      if (i < lines.length - 1) {
        out.append('\n');
      }
    }

    return out.toString();
  }

  private static final class Collector extends TreeScanner<Void, Void> {
    private final CompilationUnitTree unit;
    private final SourcePositions positions;
    private final String source;
    private final LiteralScanner.Scan scan;
    private final String[] lines;
    private final int[] lineStarts;
    private final Set<Integer> insertBefore;
    /** Anonymous class bodies and `else if` branches: not blocks that start their own statement. */
    private final Set<Tree> skipped = new HashSet<>();

    Collector(CompilationUnitTree unit, SourcePositions positions, String source, LiteralScanner.Scan scan,
            String[] lines, int[] lineStarts, Set<Integer> insertBefore) {
      this.unit = unit;
      this.positions = positions;
      this.source = source;
      this.scan = scan;
      this.lines = lines;
      this.lineStarts = lineStarts;
      this.insertBefore = insertBefore;
    }

    @Override
    public Void visitClass(ClassTree node, Void unused) {
      if (!skipped.contains(node)) {
        consider(node, true);
      }

      for (Tree member : node.getMembers()) {
        if (member instanceof BlockTree initializer) {
          // static / instance initializer
          consider(initializer, true);
        }
      }

      return super.visitClass(node, unused);
    }

    @Override
    public Void visitNewClass(NewClassTree node, Void unused) {
      if (node.getClassBody() != null) {
        skipped.add(node.getClassBody());
      }

      return super.visitNewClass(node, unused);
    }

    @Override
    public Void visitMethod(MethodTree node, Void unused) {
      consider(node, node.getBody() != null);
      return super.visitMethod(node, unused);
    }

    @Override
    public Void visitIf(IfTree node, Void unused) {
      if (node.getElseStatement() instanceof IfTree elseIf) {
        skipped.add(elseIf);
      }

      if (!skipped.contains(node)) {
        consider(node, node.getThenStatement() instanceof BlockTree);
      }

      return super.visitIf(node, unused);
    }

    @Override
    public Void visitForLoop(ForLoopTree node, Void unused) {
      consider(node, node.getStatement() instanceof BlockTree);
      return super.visitForLoop(node, unused);
    }

    @Override
    public Void visitEnhancedForLoop(EnhancedForLoopTree node, Void unused) {
      consider(node, node.getStatement() instanceof BlockTree);
      return super.visitEnhancedForLoop(node, unused);
    }

    @Override
    public Void visitWhileLoop(WhileLoopTree node, Void unused) {
      consider(node, node.getStatement() instanceof BlockTree);
      return super.visitWhileLoop(node, unused);
    }

    @Override
    public Void visitDoWhileLoop(DoWhileLoopTree node, Void unused) {
      consider(node, node.getStatement() instanceof BlockTree);
      return super.visitDoWhileLoop(node, unused);
    }

    @Override
    public Void visitSwitch(SwitchTree node, Void unused) {
      consider(node, true);
      return super.visitSwitch(node, unused);
    }

    @Override
    public Void visitTry(TryTree node, Void unused) {
      consider(node, true);
      return super.visitTry(node, unused);
    }

    @Override
    public Void visitSynchronized(SynchronizedTree node, Void unused) {
      consider(node, true);
      return super.visitSynchronized(node, unused);
    }

    /** Queues blank lines around {@code node} if it is a multi-line, brace-bodied block starting its own line. */
    private void consider(Tree node, boolean hasBraceBody) {
      if (!hasBraceBody) {
        return;
      }

      long start = positions.getStartPosition(unit, node);
      long end = positions.getEndPosition(unit, node);

      if (start < 0 || end <= start) {
        return;
      }

      int startLine = lineOf((int) start);
      int endLine = lineOf((int) end - 1);

      if (startLine == endLine || !source.substring(lineStarts[startLine], (int) start).isBlank()) {
        // one-line block, or it doesn't start its own line
        return;
      }

      // Before: above the attached comment lines.
      int head = startLine;

      while (head > 0 && isOwnLineComment(head - 1)) {
        head--;
      }

      if (head > 0 && !lines[head - 1].isBlank() && !opensScope(head - 1)) {
        insertBefore.add(head);
      }

      // After: before the next line, unless it closes a scope or starts a case label.
      int next = endLine + 1;

      if (next < lines.length && !lines[next].isBlank() && !closesScopeOrLabel(next)) {
        insertBefore.add(next);
      }
    }

    private int lineOf(int offset) {
      int lo = 0;
      int hi = lines.length - 1;

      while (lo < hi) {
        int mid = (lo + hi + 1) >>> 1;

        if (lineStarts[mid] <= offset) {
          lo = mid;
        } else {
          hi = mid - 1;
        }
      }

      return lo;
    }

    /** Whether line {@code i} starts inside a comment that owns the line (comment lines above a block). */
    private boolean isOwnLineComment(int i) {
      String line = lines[i];
      String trimmed = line.stripLeading();

      if (trimmed.isEmpty()) {
        return false;
      }

      int first = lineStarts[i] + (line.length() - trimmed.length());
      LiteralScanner.Kind kind = scan.kindAt(first);

      if (kind != LiteralScanner.Kind.LINE_COMMENT && kind != LiteralScanner.Kind.BLOCK_COMMENT) {
        return false;
      }

      // Nothing but the comment on the line: the span reaches the end of the line's content.
      LiteralScanner.VerbatimSpan span = scan.spanAt(first);
      return
        source.substring(Math.min(span.end(), lineStarts[i] + line.length()), lineStarts[i] + line.length()).isBlank()
        || span.end() >= lineStarts[i] + line.length();
    }

    /** The code on line {@code i}, with any trailing comment removed and whitespace trimmed. */
    private String code(int i) {
      String line = lines[i];
      StringBuilder code = new StringBuilder();

      for (int k = 0; k < line.length(); k++) {
        LiteralScanner.Kind kind = scan.kindAt(lineStarts[i] + k);

        if (kind == LiteralScanner.Kind.LINE_COMMENT || kind == LiteralScanner.Kind.BLOCK_COMMENT) {
          continue;
        }

        code.append(line.charAt(k));
      }

      return code.toString().strip();
    }

    /** Whether the line before a block already begins its parent scope ({@code {}, a label). */
    private boolean opensScope(int i) {
      String code = code(i);
      return code.endsWith("{")
                || code.startsWith("case ") && code.endsWith(":")
                || code.equals("default:")
                || code.matches("[A-Za-z_$][\\w$]*:");
    }

    private boolean closesScopeOrLabel(int i) {
      String code = code(i);

      if (code.isEmpty()) {
        // A comment-only line belongs to whatever follows; look through to that.
        for (int k = i + 1; k < lines.length; k++) {
          code = code(k);

          if (!code.isEmpty()) {
            break;
          }
        }

        if (code.isEmpty()) {
          return false;
        }
        // The comment itself is what follows the block: a blank goes before it unless the
        // code it leads closes a scope.
      }

      return code.startsWith("}") || code.startsWith(")") || code.startsWith("]")
                || code.startsWith("case ") || code.startsWith("default:") || code.startsWith("default ->")
                || code.startsWith("else") || code.startsWith("catch") || code.startsWith("finally");
    }
  }
}
