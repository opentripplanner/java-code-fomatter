package code.formatter.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.tiger.java.code.formatter.FormatterConfig;
import org.tiger.java.code.formatter.SourceFormatter;

/** Each comment rule on its own and in combination: a rule changes only what it owns. */
class JavadocRulesTest {

  private static final String SOURCE = """
      class S {
        /**
         * First paragraph written on one line that is much longer than the sixty column limit here.
         * Short line.
         *
         * Second paragraph after a blank line.
         * <P>Third paragraph, with the tag glued to the text.
         * <ul>
         *   <li>a list item that is long enough to be wrapped because it passes the limit
         *     continued
         * </ul>
         * <h2>Heading</h2>
         * Text under it.
         * @param x the x value that is described in a sentence longer than the limit allows
         *      continued with odd indent
         */
        void f(int x) {
        }
      }
      """;

  private static String format(Set<String> rules) throws IOException {
    FormatterConfig config = new FormatterConfig(60, 2, rules);

    return ((SourceFormatter.Formatted) SourceFormatter.format(SOURCE, config)).text();
  }

  @Test
  void noRulesLeavesTheComment() throws IOException {
    assertEquals(SOURCE, format(Set.of()));
  }

  @Test
  void wrap() throws IOException {
    assertEquals("""
      class S {
        /**
         * First paragraph written on one line that is much longer
         * than the sixty column limit here.
         * Short line.
         *
         * Second paragraph after a blank line.
         * <P>Third paragraph, with the tag glued to the text.
         * <ul>
         *   <li>a list item that is long enough to be wrapped
         *       because it passes the limit
         *     continued
         * </ul>
         * <h2>Heading</h2>
         * Text under it.
         * @param x the x value that is described in a sentence
         *     longer than the limit allows
         *      continued with odd indent
         */
        void f(int x) {
        }
      }
      """, format(Set.of("wrap")));
  }

  @Test
  void paragraphs() throws IOException {
    assertEquals("""
      class S {
        /**
         * First paragraph written on one line that is much longer than the sixty column limit here.
         * Short line.
         * <p>
         * Second paragraph after a blank line.
         * <p>
         * Third paragraph, with the tag glued to the text.
         * <ul>
         *   <li>a list item that is long enough to be wrapped because it passes the limit
         *     continued
         * </ul>
         * <h2>Heading</h2>
         * Text under it.
         * @param x the x value that is described in a sentence longer than the limit allows
         *      continued with odd indent
         */
        void f(int x) {
        }
      }
      """, format(Set.of("paragraphs")));
  }

  @Test
  void blankLines() throws IOException {
    assertEquals("""
      class S {
        /**
         * First paragraph written on one line that is much longer than the sixty column limit here.
         * Short line.
         *
         * Second paragraph after a blank line.
         * <P>Third paragraph, with the tag glued to the text.
         * <ul>
         *   <li>a list item that is long enough to be wrapped because it passes the limit
         *     continued
         * </ul>
         *
         * <h2>Heading</h2>
         * Text under it.
         *
         * @param x the x value that is described in a sentence longer than the limit allows
         *      continued with odd indent
         */
        void f(int x) {
        }
      }
      """, format(Set.of("blank-lines")));
  }

  @Test
  void indent() throws IOException {
    assertEquals("""
      class S {
        /**
         * First paragraph written on one line that is much longer than the sixty column limit here.
         * Short line.
         *
         * Second paragraph after a blank line.
         * <P>Third paragraph, with the tag glued to the text.
         * <ul>
         *   <li>a list item that is long enough to be wrapped because it passes the limit
         *       continued
         * </ul>
         * <h2>Heading</h2>
         * Text under it.
         * @param x the x value that is described in a sentence longer than the limit allows
         *     continued with odd indent
         */
        void f(int x) {
        }
      }
      """, format(Set.of("indent")));
  }

  @Test
  void wrapParagraphsBlankLinesIndent() throws IOException {
    assertEquals("""
      class S {
        /**
         * First paragraph written on one line that is much longer
         * than the sixty column limit here.
         * Short line.
         * <p>
         * Second paragraph after a blank line.
         * <p>
         * Third paragraph, with the tag glued to the text.
         * <ul>
         *   <li>a list item that is long enough to be wrapped
         *       because it passes the limit
         *       continued
         * </ul>
         *
         * <h2>Heading</h2>
         * Text under it.
         *
         * @param x the x value that is described in a sentence
         *     longer than the limit allows
         *     continued with odd indent
         */
        void f(int x) {
        }
      }
      """, format(Set.of("wrap", "paragraphs", "blank-lines", "indent")));
  }

  @Test
  void fill() throws IOException {
    assertEquals("""
      class S {
        /**
         * First paragraph written on one line that is much longer
         * than the sixty column limit here. Short line.
         * <p>
         * Second paragraph after a blank line.
         * <p>
         * Third paragraph, with the tag glued to the text.
         * <ul>
         *   <li>a list item that is long enough to be wrapped
         *       because it passes the limit continued
         * </ul>
         *
         * <h2>Heading</h2>
         * Text under it.
         *
         * @param x the x value that is described in a sentence
         *     longer than the limit allows continued with odd
         *     indent
         */
        void f(int x) {
        }
      }
      """, format(Set.of("fill")));
  }

  @Test
  void all() throws IOException {
    assertEquals("""
      class S {
        /**
         * First paragraph written on one line that is much longer
         * than the sixty column limit here. Short line.
         * <p>
         * Second paragraph after a blank line.
         * <p>
         * Third paragraph, with the tag glued to the text.
         * <ul>
         *   <li>a list item that is long enough to be wrapped
         *       because it passes the limit continued
         * </ul>
         *
         * <h2>Heading</h2>
         * Text under it.
         *
         * @param x the x value that is described in a sentence
         *     longer than the limit allows continued with odd
         *     indent
         */
        void f(int x) {
        }
      }
      """, format(FormatterConfig.COMMENT_RULES));
  }

  @Test
  void paragraphRuleLeavesSpacingAroundInlineTagsAlone() throws IOException {
    String source = "class T {\n  /**\n   * @param direction  whether to build a {@link java.util.Map  here}   forward tree\n   */\n  void f() {\n  }\n}\n";

    assertEquals(source, ((SourceFormatter.Formatted) SourceFormatter.format(source, new FormatterConfig(100, 2, Set.of("paragraphs")))).text());
  }
}
