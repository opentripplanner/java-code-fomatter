# Jenesis

[Jenesis](https://jenesis.build/) builds a Java project from its `module-info.java`. It has no plugin system to
install. A custom step is small Java code. The formatter is an ordinary Java module, so there are two simple ways to
use it. This project itself is built with Jenesis.

## Fail the build when a file is not formatted

Use the formatter as a library in a test. Jenesis runs the tests as part of `java build/jenesis/Make.java`, so an
unformatted file breaks the build:

```java
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.tiger.java.code.formatter.FormatRun;

class FormattingTest {

  @Test
  void sourcesAreFormatted() {
    FormatRun.Report report = FormatRun.run(FormatRun.javaFilesUnder(Path.of("src")), FormatRun.Mode.CHECK, true);

    assertTrue(report.failed().isEmpty(), "files that could not be formatted: " + report.failed());
    assertTrue(report.changed().isEmpty(), "run the formatter with -w: " + report.changed());
  }
}
```

Add the formatter as a dependency of the test module in the same way as for any other library, and `requires` its
module in `src/test/java/module-info.java`. The path `src` is relative to the working directory of the test run. If your tests run from another directory, build an
absolute path to your source folder instead. This snippet has not been run inside a Jenesis build of another project, so
treat it as a starting point.

## Format from the command line

Build or download the formatter jar, then run it on the module path:

```bash
java -p formatter.jar -m org.tiger.java.code.formatter -w src
```

The repository's `otp-format` script is an example of this.

## Note

The test above is a pattern, not something this repository ships. Jenesis can also run a project-specific step before
the build. See the [Jenesis documentation](https://jenesis.build/tool/) for how a custom step is written, and call
`FormatRun` from it in the same way.
