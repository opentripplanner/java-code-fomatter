# Development

The project is built with [Jenesis](https://jenesis.build/). The build engine is in `build/jenesis/` as plain Java
source. You need JDK 25 or newer and nothing else.

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

Tests are in `src/test/java/`, wired as a Jenesis `@jenesis.test` module on JUnit 5. `FormatterGoldenFileTest` is one
data-driven test over every `src/test/java/cases/**/<name>/{Input.java.txt,Expected.java.txt}` pair. To add coverage,
add a fixture folder, not code. Every case also gets an idempotency check: formatting `Expected.java.txt` again must
change nothing. Fixtures end in `.java.txt` because Jenesis compiles every `.java` file under `src/test/java`.

The cases share `src/test/java/cases/.jcf/config.properties` (`wrapAtColumn=60`), in the same format as a real
configuration. A narrow limit keeps fixtures short.

## The Maven plugin

The plugin is built separately with Maven, because Jenesis does not produce plugin descriptors:

```bash
cd maven-plugin
./build.sh verify
```

This builds the formatter, installs it into the local Maven repository, builds the plugin and runs the integration
tests in `maven-plugin/src/it`.
