package code.formatter.test;

import java.io.IOException;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.tiger.java.code.formatter.FormatterConfig;
import org.tiger.java.code.formatter.SourceFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code removeUnusedImports} option (default off): imports whose name is mentioned nowhere in the file go. */
class ImportRemovalTest {

  private static final FormatterConfig ON = new FormatterConfig(
    120,
    2,
    Set.of(),
    FormatterConfig.Documents.DEFAULT,
    true,
    true
  );

  private static String format(String source, FormatterConfig config) throws IOException {
    SourceFormatter.FormatResult result = SourceFormatter.format(source, config);
    assertTrue(result instanceof SourceFormatter.Formatted, () -> "not formatted: " + result);
    return ((SourceFormatter.Formatted) result).text();
  }

  private static String format(String source) throws IOException {
    return format(source, ON);
  }

  @Test
  void anUnusedImportIsRemovedAndAUsedOneIsKept() throws IOException {
    String source = """
      package p;

      import java.util.List;
      import java.util.Map;

      class A {
        List<String> names;
      }
      """;

    assertEquals("""
      package p;

      import java.util.List;

      class A {
        List<String> names;
      }
      """, format(source));
  }

  @Test
  void importsAreKeptByDefault() throws IOException {
    String source = "import java.util.Map;\n\nclass A {\n}\n";

    assertEquals(source, format(source, FormatterConfig.DEFAULT));
  }

  @Test
  void theBlankLinesAroundRemovedImportsAreCollapsed() throws IOException {
    String source = """
      package p;

      import java.util.Map;

      class A {
      }
      """;

    assertEquals("package p;\n\nclass A {\n}\n", format(source));
    assertEquals("class A {\n}\n", format("import java.util.Map;\n\nclass A {\n}\n"));
  }

  @Test
  void aNameUsedOnlyInJavadocIsKept() throws IOException {
    String source = """
      import java.util.List;
      import java.util.Set;

      /** See {@link List#of()}. */
      class A {
      }
      """;

    assertEquals(source.replace("import java.util.Set;\n", ""), format(source));
  }

  @Test
  void staticImportsFollowTheSameRule() throws IOException {
    String source = """
      import static java.lang.Math.max;
      import static java.lang.Math.min;

      class A {
        int f() {
          return max(1, 2);
        }
      }
      """;

    assertEquals(source.replace("import static java.lang.Math.min;\n", ""), format(source));
  }

  @Test
  void aStaticImportOfAnOverloadedNameIsKeptWhileTheNameIsUsed() throws IOException {
    String source = """
      import static a.Strings.format;
      import static b.Dates.format;

      class A {
        String f() {
          return format(date);
        }
      }
      """;

    assertEquals(source, format(source));
  }

  @Test
  void wildcardImportsAreNeverRemoved() throws IOException {
    String source = "import java.util.*;\nimport static java.lang.Math.*;\n\nclass A {\n}\n";

    assertEquals(source, format(source));
  }

  @Test
  void textThatLooksLikeAnImportIsNotTouched() throws IOException {
    String source = """
      import java.util.Map;

      class A {
        // import java.util.Set;
        String s = \"""
          import java.util.List;
          \""" + Map.of();
      }
      """;

    assertEquals(source, format(source));
  }

  @Test
  void formattingTwiceChangesNothing() throws IOException {
    String once = format("import java.util.Map;\nimport java.util.List;\n\nclass A {\n  List<A> a;\n}\n");

    assertEquals(once, format(once));
  }

  @Test
  void theOptionIsReadFromTheProperties() {
    Properties properties = new Properties();

    assertFalse(FormatterConfig.from(properties).removeUnusedImports());

    properties.setProperty("removeUnusedImports", "true");
    assertTrue(FormatterConfig.from(properties).removeUnusedImports());

    properties.setProperty("removeUnusedImports", "yes");
    assertThrows(IllegalArgumentException.class, () -> FormatterConfig.from(properties));
  }
}
