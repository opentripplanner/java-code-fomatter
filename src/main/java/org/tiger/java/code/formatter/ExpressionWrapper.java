package org.tiger.java.code.formatter;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Recursively wraps specific statement shapes - variable/field declarations with an initializer,
 * plain assignment statements, {@code return}, {@code throw} - at real operator-precedence
 * boundaries, using the JDK's own AST (`com.sun.source`) rather than character scanning: a
 * hand-rolled scan cannot reliably resolve real Java grammar ambiguity (generics vs relational,
 * ternary {@code ?} vs wildcard, casts vs parens, lambdas), which is exactly the class of bug
 * {@link LineWrapper}'s simpler, flat, greedy approach is prone to.
 *
 * <p>Everything this engine doesn't specifically handle - multi-declarator statements, bare call
 * statements, control-flow headers, method signatures, annotations - is left untouched here and
 * picked up by the existing {@link LineWrapper} afterward, unchanged from its v1 behavior.
 *
 * <p>Core model: a "list" (one precedence tier's flattened operands, call arguments, chain
 * segments) either renders whole on one line or every element gets its own line - never partial
 * packing. Siblings of one list share one indent level; only when a specific list *slot* itself
 * overflows into its own (tighter) nested list does that nested list step one level deeper -
 * confirmed via a worked multi-tier bitwise cascade. A "sole occupant" position - a bracket's
 * single piece of content, a call's single argument, a unary/cast/lambda operand - is not itself a
 * multi-element list, so it reuses the level it was given rather than adding another step.
 */
final class ExpressionWrapper {

    private record Ctx(String source, CompilationUnitTree unit, SourcePositions positions, FormatterConfig config,
        LiteralScanner.Scan scan) {

        int start(Tree tree) {
            return (int) positions.getStartPosition(unit, tree);
        }

        int end(Tree tree) {
            return (int) positions.getEndPosition(unit, tree);
        }

        String text(Tree tree) {
            return source.substring(start(tree), end(tree));
        }

        int indentWidth() {
            return config.indent();
        }

        int wrapAtColumn() {
            return config.wrapAtColumn();
        }

        /** A budget reduced by {@code n} columns, for reserving room for trailing punctuation
         * (the statement-terminating `;`) that isn't part of the tree being rendered but will
         * still land on whatever the rendering's last line turns out to be. */
        Ctx reserving(int n) {
            return new Ctx(source, unit, positions, new FormatterConfig(Math.max(1, config.wrapAtColumn() - n), config.indent()), scan);
        }
    }

    private record Edit(int start, int end, String replacement) {
    }

    private ExpressionWrapper() {
    }

