package org.tiger.java.code.formatter;

import java.io.IOException;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * Orchestrates the formatting pipeline: validate the input is syntactically valid Java, reindent by brace/scope
 * nesting, wrap lines over {@link FormatterConfig#wrapAtColumn()} columns, then re-validate the result as a safety net
 * before it's ever written or printed.
 */
public final class SourceFormatter {

  public sealed interface FormatResult permits Formatted, ValidationFailed, InternalError {
  }

  public record Formatted(String text, List<Integer> unwrappableLines) implements FormatResult {
  }

  public record ValidationFailed(List<Diagnostic<? extends JavaFileObject>> diagnostics) implements FormatResult {
  }

  public record InternalError(String message) implements FormatResult {
  }

  private SourceFormatter() {
  }

  public static FormatResult format(String source) throws IOException {
    return format(source, FormatterConfig.DEFAULT);
  }

  public static FormatResult format(String source, FormatterConfig config) throws IOException {
    SyntaxValidator.ValidationResult original = SyntaxValidator.validate(source);

    if (!original.valid()) {
      return new ValidationFailed(original.diagnostics());
    }

    String reindented = ScopeReindenter.reindent(
      BlankLineFormatter.format(CommentFormatter.format(source)),
      config.indent()
    );
    // ExpressionWrapper hands multi-line spans (switch expressions, anonymous-class bodies) off
    // verbatim, so after it moves their first line they need reindenting again.
    String expressionWrapped = ScopeReindenter.reindent(ExpressionWrapper.wrap(reindented, config), config.indent());
    String documented = expressionWrapped;
    JavadocFormatter.Rules docRules = JavadocFormatter.Rules.of(config.commentRules());

    if (docRules.any()) {
      documented = JavadocFormatter.format(documented, config.wrapAtColumn(), docRules);
    }

    if (config.commentRules().contains("line-comments")) {
      documented = LineCommentWrapper.format(documented, config.wrapAtColumn());
    }

    LineWrapper.WrapResult wrapped = LineWrapper.wrap(documented, config.wrapAtColumn(), config.indent());

    SyntaxValidator.ValidationResult revalidated = SyntaxValidator.validate(wrapped.text());

    if (!revalidated.valid()) {
      return new InternalError(
                "formatting produced invalid Java; this is a formatter bug, not a problem with the input");
    }

    return new Formatted(wrapped.text(), wrapped.unwrappableLines());
  }
}
