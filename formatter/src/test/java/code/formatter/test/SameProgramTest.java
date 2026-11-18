package code.formatter.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.opentripplanner.java.code.formatter.SourceFormatter;

/** The token-equivalence check that proves formatting changed only whitespace and comments. */
class SameProgramTest {

  @Test
  void whitespaceAndCommentsDoNotMatter() {
    assertTrue(SourceFormatter.sameProgram("int a=1+2; // note\n", "int a = 1\n    + 2; /* other */\n"));
  }

  @Test
  void aChangedIdentifierOrLiteralDoes() {
    assertFalse(SourceFormatter.sameProgram("int a = 1;", "int b = 1;"));
    assertFalse(SourceFormatter.sameProgram("String s = \"a b\";", "String s = \"a  b\";"));
    assertFalse(SourceFormatter.sameProgram("char c = 'a';", "char c = 'b';"));
  }

  @Test
  void mergedOrSplitOperatorsDiffer() {
    assertFalse(SourceFormatter.sameProgram("x = a - -b;", "x = a --b;"));
    assertFalse(SourceFormatter.sameProgram("x = a - b;", "x = a -- b;"));
    assertFalse(SourceFormatter.sameProgram("x = a && b;", "x = a & b;"));
  }

  @Test
  void spacingInsideGenericsIsNotAChange() {
    assertTrue(SourceFormatter.sameProgram("List<List<String>> x;", "List<List<String> > x;"));
  }

  @Test
  void textBlocksAreKeptExactly() {
    String block = "String t = \"\"\"\n    one\n      two\n    \"\"\";";

    assertTrue(SourceFormatter.sameProgram(block, "  " + block.replace("String t =", "String t =\n  ")));
    assertFalse(SourceFormatter.sameProgram(block, block.replace("      two", "     two")));
  }

  @Test
  void numbersWithExponentsAreOneToken() {
    assertTrue(SourceFormatter.sameProgram("double d = 1.5e-3 + 0x1p+2;", "double d =\n  1.5e-3\n  + 0x1p+2;"));
    assertFalse(SourceFormatter.sameProgram("double d = 1.5e-3;", "double d = 1.5e-4;"));
  }

  @Test
  void aCommentedOutStatementIsNotCode() {
    assertTrue(SourceFormatter.sameProgram("a();\n// b();\n", "a();\n"));
    assertFalse(SourceFormatter.sameProgram("a();\n", "a();\nb();\n"));
  }
}
