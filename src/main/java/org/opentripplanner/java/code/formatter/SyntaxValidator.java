package org.opentripplanner.java.code.formatter;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Parses Java source with the JDK's own compiler front end - {@code javax.tools}/ {@code com.sun.source} - and reports
 * whether it's syntactically valid, without doing any semantic analysis (symbol/type resolution). That's deliberate: a
 * formatter shouldn't need a classpath, and shouldn't reject a file just because it references a type it can't resolve.
 */
final class SyntaxValidator {

  record ValidationResult(boolean valid, List<Diagnostic<? extends JavaFileObject>> diagnostics) {
  }

  /** A parsed compilation unit plus the {@link SourcePositions} needed to locate its nodes in source. */
  record Parsed(CompilationUnitTree unit, SourcePositions positions) {
  }

  private SyntaxValidator() {
  }

  /**
   * Parses {@code source} and returns its tree, assuming it's already known to be syntactically valid (e.g. via a prior
   * {@link #validate(String)} call) - callers that haven't validated first should expect a possibly-incomplete tree on
   * malformed input, not an exception.
   */
  static Parsed parseTree(String source) throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

    try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
      JavaCompiler.CompilationTask task = compiler.getTask(
                new StringWriter(), fileManager, diagnostics, null, null, List.of(new StringSource(source)));
      JavacTask javacTask = (JavacTask) task;
      CompilationUnitTree unit = javacTask.parse().iterator().next();
      SourcePositions positions = Trees.instance(javacTask).getSourcePositions();
      return new Parsed(unit, positions);
    }
  }

  static ValidationResult validate(Path file) throws IOException {
    return validate(fileManager -> fileManager.getJavaFileObjectsFromPaths(List.of(file)));
  }

  static ValidationResult validate(String source) throws IOException {
    return validate(fileManager -> List.of(new StringSource(source)));
  }

  private interface UnitSupplier {
    Iterable<? extends JavaFileObject> get(StandardJavaFileManager fileManager) throws IOException;
  }

  private static ValidationResult validate(UnitSupplier units) throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

    try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
      JavaCompiler.CompilationTask task = compiler.getTask(
                new StringWriter(), fileManager, diagnostics, null, null, units.get(fileManager));
      JavacTask javacTask = (JavacTask) task;
      javacTask.parse();
    }

    boolean hasError = diagnostics.getDiagnostics().stream()
            .anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
    return new ValidationResult(!hasError, diagnostics.getDiagnostics());
  }

  private static final class StringSource extends SimpleJavaFileObject {
    private final String content;

    StringSource(String content) {
      super(URI.create("string:///Formatted.java"), Kind.SOURCE);
      this.content = content;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return content;
    }
  }
}
