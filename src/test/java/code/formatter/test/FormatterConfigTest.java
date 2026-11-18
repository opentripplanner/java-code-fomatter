package code.formatter.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.tiger.java.code.formatter.FormatterConfig;

/** The {@code commentRules} setting. */
class FormatterConfigTest {

  private static FormatterConfig config(String rules) {
    Properties properties = new Properties();

    if (rules != null) {
      properties.setProperty("commentRules", rules);
    }

    return FormatterConfig.from(properties);
  }

  @Test
  void everyRuleIsOnByDefault() {
    assertEquals(FormatterConfig.COMMENT_RULES, config(null).commentRules());
    assertEquals(FormatterConfig.COMMENT_RULES, config("all").commentRules());
  }

  @Test
  void noneTurnsAllRulesOff() {
    assertEquals(Set.of(), config("none").commentRules());
  }

  @Test
  void aListTurnsOnTheNamedRules() {
    assertEquals(Set.of("wrap", "paragraphs"), config("wrap, paragraphs").commentRules());
  }

  @Test
  void anUnknownRuleIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> config("wrap,shrink"));
  }
}
