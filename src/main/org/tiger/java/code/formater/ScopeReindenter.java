package org.tiger.java.code.formater;

/**
 * Reindents Java source purely by brace nesting: the leading whitespace of every line is replaced
 * with {@code depth * 4} spaces, where {@code depth} is how many non-verbatim {@code {} } braces
 * are currently open. Nothing else about a line is touched - no token spacing, no brace insertion.
 *
 * <p>A line whose previous (real) line doesn't end its statement - its last non-comment,
 * non-whitespace character isn't {@code ;}, {@code {} or {@code }} - is a continuation of that
 * statement (e.g. a line {@link LineWrapper} split off, or one a human already wrapped by hand) and
 * gets one extra indent level instead of the current brace depth. This is what makes wrapping
 * idempotent: reformatting an already-wrapped line recognizes it as a continuation instead of
 * flattening it back to the statement's own depth.
 *
 * <p>Text-block interiors, and the line holding a text block's closing {@code """}, are left
 * completely untouched: per JLS 3.10.6 a text block's value depends on the common leading
 * whitespace of its content lines *and* its closing delimiter line, so reindenting that line would
 * silently change the string's value while keeping the file perfectly valid Java.
 */
final class ScopeReindenter {

    private ScopeReindenter() {
    }

    static String reindent(String source, int indentWidth) {
        LiteralScanner.Scan scan = LiteralScanner.scan(source);
        StringBuilder out = new StringBuilder(source.length());
        int depth = 0;
        boolean previousEndsStatement = true;
        boolean previousNetBracesZero = true;
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
                if (scan.kindAt(firstNonWs) == LiteralScanner.Kind.TEXT_BLOCK) {
                    out.append(rawLine);
                } else {
                    // A continuation only when the previous line neither ended its statement NOR
                    // already opened a still-unclosed brace of its own (e.g. an array initializer's
                    // '{' straddling the wrap point) - that case already deepens `depth` on its own,
                    // so adding a continuation bonus on top of it would double-count the nesting.
                    boolean isContinuation = !previousEndsStatement && previousNetBracesZero;
                    int leadingCloses = countLeadingCloses(scan, lineStart, contentEnd);
                    int baseDepth;
                    if (isContinuation) {
                        // Never *decrease* a continuation line's depth below what it already had:
                        // ScopeReindenter only knows brace/scope depth, not operator precedence, so
                        // it can't derive a multi-level cascade like ExpressionWrapper's (e.g. a
                        // bitwise expression split across several precedence tiers) - but it must
                        // not flatten one back down to a single "+1" either, or reformatting
                        // already-wrapped output wouldn't be idempotent. depth + 1 is still the
                        // floor, so a continuation that's genuinely new (not already deeper) still
                        // gets the normal single-level bump.
                        int existingIndent = rawLine.length() - trimmed.length();
                        int preExistingDepth = existingIndent / indentWidth + leadingCloses;
                        baseDepth = Math.max(depth + 1, preExistingDepth);
                    } else {
                        baseDepth = depth;
                    }
                    int displayDepth = Math.max(0, baseDepth - leadingCloses);
                    out.append(" ".repeat(displayDepth * indentWidth));
                    out.append(trimmed);
                }
                int netBraces = countNetBraces(scan, lineStart, contentEnd);
                depth += netBraces;
                depth = Math.max(0, depth);
                Boolean endsStatement = classifyLineEnd(scan, lineStart, contentEnd);
                if (endsStatement != null) {
                    previousEndsStatement = endsStatement;
                    previousNetBracesZero = netBraces == 0;
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

    /** Net change in brace depth contributed by a line's non-verbatim {@code {}/}}} characters. */
    private static int countNetBraces(LiteralScanner.Scan scan, int lineStart, int contentEnd) {
        String source = scan.source();
        int net = 0;
        for (int i = lineStart; i < contentEnd; i++) {
            if (scan.isVerbatim(i)) {
                continue;
            }
            char c = source.charAt(i);
            if (c == '{') {
                net++;
            } else if (c == '}') {
                net--;
            }
        }
        return net;
    }

    /**
     * Whether a line's own code ends a statement/block ({@code true}: last real character is
     * {@code ;}, {@code {} or {@code }}), continues one ({@code false}), or is blank/comment-only
     * and transparent to continuation tracking ({@code null}). Trailing line/block comments don't
     * count as the line's "real" ending.
     */
    private static Boolean classifyLineEnd(LiteralScanner.Scan scan, int lineStart, int contentEnd) {
        String source = scan.source();
        char last = 0;
        boolean found = false;
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
            last = c;
            found = true;
        }
        if (!found) {
            return null;
        }
        return last == ';' || last == '{' || last == '}';
    }
}
