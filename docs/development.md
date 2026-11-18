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

Tests are in `formatter/src/test/java/`, wired as a Jenesis `@jenesis.test` module on JUnit 5. `FormatterGoldenFileTest` is one
data-driven test over every `formatter/src/test/java/cases/**/<name>/{Input.java.txt,Expected.java.txt}` pair. To add coverage,
add a fixture folder, not code. Every case also gets an idempotency check: formatting `Expected.java.txt` again must
change nothing. Fixtures end in `.java.txt` because Jenesis compiles every `.java` file under `formatter/src/test/java`.

The cases share `formatter/src/test/java/cases/.jcf/config.properties` (`wrapAtColumn=60`), in the same format as a real
configuration. A narrow limit keeps fixtures short.

## The Maven plugin

The plugin is built separately with Maven, because Jenesis does not produce plugin descriptors:

```bash
cd maven-plugin
./build.sh verify
```

This builds the formatter, installs it into the local Maven repository, builds the plugin and runs the integration
tests in `maven-plugin/src/it`.

## Publishing to Maven Central

Two artifacts are published: `java-code-formatter` (built by `formatter/pom.xml` from the same sources as Jenesis) and
`java-formatter-maven-plugin`. The `release` profile of both poms adds the source and javadoc jars, signs everything with GPG
and uploads a bundle with the Central Publisher plugin.

You need these once:

1. A [Sonatype Central](https://central.sonatype.com) account with a verified namespace for the `groupId`
   (`org.opentripplanner.java.code.formatter`).
2. A user token from Central, stored in `~/.m2/settings.xml` under the server id `central`.
3. A GPG key, with the public key on a key server such as `keyserver.ubuntu.com`.

To release:

```bash
# 1. Set the version in both poms, maven-plugin/build.sh and the READMEs, then tag it.
# 2. Check everything, without signing or uploading:
maven-plugin/build.sh verify
mvn -f formatter/pom.xml -Prelease -Dgpg.skip verify
mvn -f maven-plugin/pom.xml -Prelease -Dgpg.skip verify

# 3. Publish. Do the formatter first: the plugin depends on it.
mvn -f formatter/pom.xml -Prelease deploy
mvn -f maven-plugin/pom.xml -Prelease deploy
```

Each deploy uploads a bundle that you then check and publish in the Central portal. A published version cannot be
removed or changed.
