package code.formatter.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.tiger.java.code.formatter.SourceFormatter;

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
  void textBlockWhitespaceTheCompilerIgnoresIsNotAChange() {
    String block = "String t = \"\"\"\n    one\n\n    two\n    \"\"\";";

    // a whitespace-only line, trailing spaces, CRLF, and a uniform shift of every line including the closing delimiter
    assertTrue(SourceFormatter.sameProgram(block, block.replace("\n\n", "\n        \n")));
    assertTrue(SourceFormatter.sameProgram(block, block.replace("one", "one   ")));
    assertTrue(SourceFormatter.sameProgram(block, block.replace("\n", "\r\n")));
    assertTrue(SourceFormatter.sameProgram(block, block.replace("\n    ", "\n          ")));
  }

  @Test
  void textBlockChangesTheCompilerSeesAreStillChanges() {
    String block = "String t = \"\"\"\n    one\n\n    two\n    \"\"\";";

    assertFalse(SourceFormatter.sameProgram(block, block.replace("one", "on")));
    // the closing delimiter moves, so every line gains indentation in the value
    assertFalse(SourceFormatter.sameProgram(block, block.replace("\n    \"\"\"", "\n  \"\"\"")));
    // a blank line is removed
    assertFalse(SourceFormatter.sameProgram(block, block.replace("\n\n", "\n")));
    // the text starts on the opening line
    assertFalse(SourceFormatter.sameProgram(block, block.replace("\"\"\"\n    one", "\"\"\"one")));
    assertFalse(SourceFormatter.sameProgram(block, block.replace("two", "two\\s")));
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