    static String wrap(String source, FormatterConfig config) {
        try {
            SyntaxValidator.Parsed parsed = SyntaxValidator.parseTree(source);
            Ctx ctx = new Ctx(source, parsed.unit(), parsed.positions(), config, LiteralScanner.scan(source));
            List<Edit> edits = new ArrayList<>();
            parsed.unit().accept(new CandidateScanner(ctx, edits), null);
            return applyEdits(source, edits);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String applyEdits(String source, List<Edit> edits) {
        List<Edit> sorted = new ArrayList<>(edits);
        sorted.sort((a, b) -> Integer.compare(b.start(), a.start()));
        StringBuilder out = new StringBuilder(source);
        int previousStart = Integer.MAX_VALUE;
        for (Edit edit : sorted) {
            // Defensive: two edits should never overlap (each targets one statement's own span),
            // but silently applying an overlapping edit would corrupt the output text - skip it
            // instead and let the statement fall through unformatted rather than risk that.
            if (edit.end() > previousStart) {
                continue;
            }
            previousStart = edit.start();
            out.replace(edit.start(), edit.end(), edit.replacement());
        }
        return out.toString();
    }

    /** Finds each in-scope statement and, if its own line is over budget, queues its rewritten text. */
    private static final class CandidateScanner extends TreeScanner<Void, Void> {
        private final Ctx ctx;
        private final List<Edit> edits;

        CandidateScanner(Ctx ctx, List<Edit> edits) {
            this.ctx = ctx;
            this.edits = edits;
        }

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            super.visitVariable(node, unused);
            ExpressionTree initializer = node.getInitializer();
            if (initializer != null && !isMultiDeclarator(node)) {
                // An enum constant is a VariableTree too, with a synthetic initializer (the
                // implicit constructor call) positioned at the constant itself rather than after a
                // real '=' - not a declaration this wrapper's `<type> <name> = <expr>` model
                // applies to.
                String prefix = ctx.source().substring(ctx.start(node), ctx.start(initializer));
                int equals = prefix.lastIndexOf('=');
                if (equals >= 0) {
                    String declaratorText = prefix.substring(0, equals).stripTrailing();
                    tryWrapDeclarator(declaratorText, node, initializer, ctx, edits);
                }
            }
            return null;
        }

        @Override
        public Void visitExpressionStatement(ExpressionStatementTree node, Void unused) {
            super.visitExpressionStatement(node, unused);
            if (node.getExpression() instanceof AssignmentTree assignment) {
                tryWrapDeclarator(ctx.text(assignment.getVariable()), assignment, assignment.getExpression(), ctx, edits);
            }
            return null;
        }

        @Override
        public Void visitIf(IfTree node, Void unused) {
            super.visitIf(node, unused);
            tryWrapConditionHeader(node, node.getCondition(), ctx, edits);
            return null;
        }

        @Override
        public Void visitWhileLoop(WhileLoopTree node, Void unused) {
            super.visitWhileLoop(node, unused);
            tryWrapConditionHeader(node, node.getCondition(), ctx, edits);
            return null;
        }

        @Override
        public Void visitEnhancedForLoop(EnhancedForLoopTree node, Void unused) {
            super.visitEnhancedForLoop(node, unused);
            tryWrapForEachHeader(node, ctx, edits);
            return null;
        }

        @Override
        public Void visitForLoop(ForLoopTree node, Void unused) {
            // Not super.visitForLoop: the header's own init/update clauses must not be treated as
            // declaration/assignment statements (their edits would overlap the header rewrite).
            scan(node.getCondition(), unused);
            for (Tree update : node.getUpdate()) {
                scan(update instanceof ExpressionStatementTree es ? es.getExpression() : update, unused);
            }
            scan(node.getStatement(), unused);
            tryWrapForHeader(node, ctx, edits);
            return null;
        }

        @Override
        public Void visitReturn(ReturnTree node, Void unused) {
            super.visitReturn(node, unused);
            if (node.getExpression() != null) {
                tryWrapKeyword("return", node, node.getExpression(), ctx, edits);
            }
            return null;
        }

        @Override
        public Void visitThrow(ThrowTree node, Void unused) {
            super.visitThrow(node, unused);
            tryWrapKeyword("throw", node, node.getExpression(), ctx, edits);
            return null;
        }

        /**
         * Detects `int a = 1, b = 2;`. javac gives every declarator in such a group the *same*
         * start position (the shared type) while each one's own end cumulatively grows to cover
         * every earlier declarator plus its own trailing comma or, for the last one, the group's
         * closing `;` - confirmed by direct inspection, not documented API behavior. So: a comma
         * between this node's start and its own name means an earlier declarator's tail precedes
         * it (this is the 2nd+ declarator); a comma (rather than `;`) as the last non-whitespace
         * character before this node's own end means there's more declarator group after it (this
         * is the 1st/2nd+ declarator, not the last).
         */
        private boolean isMultiDeclarator(VariableTree node) {
            String source = ctx.source();
            int start = ctx.start(node);
            int nameStart = source.indexOf(node.getName().toString(), start);
            // This range is only ever modifiers/annotations/a type (never an arbitrary
            // expression), so '<'/'>' can only be generic delimiters here - track depth to skip
            // commas inside a type argument list like `Map<String, Integer>`.
            int angleDepth = 0;
            for (int i = start; i < nameStart; i++) {
                char c = source.charAt(i);
                if (c == '<') {
                    angleDepth++;
                } else if (c == '>') {
                    angleDepth = Math.max(0, angleDepth - 1);
                } else if (c == ',' && angleDepth == 0) {
                    return true;
                }
            }
            int before = ctx.end(node) - 1;
            while (before >= 0 && Character.isWhitespace(source.charAt(before))) {
                before--;
            }
            return before >= 0 && source.charAt(before) == ',';
        }
    }

    // ---- declarator / assignment / return / throw entry points ----

    /** `lhsText = rhs;` - `=` always stays with the LHS. The RHS glues right on if it's an
     * always-glue construct (chain, bare call, unary, cast, lambda); otherwise it starts fresh. */
    private static void tryWrapDeclarator(String lhsText, Tree owner, ExpressionTree rhs, Ctx ctx, List<Edit> edits) {
        tryWrapKeywordLike(lhsText + " =", owner, rhs, ctx, edits);
    }

    /** `keyword expr;` (`return`/`throw`): same rule - keyword glues only for always-glue constructs. */
    private static void tryWrapKeyword(String keyword, Tree owner, ExpressionTree expr, Ctx ctx, List<Edit> edits) {
        if (expr instanceof LiteralTree) {
            // `return "…long string…";` - a lone literal can't be split, and moving it to its own
            // line only trades a long line for a long line (unlike a declaration, where the
            // string conventionally moves under the `=`).
            return;
        }
        tryWrapKeywordLike(keyword, owner, expr, ctx, edits);
    }

    private static void tryWrapKeywordLike(String keywordText, Tree owner, ExpressionTree expr, Ctx ctx, List<Edit> edits) {
        int ownerStart = ctx.start(owner);
        int lineStart = lineStartOf(ctx.source(), ownerStart);
        int level = (ownerStart - lineStart) / ctx.indentWidth();

        int stmtEnd = statementEnd(ctx, expr);
        int lastLineEnd = lineEndOf(ctx.source(), stmtEnd);
        if (!anyLineExceedsBudget(ctx, lineStart, lastLineEnd)) {
            return;
        }

        if (containsComment(ctx, ownerStart, stmtEnd + 1)) {
            return;
        }

        // Reserve one column throughout for the statement-terminating ';', which isn't part of
        // `expr` but always lands on whatever line this rendering's content ends on.
        Ctx reserved = ctx.reserving(1);

        List<String> lines;
        if (isAlwaysGlue(expr)) {
            List<String> rendered = render(expr, keywordText.length() + 1, level + 1, reserved);
            lines = new ArrayList<>();
            lines.add(keywordText + " " + rendered.get(0));
            lines.addAll(rendered.subList(1, rendered.size()));
        } else {
            int indent = (level + 1) * ctx.indentWidth();
            List<String> rendered = render(expr, indent, level + 1, reserved);
            lines = new ArrayList<>();
            lines.add(keywordText);
            lines.add(" ".repeat(indent) + rendered.get(0));
            lines.addAll(rendered.subList(1, rendered.size()));
        }
        int indentWidth = ctx.indentWidth();
        int newWorst = level * indentWidth + lines.get(0).length();
        for (int i = 1; i < lines.size(); i++) {
            newWorst = Math.max(newWorst, lines.get(i).length());
        }
        int originalWorst = maxLineWidth(ctx, lineStart, lastLineEnd);
        if (newWorst >= originalWorst) {
            // Nothing this engine can do actually shortens the worst line (e.g. an atomic
            // over-length string literal, or a construct outside its scope like an array
            // initializer) - leave the statement untouched rather than splitting for no benefit.
            return;
        }

        StringBuilder replacement = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                replacement.append('\n');
            }
            replacement.append(lines.get(i));
        }
        replacement.append(';');
        edits.add(new Edit(ownerStart, stmtEnd + 1, replacement.toString()));
    }

    // ---- control-flow headers: if / while / for ----

    /**
     * `if (cond) {` / `while (cond) {` whose header line is over budget becomes
     * {@code if (\n    cond\n) {}: the contents one level deeper, the closer back at the
     * statement's own level - the same bracket shape as a call's argument list.
     */
    private static void tryWrapConditionHeader(Tree owner, ExpressionTree condition, Ctx ctx, List<Edit> edits) {
        if (!(condition instanceof ParenthesizedTree paren)) {
            return;
        }
        int open = ctx.start(paren);
        int close = ctx.end(paren) - 1;
        tryWrapHeader(ctx.start(owner), open, close, ctx, edits, level -> {
            int indent = (level + 1) * ctx.indentWidth();
            return indented(render(paren.getExpression(), indent, level + 1, ctx), indent);
        });
    }

    /** `for (Type v : expr) {`: the whole `Type v : expr` on one line if it fits one level in,
     * else `Type v :` followed by the iterated expression one level deeper still. */
    private static void tryWrapForEachHeader(EnhancedForLoopTree node, Ctx ctx, List<Edit> edits) {
        int ownerStart = ctx.start(node);
        int open = ctx.source().indexOf('(', ownerStart);
        int close = headerClose(ctx, node.getStatement());
        if (open < 0 || close < open) {
            return;
        }
        tryWrapHeader(ownerStart, open, close, ctx, edits, level -> {
            int indent = (level + 1) * ctx.indentWidth();
            String declaration = ctx.text(node.getVariable());
            String expression = ctx.text(node.getExpression());
            if (expression.indexOf('\n') < 0 && indent + declaration.length() + 3 + expression.length() <= ctx.wrapAtColumn()) {
                return List.of(" ".repeat(indent) + declaration + " : " + expression);
            }
            int innerIndent = (level + 2) * ctx.indentWidth();
            List<String> lines = new ArrayList<>();
            lines.add(" ".repeat(indent) + declaration + " :");
            lines.addAll(indented(render(node.getExpression(), innerIndent, level + 2, ctx), innerIndent));
            return lines;
        });
    }

    /** `for (init; cond; update) {`: each clause on its own line, `;` trailing the first two. */
    private static void tryWrapForHeader(ForLoopTree node, Ctx ctx, List<Edit> edits) {
        if (node.getInitializer().size() != 1 || node.getCondition() == null || node.getUpdate().size() != 1) {
            return;
        }
        int ownerStart = ctx.start(node);
        int open = ctx.source().indexOf('(', ownerStart);
        int close = headerClose(ctx, node.getStatement());
        if (open < 0 || close < open) {
            return;
        }
        tryWrapHeader(ownerStart, open, close, ctx, edits, level -> {
            int indent = (level + 1) * ctx.indentWidth();
            List<String> lines = new ArrayList<>();
            lines.add(" ".repeat(indent) + ctx.text(node.getInitializer().get(0)) + ";");
            List<String> condition = indented(render(node.getCondition(), indent, level + 1, ctx), indent);
            lines.addAll(condition);
            lines.set(lines.size() - 1, lines.get(lines.size() - 1) + ";");
            Tree update = node.getUpdate().get(0);
            ExpressionTree updateExpression = update instanceof ExpressionStatementTree es ? es.getExpression() : null;
            if (updateExpression == null) {
                lines.add(" ".repeat(indent) + ctx.text(update));
            } else {
                lines.addAll(indented(render(updateExpression, indent, level + 1, ctx), indent));
            }
            return lines;
        });
    }

    /** The offset of the {@code ')'} closing a `for`/`while` header: the last one before the body. */
    private static int headerClose(Ctx ctx, Tree body) {
        int i = ctx.start(body) - 1;
        while (i >= 0 && Character.isWhitespace(ctx.source().charAt(i))) {
            i--;
        }
        return i >= 0 && ctx.source().charAt(i) == ')' ? i : -1;
    }

    /** Prefixes the first rendered line with {@code indent} spaces (later lines carry their own). */
    private static List<String> indented(List<String> rendered, int indent) {
        List<String> out = new ArrayList<>(rendered);
        out.set(0, " ".repeat(indent) + out.get(0));
        return out;
    }

    /**
     * Rewrites the header's parenthesised part {@code ( ... )} (offsets {@code open}..{@code close}
     * inclusive) as {@code (\n<content>\n<level indent>)} when the line holding it is over
     * budget and the rewrite actually shortens the worst line.
     */
    private static void tryWrapHeader(int ownerStart, int open, int close, Ctx ctx, List<Edit> edits,
        java.util.function.IntFunction<List<String>> content) {
        String source = ctx.source();
        int lineStart = lineStartOf(source, ownerStart);
        int lastLineEnd = lineEndOf(source, close);
        if (!anyLineExceedsBudget(ctx, lineStart, lastLineEnd)) {
            return;
        }
        if (containsComment(ctx, open, close + 1)) {
            return;
        }
        int level = 0;
        while (lineStart + level * ctx.indentWidth() < source.length()
            && source.startsWith(" ".repeat(ctx.indentWidth()), lineStart + level * ctx.indentWidth())) {
            level++;
        }
        List<String> lines = content.apply(level);

        int worst = open + 1 - lineStart;
        for (String line : lines) {
            worst = Math.max(worst, line.length());
        }
        worst = Math.max(worst, level * ctx.indentWidth() + 1 + (lastLineEnd - (close + 1)));
        if (worst >= maxLineWidth(ctx, lineStart, lastLineEnd)) {
            return;
        }

        StringBuilder replacement = new StringBuilder("(");
        for (String line : lines) {
            replacement.append('\n').append(line);
        }
        replacement.append('\n').append(" ".repeat(level * ctx.indentWidth())).append(')');
        edits.add(new Edit(open, close + 1, replacement.toString()));
    }

    /** Whether `expr`'s own top construct always glues to whatever precedes it (never isolated alone). */
    private static boolean isAlwaysGlue(ExpressionTree expr) {
        return expr instanceof MethodInvocationTree
            || expr instanceof UnaryTree ut && unaryPrefix(ut) != null
            || expr instanceof TypeCastTree
            || expr instanceof LambdaExpressionTree lt && lt.getBodyKind() == LambdaExpressionTree.BodyKind.EXPRESSION;
    }

    /** The end of `expr`, plus the trailing `;` that must immediately follow it in valid Java here. */
    private static int statementEnd(Ctx ctx, ExpressionTree expr) {
        int end = ctx.end(expr);
        while (end < ctx.source().length() && Character.isWhitespace(ctx.source().charAt(end))) {
            end++;
        }
        return end; // points at ';'
    }

    /**
     * Whether {@code [start, end)} holds a comment. The AST-based rendering rebuilds text from
     * its nodes, which carry no comments, so rewriting such a span would silently delete them.
     */
    private static boolean containsComment(Ctx ctx, int start, int end) {
        for (int i = start; i < end; i++) {
            LiteralScanner.Kind kind = ctx.scan().kindAt(i);
            if (kind == LiteralScanner.Kind.LINE_COMMENT || kind == LiteralScanner.Kind.BLOCK_COMMENT) {
                return true;
            }
        }
        return false;
    }

    private static int lineStartOf(String source, int offset) {
        int nl = source.lastIndexOf('\n', offset - 1);
        return nl + 1;
    }

    private static int lineEndOf(String source, int offset) {
        int nl = source.indexOf('\n', offset);
        return nl == -1 ? source.length() : nl;
    }

    /** Whether any single physical line in {@code [rangeStart, rangeEnd]} exceeds the budget -
     * a multi-line statement (e.g. one with a text-block initializer) must be checked line by
     * line, since the raw distance between its start and end isn't a meaningful column count. */
    private static boolean anyLineExceedsBudget(Ctx ctx, int rangeStart, int rangeEnd) {
        String source = ctx.source();
        int lineStart = rangeStart;
        while (lineStart <= rangeEnd) {
            int nl = source.indexOf('\n', lineStart);
            int lineEnd = (nl == -1 || nl > rangeEnd) ? rangeEnd : nl;
            if (lineEnd - lineStart > ctx.wrapAtColumn()) {
                return true;
            }
            if (nl == -1 || nl > rangeEnd) {
                break;
            }
            lineStart = nl + 1;
        }
        return false;
    }

    /** The length of the longest physical line in {@code [rangeStart, rangeEnd]}. */
    private static int maxLineWidth(Ctx ctx, int rangeStart, int rangeEnd) {
        String source = ctx.source();
        int lineStart = rangeStart;
        int max = 0;
        while (lineStart <= rangeEnd) {
            int nl = source.indexOf('\n', lineStart);
            int lineEnd = (nl == -1 || nl > rangeEnd) ? rangeEnd : nl;
            max = Math.max(max, lineEnd - lineStart);
            if (nl == -1 || nl > rangeEnd) {
                break;
            }
            lineStart = nl + 1;
        }
        return max;
    }

    // ---- the general recursive expression renderer ----

    private record OperandSlot(String operatorText, ExpressionTree operand) {
    }

    /**
     * Renders {@code expr} to be placed right after {@code columnUsed} characters already on the
     * current line (the first returned line carries no leading indent of its own - the caller
     * glues it on). {@code level} is the indent level a *new* list this construct introduces
     * should use directly (no further +1 here - only a list's own slot-overflow recursion adds a
     * level, see {@link #renderBinaryCascade}). Returns a single verbatim line when it fits.
     */
    private static List<String> render(ExpressionTree expr, int columnUsed, int level, Ctx ctx) {
        String verbatim = ctx.text(expr);
        boolean multiLine = verbatim.indexOf('\n') >= 0;
        // A multi-line verbatim span is either genuinely opaque content (text block, switch
        // expression, array/anon-class body - handled below, in the fallback) or, on a second
        // formatting pass, an expression this engine itself already wrapped last time (its
        // sub-pieces are still single-line, so re-dispatching below reproduces the same result -
        // this is what makes wrapping idempotent). Either way, a multi-line span never "fits".
        if (!multiLine && columnUsed + verbatim.length() <= ctx.wrapAtColumn()) {
            return List.of(verbatim);
        }

        if (expr instanceof BinaryTree bt && tierOf(bt.getKind()) >= 0) {
            return betterOf(verbatim, columnUsed, renderBinaryCascade(bt, columnUsed, level, ctx));
        }
        if (expr instanceof ConditionalExpressionTree ct) {
            return betterOf(verbatim, columnUsed, renderTernary(ct, columnUsed, level, ctx));
        }
        if (expr instanceof UnaryTree ut && unaryPrefix(ut) != null) {
            return betterOf(verbatim, columnUsed, renderPrefixed(unaryPrefix(ut), ut.getExpression(), columnUsed, level, ctx));
        }
        if (expr instanceof TypeCastTree tct) {
            String prefix = "(" + ctx.text(tct.getType()) + ") ";
            return betterOf(verbatim, columnUsed, renderPrefixed(prefix, tct.getExpression(), columnUsed, level, ctx));
        }
        if (expr instanceof LambdaExpressionTree lt && lt.getBodyKind() == LambdaExpressionTree.BodyKind.EXPRESSION) {
            String prefix = lambdaPrefix(lt, ctx);
            return betterOf(verbatim, columnUsed, renderPrefixed(prefix, (ExpressionTree) lt.getBody(), columnUsed, level, ctx));
        }
        if (expr instanceof ParenthesizedTree pt) {
            return betterOf(verbatim, columnUsed, renderBracketed("(", pt.getExpression(), ")", columnUsed, level, ctx));
        }
        if (expr instanceof MethodInvocationTree mit) {
            return betterOf(verbatim, columnUsed, renderCallOrChain(mit, columnUsed, level, ctx));
        }

        // Atomic / not specifically handled (instanceof, literals, identifiers, new/switch
        // expressions, array access, ...): nothing this engine can usefully do. If it's already
        // multi-line (a text block, switch expression, array/anon-class body), hand it off
        // unchanged rather than collapsing its embedded newlines into one line; otherwise leave it
        // as one over-length line for LineWrapper or the unwrappable-line report to pick up.
        return multiLine ? List.of(verbatim.split("\n", -1)) : List.of(verbatim);
    }

    /** The split guard: only use a multi-line rendering if it actually shortens the longest line. */
    private static List<String> betterOf(String verbatim, int columnUsed, List<String> split) {
        if (split.size() <= 1) {
            return List.of(verbatim);
        }
        int worstBefore = columnUsed + verbatim.length();
        int worstAfter = 0;
        for (int i = 0; i < split.size(); i++) {
            int len = (i == 0 ? columnUsed : 0) + split.get(i).length();
            worstAfter = Math.max(worstAfter, len);
        }
        return worstAfter < worstBefore ? split : List.of(verbatim);
    }

    /**
     * Renders a chain of precedence-tier lists reached by repeatedly drilling into slot 0 (e.g.
     * {@code aaaa & bbbb ^ cccc | dddd} fully expanding). Confirmed via a worked multi-tier bitwise
     * example that levels number top-down, outermost to innermost: this call's own tier (layer 0,
     * the loosest-precedence operator actually being split) uses {@code level} directly for its own
     * non-first siblings, exactly like any other list, and each *nested* layer reached by drilling
     * into slot 0 - a tighter-precedence operator, further inside the expression - steps one level
     * deeper still ({@code level + 1}, {@code level + 2}, ...), same as any other bracket's content.
     * This is two-phase: first walk down collecting one layer per tier actually drilled through
     * (stopping the moment a layer's slot 0 fits verbatim, or isn't a further-flattenable binary
     * tree), then render each layer at its now-known level.
     */
    private static List<String> renderBinaryCascade(BinaryTree top, int columnUsed, int level, Ctx ctx) {
        List<List<OperandSlot>> layers = new ArrayList<>();
        ExpressionTree current = top;
        while (true) {
            List<OperandSlot> slots = flattenBinary((BinaryTree) current);
            layers.add(slots);
            ExpressionTree slot0 = slots.get(0).operand();
            String slot0Verbatim = ctx.text(slot0);
            boolean slot0Fits = slot0Verbatim.indexOf('\n') < 0 && columnUsed + slot0Verbatim.length() <= ctx.wrapAtColumn();
            if (!slot0Fits && slot0 instanceof BinaryTree nextBt && tierOf(nextBt.getKind()) >= 0) {
                current = nextBt;
                continue;
            }
            break;
        }

        int layerCount = layers.size();
        int innermostLevel = level + layerCount - 1;
        List<String> core = render(layers.get(layerCount - 1).get(0).operand(), columnUsed, innermostLevel + 1, ctx);
        List<String> out = new ArrayList<>(core);

        for (int i = layerCount - 1; i >= 0; i--) {
            List<OperandSlot> slots = layers.get(i);
            int thisLevel = level + i;
            int indent = thisLevel * ctx.indentWidth();
            for (int s = 1; s < slots.size(); s++) {
                OperandSlot slot = slots.get(s);
                String prefix = slot.operatorText() + " ";
                List<String> rendered = render(slot.operand(), indent + prefix.length(), thisLevel + 1, ctx);
                out.add(" ".repeat(indent) + prefix + rendered.get(0));
                out.addAll(rendered.subList(1, rendered.size()));
            }
        }
        return out;
    }

    /** A sole-occupant operand (unary/cast/lambda body): no sibling to disambiguate from, so it reuses {@code level} directly. */
    private static List<String> renderPrefixed(String prefix, ExpressionTree operand, int columnUsed, int level, Ctx ctx) {
        List<String> rendered = render(operand, columnUsed + prefix.length(), level, ctx);
        List<String> out = new ArrayList<>();
        out.add(prefix + rendered.get(0));
        out.addAll(rendered.subList(1, rendered.size()));
        return out;
    }

    /** `open` glued to whatever precedes it (at {@code level}); its sole content always steps one level deeper (a bracket's content is never at the bracket's own level); `close` back at {@code level}. */
    private static List<String> renderBracketed(String open, ExpressionTree content, String close, int columnUsed, int level, Ctx ctx) {
        List<String> out = new ArrayList<>();
        out.add(open);
        int contentLevel = level + 1;
        int indent = contentLevel * ctx.indentWidth();
        List<String> contentLines = render(content, indent, contentLevel, ctx);
        out.add(" ".repeat(indent) + contentLines.get(0));
        out.addAll(contentLines.subList(1, contentLines.size()));
        out.add(" ".repeat(level * ctx.indentWidth()) + close);
        return out;
    }

    private static List<String> renderTernary(ConditionalExpressionTree ct, int columnUsed, int level, Ctx ctx) {
        List<String> out = new ArrayList<>();
        int indent = level * ctx.indentWidth();
        List<String> cond = render(ct.getCondition(), columnUsed, level + 1, ctx);
        out.add(cond.get(0));
        out.addAll(cond.subList(1, cond.size()));

        List<String> thenBranch = render(ct.getTrueExpression(), indent + 2, level + 1, ctx);
        out.add(" ".repeat(indent) + "? " + thenBranch.get(0));
        out.addAll(thenBranch.subList(1, thenBranch.size()));

        ExpressionTree elseExpr = ct.getFalseExpression();
        if (elseExpr instanceof ConditionalExpressionTree chained) {
            List<String> chainedRendering = renderTernary(chained, indent + 2, level + 1, ctx);
            out.add(" ".repeat(indent) + ": " + chainedRendering.get(0));
            out.addAll(chainedRendering.subList(1, chainedRendering.size()));
        } else {
            List<String> elseBranch = render(elseExpr, indent + 2, level + 1, ctx);
            out.add(" ".repeat(indent) + ": " + elseBranch.get(0));
            out.addAll(elseBranch.subList(1, elseBranch.size()));
        }
        return out;
    }

    // ---- method chains and call arguments ----

    private sealed interface Segment {
        record Head(ExpressionTree tree) implements Segment {
        }

        record Dot(String name, MemberSelectTree select, MethodInvocationTree call) implements Segment {
        }
    }

    private static List<String> renderCallOrChain(MethodInvocationTree mit, int columnUsed, int level, Ctx ctx) {
        List<Segment> segments = collectChain(mit);
        if (segments.size() == 1) {
            // A bare call, name(args) - no receiver dot. "name(" glues, args are a sole-occupant list.
            Segment.Head head = (Segment.Head) segments.get(0);
            String name = ctx.text(head.tree());
            return renderCall(name, mit.getArguments(), columnUsed, level, ctx);
        }

        Segment.Head head = (Segment.Head) segments.get(0);
        List<String> receiver = render(head.tree(), columnUsed, level, ctx);
        List<String> out = new ArrayList<>(receiver);

        // The chain's dots sit at `level`; the line its head is on is one level shallower.
        int dotLevel = level;
        int indent = dotLevel * ctx.indentWidth();
        int first = 1;
        String headText = ctx.text(head.tree());
        boolean stringHead = head.tree() instanceof LiteralTree lt && lt.getKind() == Tree.Kind.STRING_LITERAL;
        boolean singleCall = segments.size() == 2;
        if (receiver.size() == 1 && (stringHead || singleCall || isFactoryHead(headText, ctx.indentWidth()))) {
            // `Type.factory(...)`, `"literal".method(...)` and a lone `receiver.call(...)` keep the
            // first call on the head's line.
            Segment.Dot dot = (Segment.Dot) segments.get(1);
            int identifierStart = ctx.end(dot.select()) - dot.name().length();
            String verbatimSegment = "." + ctx.source().substring(identifierStart, ctx.end(dot.call()));
            int col = columnUsed + headText.length();
            List<String> call;
            if (col + verbatimSegment.length() <= ctx.wrapAtColumn()) {
                call = List.of(verbatimSegment);
            } else {
                List<String> rendered = renderCall(dot.name(), dot.call().getArguments(), col, level - 1, ctx);
                call = new ArrayList<>();
                call.add("." + rendered.get(0));
                call.addAll(rendered.subList(1, rendered.size()));
            }
            out.set(0, out.get(0) + call.get(0));
            out.addAll(call.subList(1, call.size()));
            first = 2;
        }
        for (int i = first; i < segments.size(); i++) {
            Segment.Dot dot = (Segment.Dot) segments.get(i);
            int identifierStart = ctx.end(dot.select()) - dot.name().length();
            String verbatimSegment = "." + ctx.source().substring(identifierStart, ctx.end(dot.call()));
            List<String> call;
            if (indent + verbatimSegment.length() <= ctx.wrapAtColumn()) {
                call = List.of(verbatimSegment);
            } else {
                List<String> rendered = renderCall(dot.name(), dot.call().getArguments(), indent, dotLevel, ctx);
                call = new ArrayList<>();
                call.add("." + rendered.get(0));
                call.addAll(rendered.subList(1, rendered.size()));
            }
            out.add(" ".repeat(indent) + call.get(0));
            out.addAll(call.subList(1, call.size()));
        }
        return out;
    }

    /** A plain, capitalised identifier: a type name, so `Type.factory()`. */
    private static boolean isFactoryHead(String text, int indentWidth) {
        if (!text.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            return false;
        }
        return Character.isUpperCase(text.charAt(0));
    }

    /** Collects `[receiver, .seg1, .seg2, ...]` by walking the method-select chain outer-to-inner, then reversing. */
    private static List<Segment> collectChain(MethodInvocationTree outer) {
        List<Segment> reversed = new ArrayList<>();
        ExpressionTree methodSelect = outer.getMethodSelect();
        MethodInvocationTree current = outer;
        while (true) {
            if (current.getMethodSelect() instanceof MemberSelectTree select) {
                reversed.add(new Segment.Dot(select.getIdentifier().toString(), select, current));
                if (select.getExpression() instanceof MethodInvocationTree inner) {
                    current = inner;
                    continue;
                }
                reversed.add(new Segment.Head(select.getExpression()));
            } else {
                reversed.add(new Segment.Head(methodSelect));
            }
            break;
        }
        List<Segment> out = new ArrayList<>(reversed);
        Collections.reverse(out);
        return out;
    }

    /** `name(` glued to whatever precedes it; arguments always step one level deeper than `name(`
     * itself (never the call's own level - a call's content is never at the call's own level, same
     * as any bracket), strictly one per line with no trailing comma when there's more than one;
     * `)` back at `name(`'s own level. */
    private static List<String> renderCall(String name, List<? extends ExpressionTree> args, int columnUsed, int level, Ctx ctx) {
        if (args.isEmpty()) {
            return List.of(name + "()");
        }
        List<String> out = new ArrayList<>();
        out.add(name + "(");
        int argLevel = level + 1;
        int indent = argLevel * ctx.indentWidth();
        for (int i = 0; i < args.size(); i++) {
            // A sole argument reuses argLevel directly for its own further splitting (matches the
            // confirmed `.foo(xIsLong + cIsLong)` example); 2+ arguments are genuine siblings, so
            // each one's own overflow steps deeper still, same as renderBinaryCascade's slot recursion.
            // A sole argument that is itself a multi-segment chain also steps deeper, so its dots
            // sit one level under its head instead of level with it.
            boolean soleChain = args.size() == 1 && args.get(0) instanceof MethodInvocationTree inner
                && collectChain(inner).size() > 1;
            int childLevel = args.size() == 1 && !soleChain ? argLevel : argLevel + 1;
            String suffix = i < args.size() - 1 ? "," : "";
            List<String> rendered = render(args.get(i), indent, childLevel, suffix.isEmpty() ? ctx : ctx.reserving(1));
            out.add(" ".repeat(indent) + rendered.get(0));
            out.addAll(rendered.subList(1, rendered.size()));
            int lastIndex = out.size() - 1;
            out.set(lastIndex, out.get(lastIndex) + suffix);
        }
        out.add(" ".repeat(level * ctx.indentWidth()) + ")");
        return out;
    }

    // ---- precedence table and small text helpers ----

    private static int tierOf(Tree.Kind kind) {
        return switch (kind) {
            case CONDITIONAL_OR -> 1;
            case CONDITIONAL_AND -> 2;
            case OR -> 3;
            case XOR -> 4;
            case AND -> 5;
            case EQUAL_TO, NOT_EQUAL_TO -> 6;
            case LESS_THAN, GREATER_THAN, LESS_THAN_EQUAL, GREATER_THAN_EQUAL -> 7;
            case LEFT_SHIFT, RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT -> 8;
            case PLUS, MINUS -> 9;
            case MULTIPLY, DIVIDE, REMAINDER -> 10;
            default -> -1;
        };
    }

    private static String operatorTextOf(Tree.Kind kind) {
        return switch (kind) {
            case CONDITIONAL_OR -> "||";
            case CONDITIONAL_AND -> "&&";
            case OR -> "|";
            case XOR -> "^";
            case AND -> "&";
            case EQUAL_TO -> "==";
            case NOT_EQUAL_TO -> "!=";
            case LESS_THAN -> "<";
            case GREATER_THAN -> ">";
            case LESS_THAN_EQUAL -> "<=";
            case GREATER_THAN_EQUAL -> ">=";
            case LEFT_SHIFT -> "<<";
            case RIGHT_SHIFT -> ">>";
            case UNSIGNED_RIGHT_SHIFT -> ">>>";
            case PLUS -> "+";
            case MINUS -> "-";
            case MULTIPLY -> "*";
            case DIVIDE -> "/";
            case REMAINDER -> "%";
            default -> throw new IllegalArgumentException("Not a flattenable binary operator: " + kind);
        };
    }

    private static List<OperandSlot> flattenBinary(BinaryTree top) {
        int tier = tierOf(top.getKind());
        List<OperandSlot> slots = new ArrayList<>();
        collectLeftSpine(top, tier, slots);
        return slots;
    }

    private static void collectLeftSpine(ExpressionTree node, int tier, List<OperandSlot> slots) {
        if (node instanceof BinaryTree bt && tierOf(bt.getKind()) == tier) {
            collectLeftSpine(bt.getLeftOperand(), tier, slots);
            slots.add(new OperandSlot(operatorTextOf(bt.getKind()), bt.getRightOperand()));
        } else {
            slots.add(new OperandSlot(null, node));
        }
    }

    /** Prefix operator text for a *prefix* unary tree (`-x`, `!x`, `~x`, `++x`, `--x`), or {@code null} for postfix. */
    private static String unaryPrefix(UnaryTree ut) {
        return switch (ut.getKind()) {
            case UNARY_PLUS -> "+";
            case UNARY_MINUS -> "-";
            case LOGICAL_COMPLEMENT -> "!";
            case BITWISE_COMPLEMENT -> "~";
            case PREFIX_INCREMENT -> "++";
            case PREFIX_DECREMENT -> "--";
            default -> null; // postfix ++/-- - never a useful place to introduce a line break
        };
    }

    private static String lambdaPrefix(LambdaExpressionTree lt, Ctx ctx) {
        int bodyStart = ctx.start(lt.getBody());
        String head = ctx.source().substring(ctx.start(lt), bodyStart);
        return head.stripTrailing() + " ";
    }
}
