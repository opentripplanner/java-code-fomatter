# Development

The project is built with [Jenesis](https://jenesis.build/). The build engine is in `build/jenesis/` as plain Java
source. You need JDK 25 or newer and nothing else.

The repository is a Maven multi-module project: the root `pom.xml` is the parent of `formatter` (the library and command
line, in `formatter/`) and `maven-plugin`. Jenesis reads the poms to find the modules and the JUnit dependency.

```bash
java build/jenesis/Make.java              # compile, test and package
java build/jenesis/Make.java stage        # prepare a release tree
java build/jenesis/Make.java dependencies # show resolved dependencies
java build/jenesis/Make.java pin          # resolve and record dependency checksums
```

## Layers of the formatter

`SourceFormatter` runs these steps in order, and finishes with the checks in [Safety](safety.md):

1. Validate the input.
2. `CommentFormatter` moves trailing comments above their line.
3. `BlankLineFormatter` adds blank lines around blocks.
4. `ScopeReindenter` indents by brace nesting.
5. `ExpressionWrapper` wraps declarations, assignments, `return`, `throw`, headers and switch arms using the javac tree.
6. `ScopeReindenter` runs again.
7. `JavadocFormatter` and `LineCommentWrapper` format comments, as chosen by `commentRules`.
8. `LineWrapper` wraps whatever is still too long, greedily.
9. Parse the result again and compare tokens (`TokenStream`).

## Tests

Tests are in `formatter/src/test/java/`, wired as a Jenesis `@jenesis.test` module on JUnit 5. `FormatterGoldenFileTest` is one
data-driven test over every `formatter/src/test/java/cases/**/<name>/{Input.java.txt,Expected.java.txt}` pair. To add coverage,
add a fixture folder, not code. Every case also gets an idempotency check: formatting `Expected.java.txt` again must
change nothing. Fixtures end in `.java.txt` because Jenesis compiles every `.java` file under `formatter/src/test/java`.

The cases share `formatter/src/test/java/cases/.jcf/config.properties` (`wrapAtColumn=60`), in the same format as a real
configuration. A narrow limit keeps fixtures short.

## The Maven plugin

The plugin is built with Maven, because Jenesis does not produce plugin descriptors:

```bash
maven-plugin/build.sh verify   # Jenesis tests, then mvn verify on both modules
mvn verify                     # only Maven: the formatter jar, the plugin and its integration tests
```

This builds the formatter, builds the plugin and runs the integration tests in `maven-plugin/src/it`. Maven does not
run the formatter's unit tests; Jenesis does.

## Trying it on a large code base

`./otp-format <folder>` formats a folder of the OpenTripPlanner submodule in place, using the configuration at the
OTP root.
