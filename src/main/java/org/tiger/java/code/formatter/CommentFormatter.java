package org.tiger.java.code.formatter;

import java.util.ArrayList;
import java.util.List;

/**
 * Moves every trailing comment - a {@code //} comment, or a single-line {@code /* ... *}{@code /}
 * comment that ends its line, with code before it on the same line - onto its own line directly
 * above the code it follows, with that line's leading whitespace. ({@link ScopeReindenter} then
 * aligns it with the code it comments.) A comment is never left at the end of a line of code.
 *
 * <p>Two placements differ from "directly above":
 * <ul>
 *   <li>A line that closes one block and opens the next ({@code } else { // why}, {@code } catch (...)
 *       { // why}) puts the comment on the first line <em>inside</em> the opened block, since above
 *       the line it would read as part of the block being closed.</li>
 *   <li>A line holding text-block content or its closing delimiter is never touched: a line
 *       inserted there would change the string's value.</li>
 * </ul>
 *
 * <p>A trailing comment that starts with a tool marker ({@code NOSONAR}, {@code NOPMD},
 * {@code CHECKSTYLE}, {@code noinspection}, {@code nosemgrep}, {@code lgtm}, {@code nolint},
 * {@code $NON-NLS}) stays on its line: such markers only work on the line they suppress.
 *
 * <p>Comments that start their own line, block comments with code after them on the line
 * ({@code foo(/* flag *}{@code / true)}), and multi-line block comments are left as they are.
 */
final class CommentFormatter {

    /** Trailing comments beginning with one of these must stay where they are (they only work there). */
    private static final java.util.regex.Pattern TOOL_MARKER = java.util.regex.Pattern.compile(
        "^(//+|/\\*+)\\s*(NOSONAR|NOPMD|NOCHECKSTYLE|CHECKSTYLE|noinspection|nosemgrep|lgtm|nolint|\\$NON-NLS)",
        java.util.regex.Pattern.CASE_INSENSITIVE);

    private CommentFormatter() {
    }

    private record Move(int lineStart, int lineEnd, String replacement) {
    }

    static String format(String source) {
        LiteralScanner.Scan scan = LiteralScanner.scan(source);
        String eol = source.contains("\r\n") ? "\r\n" : "\n";
        List<Move> moves = new ArrayList<>();
        int lastLineStart = -1;
        for (LiteralScanner.VerbatimSpan span : scan.spans()) {
            boolean lineComment = span.kind() == LiteralScanner.Kind.LINE_COMMENT;
            boolean blockComment = span.kind() == LiteralScanner.Kind.BLOCK_COMMENT;
            if (!lineComment && !blockComment) {
                continue;
            }
            int lineStart = source.lastIndexOf('\n', span.start() - 1) + 1;
            if (lineStart == lastLineStart) {
                continue; // a second comment on a line already handled
            }
            int newline = source.indexOf('\n', span.end());
            int lineEnd = newline == -1 ? source.length() : newline;
            int contentEnd = lineEnd > lineStart && source.charAt(lineEnd - 1) == '\r' ? lineEnd - 1 : lineEnd;
            if (blockComment) {
                boolean singleLine = source.indexOf('\n', span.start()) == -1 || source.indexOf('\n', span.start()) >= span.end();
                boolean endsLine = source.substring(span.end(), contentEnd).isBlank();
                if (!singleLine || !endsLine) {
                    continue;
                }
            }
            String prefix = source.substring(lineStart, span.start());
            Boolean hasCode = codeBefore(scan, lineStart, prefix);
            if (hasCode == null || !hasCode) {
                continue; // text-block line (untouchable) or the comment owns its line
            }
            String leading = prefix.substring(0, prefix.length() - prefix.stripLeading().length());
            String code = prefix.stripTrailing();
            String comment = source.substring(span.start(), blockComment ? span.end() : contentEnd).strip();
            if (TOOL_MARKER.matcher(comment).find()) {
                continue;
            }
            String trimmedCode = code.strip();
            String replacement;
            if (trimmedCode.startsWith("}") && trimmedCode.endsWith("{")) {
                replacement = code + eol + leading + comment;
            } else {
                replacement = leading + comment + eol + code;
            }
            moves.add(new Move(lineStart, contentEnd, replacement));
            lastLineStart = lineStart;
        }
        if (moves.isEmpty()) {
            return source;
        }
        StringBuilder out = new StringBuilder(source);
        for (int i = moves.size() - 1; i >= 0; i--) {
            Move move = moves.get(i);
            out.replace(move.lineStart(), move.lineEnd(), move.replacement());
        }
        return out.toString();
    }

    /**
     * Whether {@code prefix} (the text of the line before the comment) holds code. Returns
     * {@code null} when it touches a text block, which must not be edited around.
     */
    private static Boolean codeBefore(LiteralScanner.Scan scan, int lineStart, String prefix) {
        boolean code = false;
        for (int k = 0; k < prefix.length(); k++) {
            if (Character.isWhitespace(prefix.charAt(k))) {
                continue;
            }
            LiteralScanner.Kind kind = scan.kindAt(lineStart + k);
            if (kind == LiteralScanner.Kind.TEXT_BLOCK) {
                return null;
            }
            if (kind != LiteralScanner.Kind.LINE_COMMENT && kind != LiteralScanner.Kind.BLOCK_COMMENT) {
                code = true;
            }
        }
        return code;
    }
}
