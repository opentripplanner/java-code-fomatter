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

/** The {@code chainedBlocks} rule (default {@code newline}): {@code else}, {@code catch} and {@code finally} on their own line. */
class ChainedBlocksTest {

  private static final String SOURCE = """
    class A {
      void f() {
        try {
          if (a) {
            x();
          } else if (b) { // why b
            y();
          } else {
            z();
          }
        } catch (E e) {
          w();
        } finally {
          done();
        }
        do {
          v();
        } while (c);
      }
    }
    """;

  private static final String ATTACHED = """
    class A {
        void f() {
            try {
                if (a) {
                    x();
                } else if (b) {
                    // why b
                    y();
                } else {
                    z();
                }
            } catch (E e) {
                w();
            } finally {
                done();
            }

            do {
                v();
            } while (c);
        }
    }
    """;

  private static final String NEWLINE = """
    class A {
        void f() {
            try {
                if (a) {
                    x();
                }
                // why b
                else if (b) {
                    y();
                }
                else {
                    z();
                }
            }
            catch (E e) {
                w();
            }
            finally {
                done();
            }

            do {
                v();
            } while (c);
        }
    }
    """;

  private static String format(FormatterConfig config) throws IOException {
    return ((SourceFormatter.Formatted) SourceFormatter.format(SOURCE, config)).text();
  }

  @Test
  void chainedBlocksAreOnTheirOwnLineByDefault() throws IOException {
    assertTrue(FormatterConfig.DEFAULT.chainedBlocksOnNewLine());
    assertEquals(NEWLINE, format(FormatterConfig.DEFAULT));
  }

  @Test
  void attachedKeepsTheKeywordOnTheBraceLine() throws IOException {
    FormatterConfig config = new FormatterConfig(
      120, 4, FormatterConfig.COMMENT_RULES, FormatterConfig.Documents.DEFAULT, false);
    String formatted = format(config);

    assertEquals(ATTACHED, formatted);
    assertEquals(
      formatted, ((SourceFormatter.Formatted) SourceFormatter.format(formatted, config)).text(), "idempotent");
  }

  @Test
  void theKeyIsReadFromProperties() {
    Properties properties = new Properties();
    assertTrue(FormatterConfig.from(properties).chainedBlocksOnNewLine());
    properties.setProperty("chainedBlocks", "attached");
    assertFalse(FormatterConfig.from(properties).chainedBlocksOnNewLine());
    properties.setProperty("chainedBlocks", "newline");
    assertTrue(FormatterConfig.from(properties).chainedBlocksOnNewLine());
    properties.setProperty("chainedBlocks", "sideways");
    assertThrows(IllegalArgumentException.class, () -> FormatterConfig.from(properties));
  }

  @Test
  void aTrailingCommentOnTheBraceMovesAboveTheKeyword() throws IOException {
    String source = "class A {\n  void f() {\n    if (a) {\n      x();\n    } // end of a\n    else {\n      y();\n    }\n  }\n}\n";
    String expected = """
      class A {
          void f() {
              if (a) {
                  x();
              }
              // end of a
              else {
                  y();
              }
          }
      }
      """;

    assertEquals(expected, ((SourceFormatter.Formatted) SourceFormatter.format(source)).text());
  }

  @Test
  void aCommentBetweenTheBraceAndTheKeywordIsLeftAlone() throws IOException {
    FormatterConfig config = new FormatterConfig(
      120, 4, Set.of(), FormatterConfig.Documents.DEFAULT, true);
    String source = "class A {\n  void f() {\n    if (a) {\n      x();\n    } /* c */ else {\n      y();\n    }\n  }\n}\n";
    String formatted = ((SourceFormatter.Formatted) SourceFormatter.format(source, config)).text();

    assertTrue(formatted.contains("} /* c */ else {"), formatted);
  }
}
