package org.tiger.java.code.formater;

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

    private static final int INDENT_WIDTH = 4;

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

    static WrapResult wrap(String source, int maxColumns) {
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

            if (contentEnd - lineStart <= maxColumns) {
                out.append(source, lineStart, contentEnd);
            } else {
                List<String> fragments = wrapLine(scan, lineStart, contentEnd, bracketDepth, maxColumns);
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
    private static List<String> wrapLine(LiteralScanner.Scan scan, int rangeStart, int rangeEnd, int depthAtStart, int maxColumns) {
        String source = scan.source();
        int printedIndent = 0;
        while (rangeStart + printedIndent < rangeEnd && source.charAt(rangeStart + printedIndent) == ' ') {
            printedIndent++;
        }
        int leadingCloses = ScopeReindenter.countLeadingCloses(scan, rangeStart, rangeEnd);
        int logicalIndent = printedIndent + leadingCloses * INDENT_WIDTH;
        int continuationBaseIndent = logicalIndent + INDENT_WIDTH;

        List<String> fragments = new ArrayList<>();
        int curStart = rangeStart;
        int curColumnOffset = 0;
        int curDepth = depthAtStart;
        boolean first = true;
        while (true) {
            int visualLength = curColumnOffset + (rangeEnd - curStart);
            if (visualLength <= maxColumns) {
                fragments.add(first ? source.substring(curStart, rangeEnd) : withIndent(scan, curStart, rangeEnd, curColumnOffset));
                return fragments;
            }
            BreakPoint bp = findBreakPoint(scan, curStart, rangeEnd, curColumnOffset, curDepth, maxColumns);
            if (bp == null) {
                return null;
            }
            fragments.add(first ? source.substring(curStart, bp.keepEnd) : withIndent(scan, curStart, bp.keepEnd, curColumnOffset));
            curDepth += netBrackets(scan, curStart, bp.continueStart);
            curStart = bp.continueStart;
            int fragLeadingCloses = ScopeReindenter.countLeadingCloses(scan, curStart, rangeEnd);
            curColumnOffset = Math.max(0, continuationBaseIndent - fragLeadingCloses * INDENT_WIDTH);
            first = false;
        }
    }

    private static String withIndent(LiteralScanner.Scan scan, int start, int end, int indentSpaces) {
        return " ".repeat(indentSpaces) + scan.source().substring(start, end);
    }

    private record BreakPoint(int keepEnd, int continueStart) {
    }

    private static BreakPoint findBreakPoint(LiteralScanner.Scan scan, int start, int end, int columnOffset, int depthAtStart, int maxColumns) {
        String source = scan.source();
        List<Candidate> commas = new ArrayList<>();
        List<Candidate> operators = new ArrayList<>();
        int depth = depthAtStart;

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
            if (c == '(' || c == '[') {
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
                if (!prevDigit && !nextDigit) {
                    operators.add(new Candidate(depth, i));
                }
                i++;
            } else {
                int matched = matchOperator(source, i, end);
                if (matched > 0) {
                    operators.add(new Candidate(depth, i));
                    i += matched;
                } else {
                    i++;
                }
            }
        }

        BreakPoint fromComma = bestByDepth(source, commas, start, columnOffset, true, maxColumns);
        if (fromComma != null) {
            return fromComma;
        }
        return bestByDepth(source, operators, start, columnOffset, false, maxColumns);
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
    private static BreakPoint bestByDepth(String source, List<Candidate> candidates, int rangeStart, int columnOffset, boolean afterToken, int maxColumns) {
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
                if (visual <= maxColumns && (best == null || c.position() > best.position())) {
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
